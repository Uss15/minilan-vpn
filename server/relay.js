// Standalone relay node. Joins a coordinator started with --relay-secret; needs no database.
//   node server/relay.js --coord http://coord.example.com:8080 --secret S --name fra1 --region eu --public relay1.example.com:8082
import path from 'node:path';
import { createUdpCore, relayKeyFor } from './udp.js';

export function createRelay({ coord, secret, name, region = '', publicEndpoint = null, udpPort = 8082, host = '0.0.0.0', log = console.log }) {
  coord = coord.replace(/\/$/, '');
  let pairs = new Map(); // id -> Set(ids)
  const udp = createUdpCore({
    regKey: (id) => relayKeyFor(secret, id),
    canRelay: (a, b) => !!pairs.get(a)?.has(b),
  });
  let timer;
  let stopped = false;

  const headers = { 'x-relay-secret': secret, 'content-type': 'application/json' };
  async function sync() {
    try {
      await fetch(`${coord}/v1/relay/announce`, {
        method: 'POST', headers, body: JSON.stringify({ name, region, endpoint: publicEndpoint }),
      }).then((r) => { if (!r.ok) throw new Error(`announce HTTP ${r.status}`); });
      const r = await fetch(`${coord}/v1/relay/pairs`, { headers });
      if (!r.ok) throw new Error(`pairs HTTP ${r.status}`);
      const j = await r.json();
      pairs = new Map(Object.entries(j.pairs).map(([k, v]) => [Number(k), new Set(v)]));
    } catch (e) {
      log(`relay sync failed: ${e.message}`);
    }
    if (!stopped) timer = setTimeout(sync, 3000);
  }

  return {
    async listen() {
      udpPort = await udp.bind(udpPort, host);
      publicEndpoint ||= `127.0.0.1:${udpPort}`;
      await sync();
      log(`minilan relay '${name}' udp :${udpPort} announced as ${publicEndpoint} to ${coord}`);
      return { udpPort };
    },
    close() { stopped = true; clearTimeout(timer); udp.close(); },
  };
}

if (process.argv[1] && import.meta.filename === path.resolve(process.argv[1])) {
  const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : process.env[`MINILAN_${k.toUpperCase().replace(/-/g, '_')}`] || d; };
  const coord = arg('coord');
  const secret = arg('secret');
  if (!coord || !secret) {
    console.error('usage: node server/relay.js --coord URL --secret S --name NAME [--region R] [--udp 8082] [--public host:port]');
    process.exit(1);
  }
  createRelay({
    coord, secret, name: arg('name', 'relay'), region: arg('region', ''),
    udpPort: Number(arg('udp', 8082)), publicEndpoint: arg('public', null),
  }).listen();
}
