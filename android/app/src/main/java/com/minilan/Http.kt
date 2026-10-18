// Minimal HTTP/1.1 request parsing and responses (one request per connection), shared by LocalApi and MiniServer.
package com.minilan

import java.io.InputStream
import java.net.Socket
import java.net.URLDecoder

class HttpReq(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

object Http {
    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    fun parse(inp: InputStream, maxBody: Int = 65536): HttpReq? {
        val first = readLine(inp) ?: return null
        val parts = first.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(inp) ?: return null
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > maxBody) return null
        val body = ByteArray(len)
        var off = 0
        while (off < len) { val n = inp.read(body, off, len - off); if (n < 0) break; off += n }
        val (path, qs) = parts[1].split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val query = qs.split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2); URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
        return HttpReq(parts[0], path, query, headers, String(body, 0, off, Charsets.UTF_8))
    }

    fun respond(s: Socket, code: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            409 to "Conflict", 413 to "Too Large", 504 to "Timeout")[code] ?: "Error"
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
    }

    fun json(s: Socket, code: Int, o: Any) = respond(s, code, "application/json", o.toString().toByteArray(Charsets.UTF_8))
}
