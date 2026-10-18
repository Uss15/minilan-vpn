// MiniLAN desktop client: engine + local web UI on 127.0.0.1.
//   node client/main.js --server http://vpn.example.com:8080 --name alice
//   node client/main.js --host --name alice            (run your own server on this PC too)
//   node client/main.js --server ... --tun virtual     (no adapter; for testing without admin)
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Engine } from './engine.js';
import { createServer } from '../server/server.js';

const argv = process.argv.slice(2);
const arg = (k, d) => { const i = argv.indexOf(`--${k}`); return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : d; };
const flag = (k) => argv.includes(`--${k}`);

const dataDir = arg('data-dir', path.join(os.homedir(), '.minilan'));
fs.mkdirSync(dataDir, { recursive: true });
const uiPort = Number(arg('ui', 7777));
const name = arg('name', os.hostname());
const games = JSON.parse(fs.readFileSync(new URL('../shared/games.json', import.meta.url), 'utf8'));

let serverUrl = arg('server', null);
let hosted = null;
let hostInfo = null; // addresses friends should use when this PC hosts
const configPath = arg('config', null); // launcher's config.json (enables switching host/join from the UI)
// The release's built-in online server (default-server.txt next to MiniLAN.bat, or the repo root in development).
const defaultServer = ['../../default-server.txt', '../default-server.txt']
  .map((p) => new URL(p, import.meta.url)).find((u) => fs.existsSync(u));
const DEFAULT_SERVER = defaultServer ? fs.readFileSync(defaultServer, 'utf8').trim() : null;
if (flag('host')) {
  const httpPort = Number(arg('host-http', 8080));
  const udpPort = Number(arg('host-udp', 8081));
  hosted = createServer({ httpPort, udpPort, dataFile: path.join(dataDir, 'server-data.json'), relaySecret: arg('relay-secret', null) });
  await hosted.listen();
  const rfc1918 = (ip) => /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(ip);
  // Virtual adapters (WSL/Hyper-V, VM software, other VPNs) are poor addresses to hand to friends.
  const virtualNic = /vEthernet|WSL|Hyper-V|VirtualBox|VMware|Radmin|Hamachi|ZeroTier|Tailscale|WARP|Loopback|MiniLAN/i;
  const score = (name, ip) => (virtualNic.test(name) ? 0 : 2) + (rfc1918(ip) ? 1 : 0);
  const lan = Object.entries(os.networkInterfaces())
    .flatMap(([name, list]) => (list || []).map((a) => ({ ...a, name })))
    .filter((a) => a.family === 'IPv4' && !a.internal && !a.address.startsWith('100.77.'))
    .sort((x, y) => score(y.name, y.address) - score(x.name, x.address)); // real LAN first
  // Talk to our own server over the LAN address so peers learn a reachable endpoint, not 127.0.0.1.
  serverUrl ||= `http://${lan[0]?.address || '127.0.0.1'}:${httpPort}`;
  let publicIp = null;
  try { publicIp = (await (await fetch('https://api.ipify.org', { signal: AbortSignal.timeout(4000) })).text()).trim(); } catch { /* offline */ }
  hostInfo = { lan: lan.filter((a) => !virtualNic.test(a.name)).map((a) => a.address), publicIp, httpPort, udpPort };
  console.log(`\nHosting a MiniLAN server. Friends connect with:`);
  for (const ip of hostInfo.lan) console.log(`   ${ip}   (same house / Wi-Fi)`);
  if (publicIp) console.log(`   ${publicIp}   (over the Internet, after forwarding TCP ${httpPort} + UDP ${udpPort} on your router to this PC)`);
  console.log('');
}
if (!serverUrl) {
  console.error('usage: node client/main.js --server http://HOST:8080 [--name NAME] [--tun wintun|virtual] [--relay NAME]\n       node client/main.js --host [--name NAME]');
  process.exit(1);
}

