// Coordinator + standalone relay node: clients that cannot go direct are carried by the relay network.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../server/server.js';
import { createRelay } from '../server/relay.js';
import { Engine } from '../client/engine.js';
import { VirtualTun } from '../client/tun/virtual.js';

const cleanup = [];
after(async () => { for (const f of cleanup.reverse()) await f(); });

const waitFor = async (fn, ms = 10000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await fn()) return; await new Promise((r) => setTimeout(r, 50)); }
  throw new Error('waitFor timed out');
};

test('traffic flows through a standalone relay node', async () => {
  const secret = 'test-relay-secret';
  const coord = createServer({ httpPort: 0, udpPort: 0, host: '127.0.0.1', relaySecret: secret, log: () => {} });
  const { httpPort } = await coord.listen();
  cleanup.push(() => coord.close());
  const base = `http://127.0.0.1:${httpPort}`;

  const relay = createRelay({ coord: base, secret, name: 'eu-1', region: 'eu', udpPort: 0, host: '127.0.0.1', log: () => {} });
  await relay.listen();
  cleanup.push(() => relay.close());

  const mk = async (name) => {
    const tun = new VirtualTun();
    const e = new Engine({ serverUrl: base, name, tun, noDirect: true, relayPrefer: 'eu-1' });
    await e.start();
    cleanup.push(() => e.stop());
    return { e, tun };
  };
  const a = await mk('ann');
  const b = await mk('ben');
  await a.e.createNetwork('relaynet', 'secret123');
  await b.e.joinNetwork('relaynet', 'secret123');

  const usingEu = (e) => e.status().relays.some((r) => r.name === 'eu-1' && r.online && r.selected);
  await waitFor(() => usingEu(a.e) && usingEu(b.e));
  await waitFor(() => a.e.status().peers[0]?.path === 'relay' && b.e.status().peers[0]?.path === 'relay');
  await a.tun.ping(b.e.ip, 1);
  await b.tun.ping(a.e.ip, 2);

  // Wrong secret cannot join the relay network.
  const res = await fetch(`${base}/v1/relay/pairs`, { headers: { 'x-relay-secret': 'nope-nope-nope-nope' } });
  assert.equal(res.status, 401);
});
