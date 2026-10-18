// "Host over the Internet without port forwarding": the server is reachable only over HTTP (as behind a
// Cloudflare quick tunnel), so clients must learn their address via STUN and relay over WebSocket.
// Uses a local STUN server; nothing leaves this machine.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import dgram from 'node:dgram';
import { createServer } from '../server/server.js';
import { Engine } from '../client/engine.js';
import { VirtualTun } from '../client/tun/virtual.js';
import { isStun, stunResponse } from '../shared/stun.js';

let server, base, stun, stunAddr;
const engines = [];

const waitFor = async (fn, ms = 10000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await fn()) return; await new Promise((r) => setTimeout(r, 50)); }
  throw new Error('waitFor timed out');
};
const peerOf = (e, other) => e.status().peers.find((p) => p.id === other.id);

async function client(name, opts = {}) {
  const tun = new VirtualTun();
  const e = new Engine({ serverUrl: base, name, tun, stunServers: [stunAddr], localEndpoints: [], ...opts });
  await e.start();
  engines.push(e);
  return { e, tun };
}

before(async () => {
  server = createServer({ httpPort: 0, udpPort: 0, host: '127.0.0.1', log: () => {} });
  ({ httpPort: server.port } = await server.listen());
  base = `http://127.0.0.1:${server.port}`;
  stun = dgram.createSocket('udp4');
  stun.on('message', (m, r) => { if (isStun(m)) stun.send(stunResponse(m, r.address, r.port), r.port, r.address); });
  await new Promise((r) => stun.bind(0, '127.0.0.1', r));
  stunAddr = `127.0.0.1:${stun.address().port}`;
});

after(async () => {
  for (const e of engines) await e.stop();
  server.close();
  stun.close();
});

test('server UDP blocked: STUN gives public endpoints and peers connect directly', async () => {
  const a = await client('ann', { serverUdp: false });
  const b = await client('bob', { serverUdp: false });
  await waitFor(() => a.e.stunEndpoint && b.e.stunEndpoint);
  await a.e.createNetwork('net1', 'secret123');
  await b.e.joinNetwork('net1', 'secret123');
  await waitFor(() => peerOf(a.e, b.e)?.online && peerOf(b.e, a.e)?.online); // presence without UDP to the server
  await waitFor(() => peerOf(a.e, b.e)?.path === `direct ${b.e.stunEndpoint}` && peerOf(b.e, a.e)?.path.startsWith('direct'));
  await a.tun.ping(b.e.ip, 1);
  await b.tun.ping(a.e.ip, 2);
});

test('server UDP blocked and no direct path: Relay/TCP over WebSocket carries the traffic', async () => {
  const c = await client('cat', { serverUdp: false, noDirect: true });
  const d = await client('dan', { serverUdp: false, noDirect: true });
  await c.e.createNetwork('net2', 'secret123');
  await d.e.joinNetwork('net2', 'secret123');
  const usingTcp = (e) => e.status().relays.some((r) => r.name === 'Relay/TCP' && r.online && r.selected);
  await waitFor(() => usingTcp(c.e) && usingTcp(d.e));
  await waitFor(() => peerOf(c.e, d.e)?.path === 'relay' && peerOf(d.e, c.e)?.path === 'relay');
  await c.tun.ping(d.e.ip, 3);
  await d.tun.ping(c.e.ip, 4);
});

test('mixed: a UDP-relay player and a WebSocket-relay player reach each other', async () => {
  const u = await client('udpguy', { noDirect: true });
  const w = await client('wsguy', { serverUdp: false, noDirect: true });
  await u.e.createNetwork('net3', 'secret123');
  await w.e.joinNetwork('net3', 'secret123');
  await waitFor(() => peerOf(u.e, w.e)?.path === 'relay' && peerOf(w.e, u.e)?.path === 'relay');
  assert.ok(u.e.udpRelayOk());
  assert.ok(!w.e.udpRelayOk() && w.e.wsOpen);
  await u.tun.ping(w.e.ip, 5);
  await w.tun.ping(u.e.ip, 6);
});
