// Minimal IPv4/ICMP helpers used by routing and the virtual test adapter.
import { ipToInt, intToIp } from '../shared/proto.js';

export function checksum(buf) {
  let sum = 0;
  for (let i = 0; i + 1 < buf.length; i += 2) sum += buf.readUInt16BE(i);
  if (buf.length & 1) sum += buf[buf.length - 1] << 8;
  while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
  return ~sum & 0xffff;
}

export function parseIPv4(p) {
  if (p.length < 20 || p[0] >> 4 !== 4) return null;
  const ihl = (p[0] & 15) * 4;
  return { ihl, proto: p[9], src: p.readUInt32BE(12), dst: p.readUInt32BE(16), total: p.readUInt16BE(2) };
}

export function buildIPv4(src, dst, proto, payload, ttl = 64) {
  const h = Buffer.alloc(20);
  h[0] = 0x45;
  h.writeUInt16BE(20 + payload.length, 2);
  h.writeUInt16BE(Math.floor(Math.random() * 65536), 4);
  h[8] = ttl;
  h[9] = proto;
  h.writeUInt32BE(typeof src === 'string' ? ipToInt(src) : src, 12);
  h.writeUInt32BE(typeof dst === 'string' ? ipToInt(dst) : dst, 16);
  h.writeUInt16BE(checksum(h), 10);
  return Buffer.concat([h, payload]);
}

export function buildIcmpEcho(type, id, seq, data = Buffer.alloc(0)) {
  const b = Buffer.alloc(8 + data.length);
  b[0] = type; // 8 = request, 0 = reply
  b.writeUInt16BE(id, 4);
  b.writeUInt16BE(seq, 6);
  data.copy(b, 8);
  b.writeUInt16BE(checksum(b), 2);
  return b;
}

export { ipToInt, intToIp };