async function makeTun(kind) {
  if (kind === 'virtual') return new (await import('./tun/virtual.js')).VirtualTun();
  if (kind === 'wintun') return new (await import('./tun/wintun.js')).WintunTun({ dll: arg('wintun-dll', null) });
  throw new Error(`unknown --tun ${kind}`);
}
const tunKind = arg('tun', process.platform === 'win32' ? 'wintun' : 'virtual');
const tun = await makeTun(tunKind);

const stateFile = path.join(dataDir, `client-${Buffer.from(serverUrl).toString('base64url').slice(0, 24)}.json`);
const engine = new Engine({
  serverUrl, name, tun, statePath: stateFile,
  port: Number(arg('port', 0)), noDirect: flag('no-direct'), relayPrefer: arg('relay', null),
  log: (...a) => console.log(new Date().toISOString().slice(11, 19), ...a),
});
await engine.start();

// ---- local UI / control API ----
const uiPath = new URL('./web/index.html', import.meta.url); // shared with the Android app
const hostOk = (h) => h === `127.0.0.1:${uiPort}` || h === `localhost:${uiPort}`;

http.createServer(async (req, res) => {
  const send = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };
  if (!hostOk(req.headers.host)) return send(403, { error: 'bad host' }); // DNS-rebinding guard
  try {
    if (req.method === 'GET' && req.url === '/') {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'content-security-policy': "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'" });
      return res.end(fs.readFileSync(uiPath));
    }
    if (req.method === 'GET' && req.url === '/api/status') {
      return send(200, {
        ...engine.status(), connected: true, platform: 'pc',
        server: serverUrl, hosting: !!hosted, hostInfo, canSwitch: !!configPath, tun: tunKind, defaultServer: DEFAULT_SERVER,
      });
    }
    if (req.method === 'GET' && req.url === '/api/games') return send(200, games);
    if (req.method === 'GET' && req.url.startsWith('/api/rooms')) {
      const game = new URL(req.url, 'http://x').searchParams.get('game');
      return send(200, await engine.publicRooms(game));
    }
    if (req.method !== 'POST') return send(404, { error: 'not found' });
    if (req.headers['x-minilan'] !== '1') return send(403, { error: 'missing X-MiniLAN header' }); // CSRF guard
    let raw = '';
    for await (const c of req) raw += c;
    const b = JSON.parse(raw || '{}');
    if (req.url === '/api/create') return send(200, await engine.createNetwork(b.name, b.password || '', { public: !!b.public, game: b.game }));
    if (req.url === '/api/join') return send(200, await engine.joinNetwork(b.name, b.password || ''));
    if (req.url === '/api/leave') return send(200, await engine.leaveNetwork(b.name));
    if (req.url === '/api/ping') return send(200, { rtt: await engine.ping(Number(b.id)) });
    if (req.url === '/api/relay') { engine.relayPrefer = b.name || null; return send(200, { ok: true }); }
    if (req.url === '/api/mode') {
      // Switch between hosting on this PC and joining a server; the launcher restarts us (exit code 3).
      if (!configPath) return send(400, { error: 'start MiniLAN with MiniLAN.bat to switch modes' });
      const cfg = JSON.parse(fs.readFileSync(configPath, 'utf8'));
      if (b.mode === 'host') cfg.mode = 'host';
      else {
        let s = String(b.server || '').trim();
        if (!s) return send(400, { error: 'server address required' });
        if (!/^https?:\/\//.test(s)) s = `http://${s}`;
        if (!/:\d+$/.test(new URL(s).host) && !s.startsWith('https')) s = `${s.replace(/\/$/, '')}:8080`;
        Object.assign(cfg, { mode: 'join', server: s });
      }
      fs.writeFileSync(configPath, JSON.stringify(cfg, null, 1));
      send(200, { ok: true, restarting: true });
      setTimeout(async () => { await engine.stop(); hosted?.close(); process.exit(3); }, 300);
      return;
    }
    send(404, { error: 'not found' });
  } catch (e) {
    send(400, { error: e.message });
  }
}).listen(uiPort, '127.0.0.1', () => console.log(`MiniLAN UI: http://127.0.0.1:${uiPort}  (you are ${engine.ip})`));

const shutdown = async () => { await engine.stop(); hosted?.close(); process.exit(0); };
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
