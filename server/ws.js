// Minimal RFC 6455 WebSocket server side (binary messages only), no dependencies.
// Used for the "Relay/TCP" path, which works through HTTP-only tunnels and firewalls that block UDP.
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const MAX_FRAME = 1 << 17;

export class WsConn extends EventEmitter {
  constructor(socket, head) {
    super();
    this.socket = socket;
    this.buf = head?.length ? Buffer.from(head) : Buffer.alloc(0);
    this.open = true;
    socket.setNoDelay(true);
    socket.on('data', (d) => { this.buf = Buffer.concat([this.buf, d]); this.parse(); });
    socket.on('close', () => this.closed());
    socket.on('error', () => this.closed());
    // Keep idle tunnels (e.g. Cloudflare) from dropping the connection.
    this.pinger = setInterval(() => this.frame(0x9, Buffer.alloc(0)), 20_000);
    if (this.buf.length) setImmediate(() => this.parse());
  }

  closed() {
    if (!this.open) return;
    this.open = false;
    clearInterval(this.pinger);
    this.emit('close');
  }

  parse() {
    while (this.buf.length >= 2) {
      const b0 = this.buf[0];
      const b1 = this.buf[1];
      const op = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f;
      let off = 2;
      if (len === 126) { if (this.buf.length < 4) return; len = this.buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (this.buf.length < 10) return; len = Number(this.buf.readBigUInt64BE(2)); off = 10; }
      if (len > MAX_FRAME || !masked) { this.close(); return; } // clients must mask
      if (this.buf.length < off + 4 + len) return;
      const mask = this.buf.subarray(off, off + 4);
      const data = Buffer.from(this.buf.subarray(off + 4, off + 4 + len));
      for (let i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
      this.buf = this.buf.subarray(off + 4 + len);
      if (op === 0x2 || op === 0x1) this.emit('message', data);
      else if (op === 0x8) { this.close(); return; }
      else if (op === 0x9) this.frame(0xa, data);
      // 0xA pong and fragmented frames (0x0) are ignored; clients send small single-frame messages
    }
  }

  frame(op, data) {
    if (!this.open) return;
    let h;
    if (data.length < 126) { h = Buffer.from([0x80 | op, data.length]); }
    else if (data.length < 65536) { h = Buffer.alloc(4); h[0] = 0x80 | op; h[1] = 126; h.writeUInt16BE(data.length, 2); }
    else { h = Buffer.alloc(10); h[0] = 0x80 | op; h[1] = 127; h.writeBigUInt64BE(BigInt(data.length), 2); }
    this.socket.write(Buffer.concat([h, data]));
  }

  send(data) { this.frame(0x2, data); }

  close() {
    if (!this.open) return;
    this.frame(0x8, Buffer.alloc(0));
    this.socket.end();
    this.closed();
  }
}

// Completes the handshake for an HTTP 'upgrade' event; returns a WsConn or null.
export function acceptWebSocket(req, socket, head) {
  const key = req.headers['sec-websocket-key'];
  if (!key || String(req.headers.upgrade).toLowerCase() !== 'websocket') {
    socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
    return null;
  }
  const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
  return new WsConn(socket, head);
}
