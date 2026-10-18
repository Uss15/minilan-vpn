// Generates deterministic crypto/packet vectors from the Node implementation.
// The Android unit test (ProtoVectorsTest.kt) must reproduce them byte-for-byte.
import fs from 'node:fs';
import {
  keyPairFromPriv, x25519, peerStatic, sessionKeys, encodeInit, encodeResp, encodeData, encodeUdpReg, udpKeyFor, encodeRelay, encodeCtrl,
} from '../shared/proto.js';

export function makeVectors() {
  const h = (b) => Buffer.from(b).toString('hex');
  const fixed = (n) => Buffer.alloc(32, n);
  const A = keyPairFromPriv(fixed(0x11));
  const B = keyPairFromPriv(fixed(0x22));
  const eI = keyPairFromPriv(fixed(0x33));
  const eR = keyPairFromPriv(fixed(0x44));
  const st = peerStatic(A.priv, B.pub);
  const ee = x25519(eI.priv, eR.pub);
  const k = sessionKeys(st.ss, ee, eI.pub, eR.pub);
  const ts = 1760000000123;
  const payload = Buffer.from('4500001c00000000400100007f0000017f000001', 'hex');
  const token = 'abc123token';
  return {
    privA: h(A.priv), pubA: h(A.pub), privB: h(B.priv), pubB: h(B.pub),
    privEI: h(eI.priv), pubEI: h(eI.pub), privER: h(eR.priv), pubER: h(eR.pub),
    ss: h(st.ss), macKey: h(st.macKey), ee: h(ee), i2r: h(k.i2r), r2i: h(k.r2i),
    ts,
    init: h(encodeInit(1, 2, eI.pub, ts, st.macKey)),
    resp: h(encodeResp(2, 1, eR.pub, ts, st.macKey)),
    payload: h(payload),
    data: h(encodeData(1, 5n, k.i2r, payload)),
    token, udpKey: udpKeyFor(token),
    udpReg: h(encodeUdpReg(7, udpKeyFor(token), ts)),
    relay: h(encodeRelay(1, 2, Buffer.from('ff', 'hex'))),
    ctrl: h(encodeCtrl(1, ts)),
  };
}

if (process.argv[1]?.endsWith('make-vectors.js')) {
  const out = new URL('../android/app/src/test/resources/vectors.json', import.meta.url);
  fs.writeFileSync(out, JSON.stringify(makeVectors(), null, 1));
  console.log('wrote', out.pathname);
}
