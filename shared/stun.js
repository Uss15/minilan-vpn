// Minimal STUN (RFC 5389) Binding request/response, used to learn our public UDP address
// without needing UDP access to the MiniLAN server (e.g. when it is behind an HTTP-only tunnel).
import crypto from 'node:crypto';

export const STUN_COOKIE = 0x2112a442;
export const DEFAULT_STUN = ['stun.l.google.com:19302', 'stun.cloudflare.com:3478'];

export function stunRequest() {
  const b = Buffer.alloc(20);
  b.writeUInt16BE(0x0001, 0); // Binding Request
  b.writeUInt16BE(0, 2);
  b.writeUInt32BE(STUN_COOKIE, 4);
  crypto.randomBytes(12).copy(b, 8);
  return b;
}

export const isStun = (b) => b.length >= 20 && b.readUInt32BE(4) === STUN_COOKIE && (b[0] & 0xc0) === 0;

// Returns { txid, addr, port } from a Binding Success Response, or null.
export function parseStunResponse(b) {
  if (!isStun(b) || b.readUInt16BE(0) !== 0x0101) return null;
  const len = b.readUInt16BE(2);
  const txid = b.subarray(8, 20).toString('hex');
  let off = 20;
  let mapped = null;
  while (off + 4 <= 20 + len && off + 4 <= b.length) {
    const type = b.readUInt16BE(off);
    const alen = b.readUInt16BE(off + 2);
    const v = b.subarray(off + 4, off + 4 + alen);
    if ((type === 0x0020 || type === 0x8020) && v.length >= 8 && v[1] === 0x01) { // XOR-MAPPED-ADDRESS, IPv4
      const port = v.readUInt16BE(2) ^ (STUN_COOKIE >>> 16);
      const ip = (v.readUInt32BE(4) ^ STUN_COOKIE) >>> 0;
      return { txid, addr: [24, 16, 8, 0].map((s) => (ip >>> s) & 255).join('.'), port };
    }
    if (type === 0x0001 && v.length >= 8 && v[1] === 0x01) mapped = { txid, addr: [...v.subarray(4, 8)].join('.'), port: v.readUInt16BE(2) };
    off += 4 + alen + ((4 - (alen % 4)) % 4);
  }
  return mapped;
}

// Builds a Binding Success Response (used by tests' local STUN server).
export function stunResponse(req, addr, port) {
  const attr = Buffer.alloc(12);
  attr.writeUInt16BE(0x0020, 0);
  attr.writeUInt16BE(8, 2);
  attr[5] = 0x01;
  attr.writeUInt16BE(port ^ (STUN_COOKIE >>> 16), 6);
  const ip = addr.split('.').reduce((a, o) => ((a << 8) | Number(o)) >>> 0, 0);
  attr.writeUInt32BE((ip ^ STUN_COOKIE) >>> 0, 8);
  const h = Buffer.alloc(20);
  h.writeUInt16BE(0x0101, 0);
  h.writeUInt16BE(attr.length, 2);
  h.writeUInt32BE(STUN_COOKIE, 4);
  req.copy(h, 8, 8, 20);
  return Buffer.concat([h, attr]);
}
