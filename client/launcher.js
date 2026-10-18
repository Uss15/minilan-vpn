// One-click Windows launcher: first-run setup (host or join), firewall rules, then starts MiniLAN and opens the UI.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline/promises';
import { execFile, exec } from 'node:child_process';
import { promisify } from 'node:util';

const run = promisify(execFile);
const dataDir = path.join(process.env.LOCALAPPDATA || os.homedir(), 'MiniLAN');
const cfgPath = path.join(dataDir, 'config.json');
fs.mkdirSync(dataDir, { recursive: true });

let cfg = fs.existsSync(cfgPath) && !process.argv.includes('--setup') ? JSON.parse(fs.readFileSync(cfgPath, 'utf8')) : null;

if (!cfg) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  console.log('\n=== MiniLAN setup ===\n');
  const name = (await rl.question(`Your player name [${os.hostname()}]: `)).trim() || os.hostname();
  console.log('\n  1) Join a server (a friend hosts, or an online server)');
  console.log('  2) Host the server on THIS PC (keep this PC on while people play)\n');
  const mode = (await rl.question('Choose 1 or 2: ')).trim() === '2' ? 'host' : 'join';
  let server = null;
  // A release can ship a default server (default-server.txt next to the app) so players just press Enter.
  const defFile = path.join(path.dirname(import.meta.filename), '..', 'default-server.txt');
  const def = fs.existsSync(defFile) ? fs.readFileSync(defFile, 'utf8').trim() : '';
  if (mode === 'join') {
    server = (await rl.question(`Server address${def ? ` [${def}]` : ' (e.g. 203.0.113.5 or http://vpn.example.com:8080)'}: `)).trim() || def;
    if (!/^https?:\/\//.test(server)) server = `http://${server}`;
    if (!/:\d+$/.test(new URL(server).host) && !server.startsWith('https')) server = `${server.replace(/\/$/, '')}:8080`;
  }
  rl.close();
  cfg = { name, mode, server };
  fs.writeFileSync(cfgPath, JSON.stringify(cfg, null, 1));
  console.log(`\nSaved to ${cfgPath}. Run "MiniLAN settings" to change it.\n`);
}

// Firewall: allow our node.exe (peer-to-peer UDP + game traffic arriving on the MiniLAN adapter), and the server ports when hosting.
const rule = async (n, args) => {
  await run('netsh', ['advfirewall', 'firewall', 'delete', 'rule', `name=${n}`]).catch(() => {});
  await run('netsh', ['advfirewall', 'firewall', 'add', 'rule', `name=${n}`, ...args]).catch((e) => console.log(`firewall rule '${n}' failed: ${e.message}`));
};
await rule('MiniLAN (app)', ['dir=in', 'action=allow', `program=${process.execPath}`, 'enable=yes', 'profile=any']);
if (cfg.mode === 'host') {
  await rule('MiniLAN server TCP 8080', ['dir=in', 'action=allow', 'protocol=TCP', 'localport=8080', 'profile=any']);
  await rule('MiniLAN server UDP 8081', ['dir=in', 'action=allow', 'protocol=UDP', 'localport=8081', 'profile=any']);
}

process.env.MINILAN_PRIVATE ??= '1'; // mark the MiniLAN adapter as a Private network so games accept LAN traffic
process.argv = [process.argv[0], 'main.js', '--name', cfg.name, '--data-dir', dataDir, '--config', cfgPath,
  ...(cfg.mode === 'host' ? ['--host'] : ['--server', cfg.server])];

try {
  await import('./main.js');
  if (!process.env.MINILAN_RESTARTED) exec('cmd /c start "" http://127.0.0.1:7777');
  console.log('\nMiniLAN is running. Keep this window open; close it to disconnect.\n');
} catch (e) {
  console.error(`\nMiniLAN failed to start: ${e.message}`);
  console.error('If the server is unreachable, run "MiniLAN settings" to change it.');
  await new Promise((r) => setTimeout(r, 60_000));
  process.exit(1);
}
