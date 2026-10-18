// MiniLAN v1 wire format and crypto. See PROTOCOL.md.
import crypto from 'node:crypto';

export const T = {
  UDP_REG: 0x01, UDP_REG_ACK: 0x02, RELAY: 0x03,
  HS_INIT: 0x10, HS_RESP: 0x11, DATA: 0x12, PUNCH: 0x14,
};
export const CTRL = { PING: 1, PONG: 2 };

const PKCS8_PREFIX = Buffer.from('302e020100300506032b656e04220420', 'hex');
const SPKI_PREFIX = Buffer.from('302a300506032b656e032100', 'hex');

const privObj = (raw) => crypto.createPrivateKey({ key: Buffer.concat([PKCS8_PREFIX, raw]), format: 'der', type: 'pkcs8' });
const pubObj = (raw) => crypto.createPublicKey({ key: Buffer.concat([SPKI_PREFIX, raw]), format: 'der', type: 'spki' });

export function keyPairFromPriv(priv) {
  const pub = crypto.createPublicKey(privObj(priv)).export({ type: 'spki', format: 'der' }).subarray(SPKI_PREFIX.length);
  return { priv: Buffer.from(priv), pub: Buffer.from(pub) };
}

export function genKeyPair() {
  return keyPairFromPriv(crypto.randomBytes(32));
}

export function x25519(priv, pub) {
  const out = crypto.diffieHellman({ privateKey: privObj(priv), publicKey: pubObj(pub) });
  if (out.every((b) => b === 0)) throw new Error('x25519: low-order point');
  return out;
}

export const hkdf = (ikm, salt, info, len) => Buffer.from(crypto.hkdfSync('sha256', ikm, salt, Buffer.from(info), len));
export const mac16 = (key, data) => crypto.createHmac('sha256', key).update(data).digest().subarray(0, 16);

function nonce(counter) {
  const n = Buffer.alloc(12);
  n.writeBigUInt64BE(counter, 4);
  return n;
}

export function seal(key, counter, aad, plaintext) {
  const c = crypto.createCipheriv('chacha20-poly1305', key, nonce(counter), { authTagLength: 16 });
  c.setAAD(aad, { plaintextLength: plaintext.length });
  return Buffer.concat([c.update(plaintext), c.final(), c.getAuthTag()]);
}

export function open(key, counter, aad, ciphertext) {
  if (ciphertext.length < 16) return null;
  const d = crypto.createDecipheriv('chacha20-poly1305', key, nonce(counter), { authTagLength: 16 });
  d.setAAD(aad, { plaintextLength: ciphertext.length - 16 });
  d.setAuthTag(ciphertext.subarray(ciphertext.length - 16));
  try {
    return Buffer.concat([d.update(ciphertext.subarray(0, ciphertext.length - 16)), d.final()]);
  } catch {
    return null;
  }
}

// ---- static peer material ----

export function peerStatic(selfPriv, peerPub) {
  const ss = x25519(selfPriv, peerPub);
  return { ss, macKey: hkdf(ss, Buffer.alloc(0), 'minilan/v1/hs-mac', 32) };
}

export function sessionKeys(ss, ee, eInit, eResp) {
  const okm = hkdf(Buffer.concat([ss, ee]), Buffer.concat([eInit, eResp]), 'minilan/v1/session', 64);
  return { i2r: okm.subarray(0, 32), r2i: okm.subarray(32, 64) };
}

// ---- packets ----

function hsPacket(type, sender, receiver, eph, ts, macKey) {
  const b = Buffer.alloc(65);
  b[0] = type;
  b.writeUInt32BE(sender, 1);
  b.writeUInt32BE(receiver, 5);
  eph.copy(b, 9);
  b.writeBigUInt64BE(BigInt(ts), 41);
  mac16(macKey, b.subarray(0, 49)).copy(b, 49);
  return b;
}

