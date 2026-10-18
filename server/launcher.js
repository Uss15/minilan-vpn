// "MiniLAN Server" for Windows: runs only the coordinator + relay on this PC (no adapter needed),
// opens the firewall ports, and prints the addresses to give your friends.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createServer } from './server.js';

const run = promisify(execFile);
const dataDir = path.join(process.env.LOCALAPPDATA || os.homedir(), 'MiniLAN', 'server');
fs.mkdirSync(dataDir, { recursive: true });
const HTTP = Number(process.env.MINILAN_HTTP || 8080);
const UDP = Number(process.env.MINILAN_UDP || 8081);

const rule = async (n, args) => {
  await run('netsh', ['advfirewall', 'firewall', 'delete', 'rule', `name=${n}`]).catch(() => {});
  await run('netsh', ['advfirewall', 'firewall', 'add', 'rule', `name=${n}`, ...args])
    .catch(() => console.log(`(could not add firewall rule '${n}' - run as Administrator or allow it manually)`));
};
await rule(`MiniLAN server TCP ${HTTP}`, ['dir=in', 'action=allow', 'protocol=TCP', `localport=${HTTP}`, 'profile=any']);
await rule(`MiniLAN server UDP ${UDP}`, ['dir=in', 'action=allow', 'protocol=UDP', `localport=${UDP}`, 'profile=any']);

await createServer({ httpPort: HTTP, udpPort: UDP, dataFile: path.join(dataDir, 'data.json') }).listen();

const virtualNic = /vEthernet|WSL|Hyper-V|VirtualBox|VMware|Radmin|Hamachi|ZeroTier|Tailscale|WARP|Loopback|MiniLAN/i;
const lan = Object.entries(os.networkInterfaces())
  .filter(([name]) => !virtualNic.test(name))
  .flatMap(([, l]) => (l || []).filter((a) => a.family === 'IPv4' && !a.internal).map((a) => a.address));
let publicIp = null;
try { publicIp = (await (await fetch('https://api.ipify.org', { signal: AbortSignal.timeout(4000) })).text()).trim(); } catch { /* offline */ }

console.log('\n================ MiniLAN server is running ================');
console.log('Same house / Wi-Fi friends use:');
for (const ip of lan) console.log(`    ${ip}`);
if (publicIp) console.log(`Friends over the Internet use:\n    ${publicIp}   (only after forwarding TCP ${HTTP} + UDP ${UDP} on your router to ${lan[0] || 'this PC'})`);
console.log('You (on this PC) use:  127.0.0.1');
console.log(`Data: ${dataDir}`);
console.log('Keep this window open. Close it to stop the server.');
console.log('===========================================================\n');
