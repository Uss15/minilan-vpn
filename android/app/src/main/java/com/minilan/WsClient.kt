// Minimal RFC 6455 WebSocket client (binary messages) over plain or TLS sockets, no dependencies.
// Carries the "Relay/TCP" path when UDP to every relay is blocked (or the server is behind an HTTP tunnel).
package com.minilan

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class WsClient(
    private val url: String,
    private val onMessage: (ByteArray) -> Unit,
    private val onClose: () -> Unit,
) {
    private val rng = SecureRandom()
    private lateinit var sock: Socket
    private lateinit var out: OutputStream
    @Volatile var open = false
        private set

    /** Connects and completes the handshake (blocking), then reads frames on a background thread. */
    fun connect(): WsClient {
        val u = URI(url)
        val tls = u.scheme == "wss"
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        sock = if (tls) {
            (SSLSocketFactory.getDefault().createSocket(u.host, port) as SSLSocket).also { s ->
                s.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(u.host, s.session)) {
                    s.close(); throw java.io.IOException("TLS hostname mismatch for ${u.host}")
                }
            }
        } else Socket(u.host, port)
        sock.tcpNoDelay = true
        sock.soTimeout = 0
        out = sock.getOutputStream()
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { rng.nextBytes(it) })
        val path = (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
        val hostHdr = if (u.port > 0) "${u.host}:${u.port}" else u.host
        out.write("GET $path HTTP/1.1\r\nHost: $hostHdr\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n".toByteArray())
        out.flush()
        val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
        val status = readLine(inp)
        if (!status.contains(" 101 ")) { sock.close(); throw java.io.IOException("WebSocket rejected: $status") }
        val expect = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        var accepted = false
        while (true) {
            val line = readLine(inp)
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("sec-websocket-accept:") && line.substringAfter(':').trim() == expect) accepted = true
        }
        if (!accepted) { sock.close(); throw java.io.IOException("bad WebSocket accept") }
        open = true
        Thread({ readLoop(inp) }, "minilan-ws").apply { isDaemon = true }.start()
        return this
    }

    private fun readLine(inp: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0 || c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) throw java.io.IOException("header too long")
        }
    }

    private fun readLoop(inp: DataInputStream) {
        try {
            while (open) {
                val b0 = inp.readUnsignedByte()
                val b1 = inp.readUnsignedByte()
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = inp.readUnsignedShort().toLong() else if (len == 127L) len = inp.readLong()
                if (len > (1 shl 17)) throw java.io.IOException("frame too large")
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { inp.readFully(it) } else null
                val data = ByteArray(len.toInt()).also { inp.readFully(it) }
                if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
                when (b0 and 0x0f) {
                    0x1, 0x2 -> onMessage(data)
                    0x8 -> break
                    0x9 -> frame(0xA, data)
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    @Synchronized private fun frame(op: Int, data: ByteArray) {
        if (!open) return
        val mask = ByteArray(4).also { rng.nextBytes(it) }
        val head = when {
            data.size < 126 -> byteArrayOf((0x80 or op).toByte(), (0x80 or data.size).toByte())
            data.size < 65536 -> byteArrayOf((0x80 or op).toByte(), (0x80 or 126).toByte(), (data.size shr 8).toByte(), data.size.toByte())
            else -> java.nio.ByteBuffer.allocate(10).put((0x80 or op).toByte()).put((0x80 or 127).toByte()).putLong(data.size.toLong()).array()
        }
        val masked = ByteArray(data.size) { (data[it].toInt() xor mask[it and 3].toInt()).toByte() }
        try { out.write(head + mask + masked); out.flush() } catch (_: Exception) { close() }
    }

    fun send(data: ByteArray) = frame(0x2, data)

    fun close() {
        if (!open) return
        open = false
        runCatching { sock.close() }
        onClose()
    }
}
