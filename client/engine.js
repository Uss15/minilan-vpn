// MiniLAN client engine: control-plane sync, UDP transport, peer sessions, routing, game firewall.
import { EventEmitter } from 'node:events';
import dgram from 'node:dgram';
import dns from 'node:dns/promises';
import fs from 'node:fs';
import os from 'node:os';
import {
  T, CTRL, genKeyPair, keyPairFromPriv, x25519, peerStatic, sessionKeys,
  encodeInit, encodeResp, decodeHs, verifyHs, encodeData, decodeDataHeader, open,
  encodeUdpReg, udpKeyFor, encodeRelay, encodePunch, encodeCtrl, ReplayWindow,
  ipToInt, intToIp, BROADCAST, inSubnet,
} from '../shared/proto.js';
import { parseIPv4 } from './ip.js';
import { DEFAULT_STUN, stunRequest, isStun, parseStunResponse } from '../shared/stun.js';

const GAMES = JSON.parse(fs.readFileSync(new URL('../shared/games.json', import.meta.url), 'utf8'));
const LIMITED_BCAST = 0xffffffff;

const REKEY_MS = 120_000;
const DEAD_MS = 30_000;
const DIRECT_STALE_MS = 15_000;
const KEEPALIVE_MS = 10_000;
const PROBE_MS = 5_000;
const REG_MS = 15_000;
const STUN_MS = 60_000;

function parsePorts(list) {
  return (list || []).map((r) => {
    const [a, b] = String(r).split('-').map(Number);
    return [a, b ?? a];
  });
}
const portIn = (ranges, p) => ranges.some(([a, b]) => p >= a && p <= b);

export class Engine extends EventEmitter {
  constructor({
    serverUrl, name, statePath = null, tun = null, port = 0, noDirect = false, localEndpoints = null, relayPrefer = null, log = () => {},
    stunServers = DEFAULT_STUN, // learn our public UDP address even when the server is only reachable over HTTP
    serverUdp = true, // false simulates a server whose UDP port is unreachable (e.g. behind an HTTP-only tunnel)
  }) {
    super();
    this.stunServers = stunServers;
    this.serverUdp = serverUdp;
    this.stunAddrs = new Map(); // "addr:port" of STUN servers we query
    this.stunEndpoint = null;
    this.ws = null; // Relay/TCP over WebSocket, used when UDP to every relay fails
    this.wsOpen = false;
    this.lastWsTry = 0;
    this.relayPrefer = relayPrefer; // pin a relay by name (e.g. a region), falls back to lowest RTT
    this.serverUrl = serverUrl.replace(/\/$/, '');
    this.name = name;
    this.statePath = statePath;
    this.tun = tun;
    this.port = port;
    this.noDirect = noDirect;
    this.localEndpointsOverride = localEndpoints;
    this.log = log;
    this.peers = new Map(); // id -> peer
    this.byIp = new Map(); // ipInt -> peer
    this.networks = [];
    this.version = 0;
    this.publicEndpoint = null;
    this.running = false;
    this.pendingPings = new Map();
    this.relays = new Map(); // "addr:port" -> { name, region, addr, port, key, rtt, sentAt, lastAck, builtin }
  }

  // ---------- persistence + registration ----------

  loadState() {
    let st = {};
    if (this.statePath && fs.existsSync(this.statePath)) st = JSON.parse(fs.readFileSync(this.statePath, 'utf8'));
    const kp = st.priv ? keyPairFromPriv(Buffer.from(st.priv, 'base64')) : genKeyPair();
    this.keys = kp;
    this.token = st.token || null;
    this.id = st.id || null;
    this.ip = st.ip || null;
  }

  saveState() {
    if (!this.statePath) return;
    fs.writeFileSync(this.statePath, JSON.stringify({
      priv: this.keys.priv.toString('base64'), token: this.token, id: this.id, ip: this.ip, server: this.serverUrl,
    }, null, 1));
  }