export const encodeInit = (sender, receiver, eph, ts, macKey) => hsPacket(T.HS_INIT, sender, receiver, eph, ts, macKey);
export const encodeResp = (sender, receiver, eph, initTs, macKey) => hsPacket(T.HS_RESP, sender, receiver, eph, initTs, macKey);

export function decodeHs(b) {
  if (b.length !== 65) return null;
  return {
    type: b[0], sender: b.readUInt32BE(1), receiver: b.readUInt32BE(5),
    eph: Buffer.from(b.subarray(9, 41)), ts: b.readBigUInt64BE(41),
    body: b.subarray(0, 49), mac: b.subarray(49, 65),
  };
}

export const verifyHs = (hs, macKey) => crypto.timingSafeEqual(mac16(macKey, hs.body), hs.mac);

export function encodeData(sender, counter, key, payload) {
  const h = Buffer.alloc(13);
  h[0] = T.DATA;
  h.writeUInt32BE(sender, 1);
  h.writeBigUInt64BE(counter, 5);
  return Buffer.concat([h, seal(key, counter, h, payload)]);
}

export function decodeDataHeader(b) {
  if (b.length < 13 + 16) return null;
  return { sender: b.readUInt32BE(1), counter: b.readBigUInt64BE(5), aad: b.subarray(0, 13), ct: b.subarray(13) };
}

// udpKey = hex sha256 of the bearer token (the server stores only that digest).
export const udpKeyFor = (token) => crypto.createHash('sha256').update(token).digest('hex');

export function encodeUdpReg(nodeId, udpKey, ts = Date.now()) {
  const b = Buffer.alloc(29);
  b[0] = T.UDP_REG;
  b.writeUInt32BE(nodeId, 1);
  b.writeBigUInt64BE(BigInt(ts), 5);
  mac16(Buffer.from(udpKey, 'utf8'), b.subarray(0, 13)).copy(b, 13);
  return b;
}

export function encodeRelay(src, dst, inner) {
  const h = Buffer.alloc(9);
  h[0] = T.RELAY;
  h.writeUInt32BE(src, 1);
  h.writeUInt32BE(dst, 5);
  return Buffer.concat([h, inner]);
}

export function encodePunch(sender) {
  const b = Buffer.alloc(5);
  b[0] = T.PUNCH;
  b.writeUInt32BE(sender, 1);
  return b;
}

export function encodeCtrl(kind, ts) {
  const b = Buffer.alloc(10);
  b[1] = kind;
  b.writeBigUInt64BE(BigInt(ts), 2);
  return b;
}

// 64-entry sliding replay window.
export class ReplayWindow {
  constructor() { this.max = -1n; this.bits = 0n; }
  check(n) {
    if (n > this.max) return true;
    const d = this.max - n;
    return d < 64n && ((this.bits >> d) & 1n) === 0n;
  }
  accept(n) {
    if (n > this.max) {
      const shift = this.max < 0n ? 64n : n - this.max;
      this.bits = shift >= 64n ? 1n : ((this.bits << shift) | 1n) & ((1n << 64n) - 1n);
      this.max = n;
    } else {
      this.bits |= 1n << (this.max - n);
    }
  }
}

// ---- addressing ----

export function ipForIndex(n) {
  let idx = 0;
  for (let i = 1; ; i++) {
    const lo = i & 255;
    if (lo === 0 || lo === 255) continue;
    if (++idx === n) return `100.77.${(i >> 8) & 255}.${lo}`;
  }
}

export const ipToInt = (s) => s.split('.').reduce((a, o) => ((a << 8) | Number(o)) >>> 0, 0);
export const intToIp = (n) => [24, 16, 8, 0].map((s) => (n >>> s) & 255).join('.');
export const SUBNET = ipToInt('100.77.0.0');
export const BROADCAST = ipToInt('100.77.255.255');
export const inSubnet = (n) => ((n & 0xffff0000) >>> 0) === SUBNET;
