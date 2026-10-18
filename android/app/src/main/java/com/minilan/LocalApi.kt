// Tiny HTTP server on 127.0.0.1 that serves the shared web UI (client/web/index.html, packaged as an asset)
// and the same /api/* endpoints as the desktop client. Every /api call must carry the per-launch token,
// which only our WebView receives (via the JS bridge), so other apps on the phone can't drive it.
package com.minilan

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

class LocalApi(
    private val page: () -> InputStream,
    /** Called when no engine is running: saved server/name + connecting/error state. */
    private val offlineState: () -> JSONObject,
    private val engine: () -> Engine?,
    /** Non-null while this phone hosts the server: addresses and ports for friends. */
    private val hostInfo: () -> JSONObject? = { null },
) {
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var running = true

    fun start(): LocalApi {
        Thread({
            while (running) {
                val s = try { server.accept() } catch (e: Exception) { break }
                Thread({ s.use { handle(it) } }, "minilan-api").start()
            }
        }, "minilan-api-accept").apply { isDaemon = true }.start()
        return this
    }

    fun stop() { running = false; runCatching { server.close() } }

    private fun handle(s: Socket) {
        val req = try { Http.parse(BufferedInputStream(s.getInputStream())) } catch (e: Exception) { null } ?: return
        val host = req.headers["host"] ?: ""
        if (host != "127.0.0.1:$port" && host != "localhost:$port") return Http.json(s, 403, JSONObject().put("error", "bad host"))
        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            return Http.respond(s, 200, "text/html; charset=utf-8", page().use { it.readBytes() })
        }
        if (!req.path.startsWith("/api/")) return Http.json(s, 404, JSONObject().put("error", "not found"))
        if (req.headers["x-minilan"] != token) return Http.json(s, 403, JSONObject().put("error", "missing token"))
        try {
            Http.json(s, 200, route(req))
        } catch (e: ApiException) {
            Http.json(s, if (e.status in 400..599) e.status else 400, JSONObject().put("error", e.message))
        } catch (e: Exception) {
            Http.json(s, 400, JSONObject().put("error", e.message ?: e.toString()))
        }
    }

    private fun route(req: HttpReq): Any {
        val e = engine()
        if (req.method == "GET" && req.path == "/api/status") {
            if (e == null) return offlineState().put("connected", false).put("platform", "android")
            val host = hostInfo()
            return e.statusJson().put("connected", true).put("platform", "android").put("server", e.server)
                .put("hosting", host != null).put("hostInfo", host ?: JSONObject.NULL).put("canSwitch", false)
        }
        e ?: throw ApiException(409, "Not connected")
        if (req.method == "GET") return when (req.path) {
            "/api/games" -> e.games
            "/api/rooms" -> JSONObject().put("rooms", e.publicRooms(req.query["game"] ?: ""))
            else -> throw ApiException(404, "not found")
        }
        val b = if (req.body.isBlank()) JSONObject() else JSONObject(req.body)
        return when (req.path) {
            "/api/create" -> e.createNetwork(b.optString("name"), b.optString("password"), b.optBoolean("public"), b.optString("game").ifEmpty { null })
            "/api/join" -> e.joinNetwork(b.optString("name"), b.optString("password"))
            "/api/leave" -> e.leaveNetwork(b.optString("name"))
            "/api/ping" -> JSONObject().put("rtt", e.ping(b.optString("id").toIntOrNull() ?: b.optInt("id")))
            "/api/relay" -> { e.relayPrefer = b.optString("name").ifEmpty { null }; JSONObject().put("ok", true) }
            "/api/mode" -> throw ApiException(400, "On the phone, use Disconnect and then Host on this phone")
            else -> throw ApiException(404, "not found")
        }
    }
}
