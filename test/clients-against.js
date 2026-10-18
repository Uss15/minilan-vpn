// Runs two desktop (Node) clients against an existing server URL (argv[2]) and checks they can
// register, use networks and public rooms, connect, and ping each other. Used to test the Kotlin
// phone server (android InteropTest) against the real desktop client. Exits 0 on success.
import { Engine } from '../client/engine.js';
import { VirtualTun } from '../client/tun/virtual.js';

const url = process.argv[2];
const fail = (m) => { console.error('FAIL:', m); process.exit(1); };
const waitFor = async (fn, ms = 10000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await fn()) return; await new Promise((r) => setTimeout(r, 50)); }
  fail('timed out');
};

const mk = async (name) => {
  const tun = new VirtualTun();
  const e = new Engine({ serverUrl: url, name, tun, localEndpoints: ['127.0.0.1'], stunServers: [] });
  await e.start();
  return { e, tun };
};
const a = await mk('pc-a');
const b = await mk('pc-b');
await a.e.createNetwork('lan', 'secret123');
await b.e.joinNetwork('lan', 'wrongpass').then(() => fail('wrong password accepted'), (e) => /wrong password/.test(e.message) || fail(e.message));
await b.e.joinNetwork('lan', 'secret123');
await a.e.createNetwork('ZH room', '', { public: true, game: 'generals-zh' });
const { rooms } = await b.e.publicRooms('generals-zh');
if (!rooms.some((r) => r.name === 'ZH room')) fail('public room missing');

const direct = (e) => e.status().peers[0]?.path.startsWith('direct');
await waitFor(() => direct(a.e) && direct(b.e));
const rtt = await a.tun.ping(b.e.ip, 1);
await b.tun.ping(a.e.ip, 2);
console.log(`OK ${a.e.ip} <-> ${b.e.ip} ${rtt.toFixed(1)} ms`);
await a.e.stop();
await b.e.stop();
process.exit(0);
