// UDP endpoint discovery + relay, shared by the coordinator's built-in relay and standalone relay nodes.
import dgram from 'node:dgram';
import crypto from 'node:crypto';
import { T, mac16 } from '../shared/proto.js';

export const ONLINE_MS = 45_000;

// Key a standalone relay derives for a node, so it can check UDP_REG without a database.
export const relayKeyFor = (secret, id) => crypto.createHmac('sha256', secret).update(`minilan-relay:${id}`).digest('hex');

// regKey(id) -> mac key string or null; canRelay(src, dst) -> bool; onChange() when a node's address/online state changes.
export function createUdpCore({ regKey, canRelay, onChange = () => {} }) {
  const live = new Map(); // id -> { addr, port, lastSeen, lastTs }
  const sock = dgram.createSocket('udp4');
  const isOnline = (id) => { const l = live.get(id); return !!l && Date.now() - l.lastSeen < ONLINE_MS; };

  sock.on('message', (msg, rinfo) => {
    if (msg.length < 1) return;
    if (msg[0] === T.UDP_REG && msg.length === 29) {
      const id = msg.readUInt32BE(1);
      const ts = msg.readBigUInt64BE(5);
      const key = regKey(id);
      if (!key) return;
      if (!crypto.timingSafeEqual(mac16(Buffer.from(key, 'utf8'), msg.subarray(0, 13)), msg.subarray(13, 29))) return;
      if (Math.abs(Number(ts) - Date.now()) > 300_000) return;
      const l = live.get(id) || { addr: null, port: null, lastSeen: 0, lastTs: 0n };
      if (ts <= l.lastTs) return;
      const changed = l.addr !== rinfo.address || l.port !== rinfo.port || !isOnline(id);
      Object.assign(l, { addr: rinfo.address, port: rinfo.port, lastSeen: Date.now(), lastTs: ts });
      live.set(id, l);
      const ack = Buffer.alloc(7);
      ack[0] = T.UDP_REG_ACK;
      rinfo.address.split('.').forEach((o, i) => { ack[1 + i] = Number(o); });
      ack.writeUInt16BE(rinfo.port, 5);
      sock.send(ack, rinfo.port, rinfo.address);
      if (changed) onChange(id);
      return;
    }
    if (msg[0] === T.RELAY && msg.length > 9) {
      const src = msg.readUInt32BE(1);
      const dst = msg.readUInt32BE(5);
      const ls = live.get(src);
      const ld = live.get(dst);
      if (!ls || ls.addr !== rinfo.address || ls.port !== rinfo.port) return; // anti-spoof
      if (!ld || !isOnline(dst) || !canRelay(src, dst)) return;
      sock.send(msg, ld.port, ld.addr);
    }
  });

  return {
    live, isOnline, sock,
    bind: (port, host) => new Promise((r) => sock.bind(port, host, () => r(sock.address().port))),
    close: () => sock.close(),
  };
}
