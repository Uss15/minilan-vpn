// End-to-end: real server + two clients over localhost UDP, virtual adapters.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../server/server.js';
import { Engine } from '../client/engine.js';
import { VirtualTun } from '../client/tun/virtual.js';
import { ReplayWindow } from '../shared/proto.js';

let server;
let base;
const engines = [];

const waitFor = async (fn, ms = 8000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await fn()) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error('waitFor timed out');
};

async function client(name, opts = {}) {
  const tun = new VirtualTun();
  const e = new Engine({ serverUrl: base, name, tun, localEndpoints: ['127.0.0.1'], ...opts });
  await e.start();
  engines.push(e);
  return { e, tun };
}

const peerOf = (e, other) => e.status().peers.find((p) => p.id === other.id);

before(async () => {
  server = createServer({ httpPort: 0, udpPort: 0, host: '127.0.0.1', log: () => {} });
  const { httpPort } = await server.listen();
  base = `http://127.0.0.1:${httpPort}`;
});

after(async () => {
  for (const e of engines) await e.stop();
  server.close();
});

test('replay window rejects duplicates and old counters', () => {
  const w = new ReplayWindow();
  for (const n of [0n, 1n, 5n, 3n]) { assert.ok(w.check(n)); w.accept(n); }
  assert.ok(!w.check(3n));
  assert.ok(!w.check(5n));
  assert.ok(w.check(4n));
  w.accept(200n);
  assert.ok(!w.check(100n));
});

test('private network: join, direct path, ping both ways', async () => {
  const a = await client('alice');
  const b = await client('bob');
  await a.e.createNetwork('home', 'secret123');
  await assert.rejects(b.e.joinNetwork('home', 'wrongpass'), /wrong password/);
  await b.e.joinNetwork('home', 'secret123');

  await waitFor(() => peerOf(a.e, b.e)?.path.startsWith('direct') && peerOf(b.e, a.e)?.path.startsWith('direct'));
  const rtt1 = await a.tun.ping(b.e.ip, 1);
  const rtt2 = await b.tun.ping(a.e.ip, 2);
  assert.ok(rtt1 < 1000 && rtt2 < 1000);
  assert.equal(peerOf(a.e, b.e).trusted, true);
  assert.ok(await a.e.ping(b.e.id) >= 0);
});

test('relay fallback when direct UDP is impossible', async () => {
  const c = await client('carol', { noDirect: true });
  const d = await client('dave', { noDirect: true });
  await c.e.createNetwork('relayed', 'secret123');
  await d.e.joinNetwork('relayed', 'secret123');
  await waitFor(() => peerOf(c.e, d.e)?.path === 'relay' && peerOf(d.e, c.e)?.path === 'relay');
  await c.tun.ping(d.e.ip, 7);
  await d.tun.ping(c.e.ip, 8);
});

test('public game room: LAN broadcast reaches peers, game firewall blocks other ports', async () => {
  const host = await client('host');
  const guest = await client('guest');
  await host.e.createNetwork('Generals 1v1', '', { public: true, game: 'generals' });
  const { rooms } = await guest.e.publicRooms('generals');
  assert.ok(rooms.some((r) => r.name === 'Generals 1v1'));
  await guest.e.joinNetwork('Generals 1v1');
  await waitFor(() => peerOf(host.e, guest.e)?.path.startsWith('direct') && peerOf(guest.e, host.e)?.path.startsWith('direct'));
  assert.equal(peerOf(host.e, guest.e).trusted, false);

  const got = [];
  host.tun.on('udp', (u) => got.push(u));
  guest.tun.sendUdp('255.255.255.255', 8086, 8086, Buffer.from('generals lobby'));
  guest.tun.sendUdp(host.e.ip, 50000, 445, Buffer.from('smb probe'));
  guest.tun.sendUdp(host.e.ip, 50001, 8088, Buffer.from('game data'));
  await waitFor(() => got.length >= 2);
  await new Promise((r) => setTimeout(r, 200));
  assert.deepEqual(got.map((u) => u.dport).sort(), [8086, 8088]);
  assert.equal(got.find((u) => u.dport === 8086).dst, '255.255.255.255');
});
