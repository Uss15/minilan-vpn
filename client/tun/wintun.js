// Windows virtual adapter via Wintun (https://www.wintun.net), loaded with the koffi FFI package.
// Needs: Administrator, `npm install koffi`, and wintun.dll (amd64/arm64) next to this file or via --wintun-dll.
import { EventEmitter } from 'node:events';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs';
import path from 'node:path';
import { intToIp } from '../../shared/proto.js';

const run = promisify(execFile);
const ADAPTER = 'MiniLAN';
const RING = 0x400000; // 4 MiB ring buffer
const WAIT_TIMEOUT = 0x102;

export class WintunTun extends EventEmitter {
  constructor({ dll = null } = {}) {
    super();
    this.dllPath = dll || path.join(path.dirname(import.meta.filename), 'wintun.dll');
  }

  async start({ ip, prefix = 16 }) {
    if (!fs.existsSync(this.dllPath)) {
      throw new Error(`wintun.dll not found at ${this.dllPath}. Download it from https://www.wintun.net and copy bin/<arch>/wintun.dll there.`);
    }
    let koffi;
    try { koffi = (await import('koffi')).default; } catch {
      throw new Error('The Wintun backend needs the koffi package: run `npm install koffi` in the minilan folder.');
    }
    this.koffi = koffi;
    const lib = koffi.load(this.dllPath);
    const k32 = koffi.load('kernel32.dll');
    const f = {
      create: lib.func('void *WintunCreateAdapter(str16 name, str16 tunnelType, void *guid)'),
      open: lib.func('void *WintunOpenAdapter(str16 name)'),
      close: lib.func('void WintunCloseAdapter(void *adapter)'),
      start: lib.func('void *WintunStartSession(void *adapter, uint32_t capacity)'),
      end: lib.func('void WintunEndSession(void *session)'),
      event: lib.func('void *WintunGetReadWaitEvent(void *session)'),
      recv: lib.func('void *WintunReceivePacket(void *session, _Out_ uint32_t *size)'),
      release: lib.func('void WintunReleaseReceivePacket(void *session, void *packet)'),
      alloc: lib.func('void *WintunAllocateSendPacket(void *session, uint32_t size)'),
      send: lib.func('void WintunSendPacket(void *session, void *packet)'),
      wait: k32.func('uint32_t WaitForSingleObject(void *handle, uint32_t ms)'),
      lastError: k32.func('uint32_t GetLastError()'),
    };
    this.f = f;
    this.adapter = f.open(ADAPTER) || f.create(ADAPTER, 'MiniLAN', null);
    if (!this.adapter) throw new Error(`WintunCreateAdapter failed (error ${f.lastError()}). Run as Administrator.`);
    this.session = f.start(this.adapter, RING);
    if (!this.session) throw new Error(`WintunStartSession failed (error ${f.lastError()})`);
    this.readEvent = f.event(this.session);

    await this.configure(ip, prefix);
    this.running = true;
    this.readLoop();
  }

  async configure(ip, prefix) {
    const mask = intToIp(prefix ? (0xffffffff << (32 - prefix)) >>> 0 : 0);
    await run('netsh', ['interface', 'ipv4', 'set', 'address', `name=${ADAPTER}`, 'source=static', `address=${ip}`, `mask=${mask}`]);
    // Metric 1 makes Windows send 255.255.255.255 broadcasts and multicast (game LAN discovery) through MiniLAN.
    await run('netsh', ['interface', 'ipv4', 'set', 'interface', `interface=${ADAPTER}`, 'metric=1']).catch(() => {});
    // Windows marks new adapters "Public", which blocks inbound game traffic. Opt in with MINILAN_PRIVATE=1.
    if (process.env.MINILAN_PRIVATE === '1') {
      await run('powershell', ['-NoProfile', '-Command', `Set-NetConnectionProfile -InterfaceAlias '${ADAPTER}' -NetworkCategory Private`]).catch(() => {});
    }
  }

  readLoop() {
    const { f, koffi } = this;
    const size = [0];
    // Drain everything available, then wait (asynchronously, off the JS thread) for the read event.
    for (;;) {
      if (!this.running) return;
      const p = f.recv(this.session, size);
      if (!p) break;
      const pkt = Buffer.from(new Uint8Array(koffi.view(p, size[0])));
      f.release(this.session, p);
      this.emit('packet', pkt);
    }
    f.wait.async(this.readEvent, 250, (err, res) => {
      if (err || !this.running) return;
      if (res === WAIT_TIMEOUT || res === 0) setImmediate(() => this.readLoop());
    });
  }

  write(pkt) {
    if (!this.running) return;
    const p = this.f.alloc(this.session, pkt.length);
    if (!p) return; // ring full: drop, as a NIC would
    new Uint8Array(this.koffi.view(p, pkt.length)).set(pkt);
    this.f.send(this.session, p);
  }

  async stop() {
    if (!this.running) return;
    this.running = false;
    this.f.end(this.session);
    this.f.close(this.adapter);
  }
}