  async api(method, path, body) {
    const res = await fetch(this.serverUrl + path, {
      method,
      headers: { 'content-type': 'application/json', ...(this.token ? { authorization: `Bearer ${this.token}` } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      signal: this.abort?.signal,
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok) throw Object.assign(new Error(json.error || `HTTP ${res.status}`), { status: res.status });
    return json;
  }

  async register() {
    let r;
    try {
      r = await this.api('POST', '/v1/register', { name: this.name, pub: this.keys.pub.toString('base64') });
    } catch (e) {
      if (e.status !== 409) throw e;
      // Our key is known but our token is not valid (e.g. state file lost): start with a fresh identity.
      this.keys = genKeyPair();
      this.token = null;
      r = await this.api('POST', '/v1/register', { name: this.name, pub: this.keys.pub.toString('base64') });
    }
    if (r.token) this.token = r.token;
    this.id = r.id;
    this.ip = r.ip;
    this.udpPort = r.udpPort;
    this.udpKey = udpKeyFor(this.token);
    this.saveState();
  }

  // ---------- lifecycle ----------

  async start() {
    this.loadState();
    this.abort = new AbortController();
    this.running = true;
    await this.register();
    const host = new URL(this.serverUrl).hostname;
    this.serverAddr = (await dns.lookup(host, { family: 4 })).address;
    this.relays.set(`${this.serverAddr}:${this.udpPort}`, {
      name: 'coordinator', region: '', addr: this.serverAddr, port: this.udpPort, key: this.udpKey, builtin: true,
      rtt: null, sentAt: 0, lastAck: 0,
    });

    this.udp = dgram.createSocket('udp4');
    this.udp.on('message', (m, r) => { try { this.onUdp(m, r); } catch (e) { this.log('udp error', e); } });
    this.udp.on('error', (e) => this.log('udp socket error', e.message));
    await new Promise((r) => this.udp.bind(this.port, r));
    this.port = this.udp.address().port;

    if (this.tun) {
      await this.tun.start({ ip: this.ip, prefix: 16 });
      this.tun.on('packet', (p) => this.fromTun(p));
    }
    await this.reportEndpoints();
    this.startedAt = Date.now();
    this.sendReg();
    this.regTimer = setInterval(() => this.sendReg(), REG_MS);
    this.queryStun();
    this.stunTimer = setInterval(() => this.queryStun(), STUN_MS);
    this.tickTimer = setInterval(() => this.tick(), 500);
    this.pollLoop();
    this.log(`up: #${this.id} ${this.ip} udp :${this.port}`);
    return { id: this.id, ip: this.ip };
  }

  async stop() {
    this.running = false;
    this.abort?.abort();
    clearInterval(this.regTimer);
    clearInterval(this.tickTimer);
    clearInterval(this.stunTimer);
    this.ws?.close();
    await this.tun?.stop?.();
    this.udp?.close();
  }

  reportEndpoints() {
    return this.api('POST', '/v1/endpoints', { local: this.localEndpoints(), public: this.stunEndpoint });
  }

  async queryStun() {
    for (const s of this.stunServers) {
      const m = /^(.+):(\d+)$/.exec(s);
      if (!m) continue;
      try {
        const addr = (await dns.lookup(m[1], { family: 4 })).address;
        this.stunAddrs.set(`${addr}:${m[2]}`, true);
        this.sendRaw(stunRequest(), Number(m[2]), addr);
      } catch { /* offline or DNS failure: try the next one */ }
    }
  }

  onStun(msg) {
    const r = parseStunResponse(msg);
    if (!r) return;
    const ep = `${r.addr}:${r.port}`;
    if (ep === this.stunEndpoint) return;
    this.stunEndpoint = ep;
    this.publicEndpoint ||= ep;
    this.reportEndpoints().catch(() => {});
    this.emit('state', this.status());
  }

  // ---------- Relay/TCP (WebSocket) ----------

  udpRelayOk() {
    return [...this.relays.values()].some((r) => Date.now() - r.lastAck < 45_000 && r.rtt != null);
  }

  connectWs() {
    if (this.ws || !this.running) return;
    this.lastWsTry = Date.now();
    const url = `${this.serverUrl.replace(/^http/, 'ws')}/v1/ws?token=${this.token}`;
    const ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    ws.onopen = () => { this.wsOpen = true; this.log('relay/tcp connected'); this.emit('state', this.status()); };
    ws.onmessage = (ev) => {
      const m = Buffer.from(ev.data);
      if (m[0] === T.RELAY && m.length > 9 && m.readUInt32BE(5) === this.id) {
        try { this.onPeerPacket(m.subarray(9), { relay: true, src: m.readUInt32BE(1) }); } catch (e) { this.log('ws packet error', e); }
      }
    };
    const closed = () => { if (this.ws === ws) { this.ws = null; this.wsOpen = false; } };
    ws.onclose = closed;
    ws.onerror = closed;
  }

  localEndpoints() {
    if (this.localEndpointsOverride) return this.localEndpointsOverride.map((h) => `${h}:${this.port}`);
    const out = [];
    for (const list of Object.values(os.networkInterfaces())) {
      for (const a of list || []) {
        if (a.family !== 'IPv4' || a.internal) continue;
        if (inSubnet(ipToInt(a.address))) continue; // never tunnel the tunnel
        out.push(`${a.address}:${this.port}`);
      }
    }
    return out.slice(0, 6);
  }

  async pollLoop() {
    while (this.running) {
      try {
        const st = await this.api('GET', `/v1/state?since=${this.version}`);
        this.applyState(st);
      } catch (e) {
        if (!this.running) return;
        this.log('state poll failed:', e.message);
        await new Promise((r) => setTimeout(r, 2000));
      }
    }
  }

  applyState(st) {
    this.version = st.version;
    this.networks = st.networks;
    const seen = new Set();
    for (const p of st.peers) {
      seen.add(p.id);
      let peer = this.peers.get(p.id);
      const pub = Buffer.from(p.pub, 'base64');
      if (!peer || !peer.pub.equals(pub)) {
        if (peer) this.byIp.delete(peer.ipInt);
        peer = {
          id: p.id, pub, ...peerStatic(this.keys.priv, pub),
          cur: null, prev: null, pending: null, lastInitTs: 0n,
          path: null, lastRx: 0, lastTx: 0, lastDirectRx: 0, lastProbe: 0, lastPunch: 0,
          rtt: null, rxBytes: 0, txBytes: 0,
        };
        this.peers.set(p.id, peer);
      }
      Object.assign(peer, { name: p.name, ip: p.ip, ipInt: ipToInt(p.ip), online: p.online, endpoints: p.endpoints.map(parseEp).filter(Boolean) });
      this.byIp.set(peer.ipInt, peer);
    }
    for (const [id, peer] of this.peers) {
      if (!seen.has(id)) { this.peers.delete(id); this.byIp.delete(peer.ipInt); }
    }
    this.recomputeFirewall();
    this.syncRelays(st.relays || [], st.relayKey);
    this.emit('state', this.status());
  }

  async syncRelays(list, relayKey) {
    const want = new Set();
    for (const r of list) {
      const m = /^(.+):(\d+)$/.exec(r.endpoint);
      if (!m || !relayKey) continue;
      let addr;
      try { addr = (await dns.lookup(m[1], { family: 4 })).address; } catch { continue; }
      const k = `${addr}:${m[2]}`;
      want.add(k);
      if (!this.relays.has(k)) {
        this.relays.set(k, { name: r.name, region: r.region, addr, port: Number(m[2]), key: relayKey, rtt: null, sentAt: 0, lastAck: 0 });
        this.sendRegTo(this.relays.get(k));
      }
    }
    for (const [k, r] of this.relays) if (!r.builtin && !want.has(k)) this.relays.delete(k);
  }

  // Lowest-RTT relay that answered recently; the coordinator's built-in relay is the fallback.
  bestRelay() {
    const fresh = (r) => Date.now() - r.lastAck < 45_000 && r.rtt != null;
    if (this.relayPrefer) {
      const pinned = [...this.relays.values()].find((r) => r.name === this.relayPrefer && fresh(r));
      if (pinned) return pinned;
    }
    let best = null;
    for (const r of this.relays.values()) {
      if (Date.now() - r.lastAck > 45_000 || r.rtt == null) continue;
      if (!best || r.rtt < best.rtt) best = r;
    }
    return best || this.relays.get(`${this.serverAddr}:${this.udpPort}`);
  }

  // Strangers (only public rooms in common) get a port allow-list from their games.
  recomputeFirewall() {
    for (const peer of this.peers.values()) {
      const shared = this.networks.filter((n) => n.members.includes(peer.id));
      if (shared.some((n) => !n.public)) { peer.fw = null; continue; }
      const udp = [];
      const tcp = [];
      for (const n of shared) {
        const g = GAMES[n.game] || {};
        udp.push(...parsePorts(g.udp));
        tcp.push(...parsePorts(g.tcp));
      }
      peer.fw = { udp, tcp };
    }
  }

  allowedInbound(peer, pkt, ip) {
    if (!peer.fw) return true;
    if (ip.proto === 1) return true; // ICMP
    if (ip.proto !== 6 && ip.proto !== 17) return false;
    if (pkt.length < ip.ihl + 4) return false;
    const sport = pkt.readUInt16BE(ip.ihl);
    const dport = pkt.readUInt16BE(ip.ihl + 2);
    const ranges = ip.proto === 17 ? peer.fw.udp : peer.fw.tcp;
    return portIn(ranges, dport) || portIn(ranges, sport);
  }

  // ---------- network API ----------

  createNetwork(name, password, opts = {}) { return this.api('POST', '/v1/networks', { name, password, ...opts }); }
  joinNetwork(name, password) { return this.api('POST', '/v1/networks/join', { name, password }); }
  leaveNetwork(name) { return this.api('POST', '/v1/networks/leave', { name }); }
  publicRooms(game) { return this.api('GET', `/v1/public${game ? `?game=${encodeURIComponent(game)}` : ''}`); }

  status() {
    return {
      self: { id: this.id, name: this.name, ip: this.ip, port: this.port, publicEndpoint: this.publicEndpoint },
      relays: [...this.relays.values()].map((r) => ({
        name: r.name, region: r.region, endpoint: `${r.addr}:${r.port}`, rtt: r.rtt,
        online: Date.now() - r.lastAck < 45_000, selected: this.udpRelayOk() && r === this.bestRelay(),
      })).concat(this.ws ? [{
        name: 'Relay/TCP', region: 'websocket', endpoint: new URL(this.serverUrl).host, rtt: null,
        online: this.wsOpen, selected: this.wsOpen && !this.udpRelayOk(),
      }] : []),
      networks: this.networks,
      peers: [...this.peers.values()].map((p) => ({
        id: p.id, name: p.name, ip: p.ip, online: p.online,
        trusted: !p.fw,
        path: !p.cur ? 'none' : p.path?.relay ? 'relay' : p.path ? `direct ${p.path.addr}:${p.path.port}` : 'none',
        rtt: p.rtt, rxBytes: p.rxBytes, txBytes: p.txBytes,
      })),
    };
  }

  ping(peerId, timeoutMs = 3000) {
    const peer = this.peers.get(peerId);
    if (!peer?.cur) return Promise.reject(new Error('no session'));
    const ts = Date.now() * 1000 + Math.floor(Math.random() * 1000);
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pendingPings.delete(ts); reject(new Error('timeout')); }, timeoutMs);
      this.pendingPings.set(ts, (rtt) => { clearTimeout(t); resolve(rtt); });
      this.sendData(peer, encodeCtrl(CTRL.PING, ts));
    });
  }

  // ---------- UDP ----------

  sendRaw(buf, port, addr) { this.udp.send(buf, port, addr); }

  sendRegTo(r) {
    r.sentAt = performance.now();
    this.sendRaw(encodeUdpReg(this.id, r.key), r.port, r.addr);
  }

  sendReg() {
    if (!this.udp) return;
    for (const r of this.relays.values()) if (this.serverUdp || !r.builtin) this.sendRegTo(r);
  }

  sendVia(peer, pkt, path) {
    if (!path) return;
    if (path.relay) {
      const wrapped = encodeRelay(this.id, peer.id, pkt);
      if (!this.udpRelayOk() && this.wsOpen) { this.ws.send(wrapped); return; } // Relay/TCP
      const r = this.bestRelay();
      this.sendRaw(wrapped, r.port, r.addr);
    } else {
      this.sendRaw(pkt, path.port, path.addr);
    }
  }

  sendData(peer, payload, path = peer.path) {
    const s = peer.cur;
    if (!s || !path) return false;
    const pkt = encodeData(this.id, s.counter++, s.send, payload);
    this.sendVia(peer, pkt, path);
    peer.lastTx = Date.now();
    peer.txBytes += payload.length;
    return true;
  }

  onUdp(msg, rinfo) {
    if (!msg.length) return;
    if (isStun(msg) && this.stunAddrs.has(`${rinfo.address}:${rinfo.port}`)) { this.onStun(msg); return; }
    const relay = this.relays.get(`${rinfo.address}:${rinfo.port}`);
    if (relay) {
      if (msg[0] === T.UDP_REG_ACK && msg.length === 7) {
        relay.rtt = Math.round(performance.now() - relay.sentAt);
        relay.lastAck = Date.now();
        if (!relay.builtin) return;
        const ep = `${msg[1]}.${msg[2]}.${msg[3]}.${msg[4]}:${msg.readUInt16BE(5)}`;
        if (ep !== this.publicEndpoint) { this.publicEndpoint = ep; this.emit('state', this.status()); }
      } else if (msg[0] === T.RELAY && msg.length > 9 && msg.readUInt32BE(5) === this.id) {
        this.onPeerPacket(msg.subarray(9), { relay: true, src: msg.readUInt32BE(1) });
      }
      return;
    }
    this.onPeerPacket(msg, { relay: false, addr: rinfo.address, port: rinfo.port });
  }

  markRx(peer, via) {
    const now = Date.now();
    peer.lastRx = now;
    if (!via.relay) {
      peer.path = { relay: false, addr: via.addr, port: via.port };
      peer.lastDirectRx = now;
    } else if (!peer.path || (!peer.path.relay && now - peer.lastDirectRx > DIRECT_STALE_MS)) {
      peer.path = { relay: true };
    }
  }

  installSession(peer, send, recv) {
    peer.prev = peer.cur;
    peer.cur = { send, recv, counter: 0n, window: new ReplayWindow(), created: Date.now() };
  }

  onPeerPacket(b, via) {
    const type = b[0];
    if (type === T.PUNCH) return;

    if (type === T.HS_INIT || type === T.HS_RESP) {
      const hs = decodeHs(b);
      if (!hs || hs.receiver !== this.id) return;
      const peer = this.peers.get(hs.sender);
      if (!peer || (via.relay && via.src !== hs.sender) || !verifyHs(hs, peer.macKey)) return;

      if (type === T.HS_INIT) {
        if (peer.id > this.id || hs.ts <= peer.lastInitTs) return; // only lower id initiates; anti-replay
        peer.lastInitTs = hs.ts;
        const e = genKeyPair();
        const k = sessionKeys(peer.ss, x25519(e.priv, hs.eph), hs.eph, e.pub);
        this.installSession(peer, k.r2i, k.i2r);
        this.markRx(peer, via);
        this.sendVia(peer, encodeResp(this.id, peer.id, e.pub, hs.ts, peer.macKey), via.relay ? { relay: true } : via);
        this.emit('state', this.status());
      } else {
        const p = peer.pending;
        if (!p || BigInt(p.ts) !== hs.ts) return;
        const k = sessionKeys(peer.ss, x25519(p.e.priv, hs.eph), p.e.pub, hs.eph);
        this.installSession(peer, k.i2r, k.r2i);
        peer.pending = null;
        this.markRx(peer, via);
        this.emit('state', this.status());
      }
      return;
    }

    if (type === T.DATA) {
      const h = decodeDataHeader(b);
      if (!h) return;
      const peer = this.peers.get(h.sender);
      if (!peer || (via.relay && via.src !== h.sender)) return;
      let pt = null;
      for (const s of [peer.cur, peer.prev]) {
        if (!s || !s.window.check(h.counter)) continue;
        pt = open(s.recv, h.counter, h.aad, h.ct);
        if (pt) { s.window.accept(h.counter); break; }
      }
      if (!pt) return;
      this.markRx(peer, via);
      peer.rxBytes += pt.length;
      if (pt.length === 0) return;
      if (pt[0] === 0x00 && pt.length === 10) {
        const kind = pt[1];
        const ts = pt.readBigUInt64BE(2);
        if (kind === CTRL.PING) this.sendData(peer, encodeCtrl(CTRL.PONG, ts), via.relay ? { relay: true } : via);
        else if (kind === CTRL.PONG) {
          const cb = this.pendingPings.get(Number(ts));
          const rtt = Date.now() - Number(ts / 1000n);
          peer.rtt = rtt;
          if (cb) { this.pendingPings.delete(Number(ts)); cb(rtt); }
        }
        return;
      }
      const ip = parseIPv4(pt);
      if (!ip || ip.src !== peer.ipInt) return; // peers may only send from their own virtual IP
      if (!this.allowedInbound(peer, pt, ip)) return;
      this.tun?.write(pt);
    }
  }

  // ---------- outgoing traffic from the virtual adapter ----------

  fromTun(pkt) {
    const ip = parseIPv4(pkt);
    if (!ip) return;
    const d = ip.dst;
    if (d === LIMITED_BCAST || d === BROADCAST || (d >>> 28) === 0xe) {
      for (const peer of this.peers.values()) if (peer.cur) this.sendData(peer, pkt);
      return;
    }
    const peer = this.byIp.get(d);
    if (peer) this.sendData(peer, pkt);
  }

  // ---------- timers ----------

  tick() {
    const now = Date.now();
    // No relay answered over UDP for a few seconds (UDP blocked or server behind an HTTP tunnel): use Relay/TCP.
    if (!this.ws && !this.udpRelayOk() && now - (this.startedAt || now) > 2500 && now - this.lastWsTry > 3000) this.connectWs();
    for (const peer of this.peers.values()) {
      if (peer.cur && now - peer.lastRx > DEAD_MS) {
        peer.cur = peer.prev = null;
        peer.path = null;
        this.emit('state', this.status());
      }
      if (!peer.online) continue;
      const direct = this.noDirect ? [] : peer.endpoints;

      if (this.id < peer.id) {
        const needHs = !peer.cur || now - peer.cur.created > REKEY_MS;
        if (needHs && (!peer.pending || now - peer.pending.sentAt >= 1000)) {
          if (!peer.pending || now - peer.pending.startedAt > 10_000) {
            const ts = Math.max(now, (peer.lastSentTs || 0) + 1);
            peer.lastSentTs = ts;
            peer.pending = { e: genKeyPair(), ts, attempts: 0, startedAt: now, sentAt: 0 };
          }
          const p = peer.pending;
          p.attempts++;
          p.sentAt = now;
          const pkt = encodeInit(this.id, peer.id, p.e.pub, p.ts, peer.macKey);
          if (peer.cur && peer.path) {
            this.sendVia(peer, pkt, peer.path); // rekey on the working path
          } else {
            for (const ep of direct) this.sendVia(peer, pkt, ep);
            if (p.attempts >= 3 || this.noDirect) this.sendVia(peer, pkt, { relay: true });
          }
        }
      } else if (!peer.cur && now - peer.lastPunch >= 1000) {
        peer.lastPunch = now;
        for (const ep of direct) this.sendVia(peer, encodePunch(this.id), ep);
      }

      if (peer.cur && peer.path && now - peer.lastTx > KEEPALIVE_MS) {
        this.sendData(peer, encodeCtrl(CTRL.PING, now * 1000));
      }
      // On relay: keep trying direct paths so we upgrade once hole-punching works.
      if (peer.cur && peer.path?.relay && direct.length && now - peer.lastProbe > PROBE_MS) {
        peer.lastProbe = now;
        for (const ep of direct) this.sendData(peer, encodeCtrl(CTRL.PING, now * 1000), ep);
      }
    }
  }
}

function parseEp(s) {
  const m = /^(\d+\.\d+\.\d+\.\d+):(\d+)$/.exec(s);
  return m ? { relay: false, addr: m[1], port: Number(m[2]) } : null;
}

export { intToIp };
