// MiniLAN coordination server: HTTP control API + UDP endpoint discovery and relay.
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { ipForIndex } from '../shared/proto.js';
import { createUdpCore, relayKeyFor } from './udp.js';

const GAMES = JSON.parse(fs.readFileSync(new URL('../shared/games.json', import.meta.url), 'utf8'));

const MAX_MEMBERS = 250;
const RELAY_STALE_MS = 60_000;
const POLL_MS = 25_000;

const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const hashPw = (pw, salt) => crypto.scryptSync(pw, salt, 32).toString('hex');
const netKey = (name) => String(name).trim().toLowerCase();

export function createServer({
  httpPort = 8080, udpPort = 8081, dataFile = null, host = '0.0.0.0', log = console.log,
  relaySecret = null, // set to let standalone relay nodes join this coordinator
} = {}) {
  let db = { nextId: 1, nodes: {}, networks: {} };
  if (dataFile && fs.existsSync(dataFile)) db = JSON.parse(fs.readFileSync(dataFile, 'utf8'));
  const save = () => { if (dataFile) fs.writeFileSync(dataFile, JSON.stringify(db, null, 1)); };

  // runtime-only state
  const localEps = new Map(); // id -> ["192.168.1.5:41000", ...]
  const relays = new Map(); // name -> { name, region, endpoint, lastSeen }
  let version = 1;
  const waiters = new Set();
  const bump = () => {
    version++;
    for (const w of waiters) w();
    waiters.clear();
  };

  const sharesNetwork = (a, b) => Object.values(db.networks).some((n) => n.members[a] && n.members[b]);

  const udp = createUdpCore({
    regKey: (id) => db.nodes[id]?.tokenHash ?? null, // clients key UDP_REG with hex sha256(token)
    canRelay: (a, b) => sharesNetwork(a, b),
    onChange: () => bump(),
  });
  const live = udp.live;
  const isOnline = udp.isOnline;

  const liveRelays = () => [...relays.values()].filter((r) => Date.now() - r.lastSeen < RELAY_STALE_MS);

  function view(id) {
    const self = db.nodes[id];
    const l = live.get(id);
    const networks = [];
    const peerIds = new Set();
    for (const n of Object.values(db.networks)) {
      if (!n.members[id]) continue;
      const members = Object.keys(n.members).map(Number);
      networks.push({ name: n.name, role: n.members[id], members, public: !!n.public, game: n.game || null });
      members.forEach((m) => m !== id && peerIds.add(m));
    }
    const peers = [...peerIds].map((pid) => {
      const p = db.nodes[pid];
      const pl = live.get(pid);
      const pubEp = pl ? `${pl.addr}:${pl.port}` : null;
      const endpoints = [...(pubEp ? [pubEp] : []), ...(localEps.get(pid) || []).filter((e) => e !== pubEp)];
      return { id: p.id, name: p.name, ip: p.ip, pub: p.pub, online: isOnline(pid), endpoints };
    });
    return {
      version,
      self: { id, ip: self.ip, name: self.name, publicEndpoint: l ? `${l.addr}:${l.port}` : null },
      networks, peers,
      relays: liveRelays().map((r) => ({ name: r.name, region: r.region, endpoint: r.endpoint })),
      relayKey: relaySecret ? relayKeyFor(relaySecret, id) : null,
    };
  }

  function auth(req) {
    const m = /^Bearer (\w+)$/.exec(req.headers.authorization || '');
    if (!m) return null;
    const h = sha256(m[1]);
    return Object.values(db.nodes).find((n) => n.tokenHash === h) || null;
  }

  const routes = {
    'POST /v1/register': (body, req) => {
      const name = String(body.name || '').slice(0, 32).trim();
      const pub = Buffer.from(String(body.pub || ''), 'base64');
      if (!name || pub.length !== 32) return [400, { error: 'name and 32-byte pub required' }];
      const pubB64 = pub.toString('base64');
      const existing = Object.values(db.nodes).find((n) => n.pub === pubB64);
      if (existing) {
        const me = auth(req);
        if (!me || me.id !== existing.id) return [409, { error: 'key already registered; send its token' }];
        existing.name = name;
        save(); bump();
        return [200, { id: existing.id, ip: existing.ip, udpPort }];
      }
      const id = db.nextId++;
      const token = crypto.randomBytes(24).toString('hex');
      db.nodes[id] = { id, name, pub: pubB64, ip: ipForIndex(id), tokenHash: sha256(token) };
      save();
      log(`register #${id} ${name} ${db.nodes[id].ip}`);
      return [200, { id, ip: db.nodes[id].ip, token, udpPort }];
    },
    'POST /v1/endpoints': (body, req, me) => {
      const local = (Array.isArray(body.local) ? body.local : [])
        .filter((e) => /^\d{1,3}(\.\d{1,3}){3}:\d{1,5}$/.test(e)).slice(0, 8);
      localEps.set(me.id, local);
      bump();
      return [200, { ok: true }];
    },
    'POST /v1/networks': (body, req, me) => {
      const name = String(body.name || '').trim().slice(0, 64);
      const pw = String(body.password || '');
      const isPublic = !!body.public;
      const game = isPublic ? String(body.game || '') : null;
      if (!name) return [400, { error: 'name required' }];
      if (isPublic && (!GAMES[game] || game.startsWith('_'))) return [400, { error: 'public rooms need a known game id' }];
      if (!isPublic && pw.length < 6) return [400, { error: 'password (6+ chars) required' }];
      if (db.networks[netKey(name)]) return [409, { error: 'network name taken' }];
      const salt = crypto.randomBytes(16).toString('hex');
      db.networks[netKey(name)] = {
        name, salt, pwHash: pw ? hashPw(pw, salt) : null, public: isPublic, game,
        members: { [me.id]: 'admin' },
      };
      save(); bump();
      log(`#${me.id} created network '${name}'`);
      return [200, { ok: true }];
    },
    'POST /v1/networks/join': (body, req, me) => {
      const n = db.networks[netKey(body.name || '')];
      if (!n) return [404, { error: 'no such network' }];
      if (n.pwHash) {
        const got = Buffer.from(hashPw(String(body.password || ''), n.salt), 'hex');
        if (!crypto.timingSafeEqual(got, Buffer.from(n.pwHash, 'hex'))) return [403, { error: 'wrong password' }];
      } else if (!n.public) {
        return [403, { error: 'wrong password' }];
      }
      if (!n.members[me.id]) {
        if (Object.keys(n.members).length >= MAX_MEMBERS) return [403, { error: 'network full' }];
        n.members[me.id] = 'member';
        save(); bump();
        log(`#${me.id} joined '${n.name}'`);
      }
      return [200, { ok: true }];
    },
    'POST /v1/networks/leave': (body, req, me) => {
      const key = netKey(body.name || '');
      const n = db.networks[key];
      if (!n || !n.members[me.id]) return [404, { error: 'not a member' }];
      delete n.members[me.id];
      const admins = Object.values(n.members).filter((r) => r === 'admin').length;
      if (!admins) {
        const next = Object.keys(n.members)[0];
        if (next) n.members[next] = 'admin'; else delete db.networks[key];
      }
      save(); bump();
      return [200, { ok: true }];
    },
  };

  const httpServer = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    const send = (code, obj) => {
      res.writeHead(code, { 'content-type': 'application/json' });
      res.end(JSON.stringify(obj));
    };
    try {
      if (req.method === 'GET' && url.pathname === '/v1/state') {
        const me = auth(req);
        if (!me) return send(401, { error: 'unauthorized' });
        const since = Number(url.searchParams.get('since') || 0);
        if (version > since) return send(200, view(me.id));
        const done = () => { clearTimeout(timer); waiters.delete(done); if (!res.writableEnded) send(200, view(me.id)); };
        const timer = setTimeout(done, POLL_MS);
        waiters.add(done);
        req.on('close', () => { clearTimeout(timer); waiters.delete(done); });
        return;
      }
      if (req.method === 'GET' && url.pathname === '/v1/public') {
        if (!auth(req)) return send(401, { error: 'unauthorized' });
        const game = url.searchParams.get('game');
        const rooms = Object.values(db.networks)
          .filter((n) => n.public && (!game || n.game === game))
          .map((n) => ({
            name: n.name, game: n.game, locked: !!n.pwHash,
            members: Object.keys(n.members).length,
            online: Object.keys(n.members).filter((m) => isOnline(Number(m))).length,
          }))
          .sort((a, b) => b.online - a.online)
          .slice(0, 200);
        return send(200, { rooms });
      }
      if (url.pathname.startsWith('/v1/relay/')) {
        const given = String(req.headers['x-relay-secret'] || '');
        if (!relaySecret || given.length !== relaySecret.length
          || !crypto.timingSafeEqual(Buffer.from(given), Buffer.from(relaySecret))) return send(401, { error: 'bad relay secret' });
        if (req.method === 'GET' && url.pathname === '/v1/relay/pairs') {
          // adjacency: node id -> ids it may relay to
          const pairs = {};
          for (const n of Object.values(db.networks)) {
            const ids = Object.keys(n.members);
            for (const a of ids) for (const b of ids) if (a !== b) (pairs[a] ||= new Set()).add(Number(b));
          }
          return send(200, { version, pairs: Object.fromEntries(Object.entries(pairs).map(([k, v]) => [k, [...v]])) });
        }
        if (req.method === 'POST' && url.pathname === '/v1/relay/announce') {
          let raw = '';
          for await (const chunk of req) raw += chunk;
          const b = JSON.parse(raw || '{}');
          if (!/^[\w.-]{1,40}$/.test(b.name || '') || !/^[\w.-]+:\d{1,5}$/.test(b.endpoint || '')) return send(400, { error: 'name and host:port endpoint required' });
          const isNew = !relays.has(b.name) || relays.get(b.name).endpoint !== b.endpoint;
          relays.set(b.name, { name: b.name, region: String(b.region || '').slice(0, 20), endpoint: b.endpoint, lastSeen: Date.now() });
          if (isNew) { log(`relay '${b.name}' ${b.endpoint} joined`); bump(); }
          return send(200, { ok: true });
        }
        return send(404, { error: 'not found' });
      }
      if (req.method === 'GET' && url.pathname === '/v1/games') return send(200, GAMES);
      if (req.method === 'GET' && url.pathname === '/healthz') return send(200, { ok: true, version });
      const handler = routes[`${req.method} ${url.pathname}`];
      if (!handler) return send(404, { error: 'not found' });
      let raw = '';
      for await (const chunk of req) {
        raw += chunk;
        if (raw.length > 65536) return send(413, { error: 'too large' });
      }
      const body = raw ? JSON.parse(raw) : {};
      let me = null;
      if (url.pathname !== '/v1/register') {
        me = auth(req);
        if (!me) return send(401, { error: 'unauthorized' });
      }
      const [code, obj] = handler(body, req, me);
      send(code, obj);
    } catch (e) {
      send(400, { error: String(e.message || e) });
    }
  });

  // Online/offline transitions need a version bump so clients see them.
  let lastOnline = new Set();
  const sweeper = setInterval(() => {
    const now = new Set([...live.keys()].filter(isOnline));
    for (const [k, r] of relays) if (Date.now() - r.lastSeen > RELAY_STALE_MS) { relays.delete(k); bump(); }
    if (now.size !== lastOnline.size || [...now].some((id) => !lastOnline.has(id))) bump();
    lastOnline = now;
  }, 5_000);

  return {
    async listen() {
      await new Promise((r) => httpServer.listen(httpPort, host, r));
      udpPort = await udp.bind(udpPort, host);
      const a = httpServer.address();
      log(`minilan server http ${a.address}:${a.port} udp ${a.address}:${udpPort}${relaySecret ? ' (relay nodes enabled)' : ''}`);
      return { httpPort: a.port, udpPort };
    },
    close() {
      clearInterval(sweeper);
      for (const w of waiters) w();
      httpServer.closeAllConnections?.();
      httpServer.close();
      udp.close();
    },
  };
}

if (process.argv[1] && import.meta.filename === path.resolve(process.argv[1])) {
  const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : process.env[`MINILAN_${k.toUpperCase()}`] || d; };
  createServer({
    httpPort: Number(arg('http', 8080)),
    udpPort: Number(arg('udp', 8081)),
    dataFile: arg('data', 'minilan-data.json'),
    relaySecret: arg('relay-secret', null),
  }).listen();
}
