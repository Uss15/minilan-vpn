// Helper for the Android interop test: runs a server + a desktop (Node) peer with a virtual adapter,
// prints one JSON line with connection info, and exits when stdin closes.
import { createServer } from '../server/server.js';
import { Engine } from '../client/engine.js';
import { VirtualTun } from '../client/tun/virtual.js';

const server = createServer({ httpPort: 0, udpPort: 0, host: '127.0.0.1', log: () => {} });
const { httpPort } = await server.listen();
const url = `http://127.0.0.1:${httpPort}`;
const pc = new Engine({ serverUrl: url, name: 'pc', tun: new VirtualTun(), localEndpoints: ['127.0.0.1'], stunServers: [] });
await pc.start();
await pc.createNetwork('interop', 'secret123');
process.stdout.write(JSON.stringify({ server: url, pcIp: pc.ip, pcId: pc.id }) + '\n');

// Commands on stdin: "bcast" sends a Generals-style LAN lobby broadcast (UDP 8086) into the network.
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => {
  if (d.includes('bcast')) pc.tun.sendUdp('255.255.255.255', 8086, 8086, Buffer.from('generals lobby'));
});
process.stdin.on('end', async () => { await pc.stop(); server.close(); process.exit(0); });
