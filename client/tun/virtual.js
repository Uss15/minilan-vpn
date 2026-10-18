// In-process virtual host: answers ICMP echo and UDP echo (port 7). Lets you test without admin rights.
import { EventEmitter } from 'node:events';
import { parseIPv4, buildIPv4, buildIcmpEcho, ipToInt, intToIp } from '../ip.js';

export class VirtualTun extends EventEmitter {
  async start({ ip }) {
    this.ip = ip;
    this.ipInt = ipToInt(ip);
    this.waiting = new Map();
    this.udpInbox = [];
  }

  // Packet delivered by the tunnel to "this computer".
  write(pkt) {
    const ip = parseIPv4(pkt);
    if (!ip) return;
    const body = pkt.subarray(ip.ihl);
    if (ip.proto === 1 && body[0] === 8 && ip.dst === this.ipInt) {
      const r = buildIcmpEcho(0, body.readUInt16BE(4), body.readUInt16BE(6), body.subarray(8));
      setImmediate(() => this.emit('packet', buildIPv4(this.ipInt, ip.src, 1, r)));
    } else if (ip.proto === 1 && body[0] === 0) {
      const key = `${intToIp(ip.src)}/${body.readUInt16BE(6)}`;
      this.waiting.get(key)?.();
    } else if (ip.proto === 17) {
      this.udpInbox.push({ src: intToIp(ip.src), dst: intToIp(ip.dst), dport: body.readUInt16BE(2), data: body.subarray(8) });
      this.emit('udp', this.udpInbox.at(-1));
    }
  }

  // ICMP ping from this virtual host through the tunnel.
  ping(dst, seq = 1, timeoutMs = 3000) {
    const t0 = performance.now();
    return new Promise((resolve, reject) => {
      const key = `${dst}/${seq}`;
      const t = setTimeout(() => { this.waiting.delete(key); reject(new Error(`ping ${dst} timeout`)); }, timeoutMs);
      this.waiting.set(key, () => { clearTimeout(t); this.waiting.delete(key); resolve(performance.now() - t0); });
      this.emit('packet', buildIPv4(this.ipInt, dst, 1, buildIcmpEcho(8, 0x4d4c, seq, Buffer.from('minilan'))));
    });
  }

  // Send a UDP datagram (e.g. a game LAN broadcast) into the tunnel.
  sendUdp(dst, sport, dport, data) {
    const u = Buffer.alloc(8 + data.length);
    u.writeUInt16BE(sport, 0);
    u.writeUInt16BE(dport, 2);
    u.writeUInt16BE(u.length, 4);
    data.copy(u, 8);
    this.emit('packet', buildIPv4(this.ipInt, dst, 17, u));
  }
}
