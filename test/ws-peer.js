// Helper for the Android Relay/TCP test: a server whose UDP the clients won't use, a local STUN server,
// and a desktop peer that can only relay over WebSocket. Prints one JSON line; exits when stdin closes.
import dgram from 'node:dgram';
import { createServer } from '../server/server.js';
import { Engine } from '../client/engine.js';
import { VirtualTun } from '../client/tun/virtual.js';
import { isStun, stunResponse } from '../shared/stun.js';

const server = createServer({ httpPort: 0, udpPort: 0, host: '127.0.0.1', log: () => {} });
const { httpPort } = await server.listen();
const url = `http://127.0.0.1:${httpPort}`;
const stun = dgram.createSocket('udp4');
stun.on('message', (m, r) => { if (isStun(m)) stun.send(stunResponse(m, r.address, r.port), r.port, r.address); });
await new Promise((r) => stun.bind(0, '127.0.0.1', r));
const stunAddr = `127.0.0.1:${stun.address().port}`;

const pc = new Engine({ serverUrl: url, name: 'pc', tun: new VirtualTun(), serverUdp: false, noDirect: true, stunServers: [stunAddr], localEndpoints: [] });
await pc.start();
await pc.createNetwork('tcpnet', 'secret123');
process.stdout.write(JSON.stringify({ server: url, pcIp: pc.ip, pcId: pc.id, stun: stunAddr }) + '\n');

process.stdin.resume();
process.stdin.on('end', async () => { await pc.stop(); server.close(); stun.close(); process.exit(0); });
