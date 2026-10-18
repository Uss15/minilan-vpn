// MiniLAN coordinator for phones: a Kotlin port of server/server.js + server/udp.js (same HTTP API and UDP
// protocol, so PC and phone clients work with either). Built-in relay only; standalone relay nodes are PC/VPS-only.
package com.minilan

import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

class MiniServer(
    private val dataFile: File?,
    private val games: JSONObject,
    private var httpPort: Int = 8080,
    private var udpPort: Int = 8081,
    private val bind: String = "0.0.0.0",
    private val log: (String) -> Unit = {},
) {
    private companion object {
        const val MAX_MEMBERS = 250
        const val ONLINE_MS = 45_000L
        const val POLL_MS = 25_000L
    }

    private class Node(val id: Int, var name: String, val pub: String, val ip: String, val tokenHash: String)
    private class Net(val name: String, val salt: String, val pwHash: String?, val public: Boolean, val game: String?,
                      val members: LinkedHashMap<Int, String>)
    private class Live(var addr: InetAddress? = null, var port: Int = 0, var lastSeen: Long = 0, var lastTs: Long = 0)

    private val lock = Object()
    private val rng = SecureRandom()
    private var nextId = 1
    private val nodes = LinkedHashMap<Int, Node>()
    private val networks = LinkedHashMap<String, Net>()
    private val live = HashMap<Int, Live>()
    private val localEps = HashMap<Int, List<String>>()
    private var version = 1L
    @Volatile private var running = false
    private lateinit var http: ServerSocket
    private lateinit var udp: DatagramSocket

    val boundHttpPort get() = http.localPort
    val boundUdpPort get() = udp.localPort

    // ---------- helpers ----------

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
    // Same parameters as Node's crypto.scryptSync(pw, saltHexString, 32): N=16384, r=8, p=1.
    private fun hashPw(pw: String, salt: String) = hex(SCrypt.generate(pw.toByteArray(), salt.toByteArray(), 16384, 8, 1, 32))
    private fun netKey(name: String) = name.trim().lowercase()

    private fun ipForIndex(n: Int): String {
        var idx = 0
        var i = 0
        while (true) {
            i++
            val lo = i and 255
            if (lo == 0 || lo == 255) continue
            if (++idx == n) return "100.77.${(i shr 8) and 255}.$lo"
        }
    }

    private fun isOnline(id: Int) = live[id]?.let { System.currentTimeMillis() - it.lastSeen < ONLINE_MS } ?: false
    private fun sharesNetwork(a: Int, b: Int) = networks.values.any { it.members.containsKey(a) && it.members.containsKey(b) }
    private fun bump() { version++; lock.notifyAll() }

    // ---------- persistence (same JSON layout as the Node server's data file) ----------

    private fun load() {
        if (dataFile == null || !dataFile.exists()) return
        val o = JSONObject(dataFile.readText())
        nextId = o.optInt("nextId", 1)
        o.optJSONObject("nodes")?.let { ns ->
            for (k in ns.keys()) ns.getJSONObject(k).let {
                nodes[it.getInt("id")] = Node(it.getInt("id"), it.getString("name"), it.getString("pub"), it.getString("ip"), it.getString("tokenHash"))
            }
        }
        o.optJSONObject("networks")?.let { ns ->
            for (k in ns.keys()) ns.getJSONObject(k).let { n ->
                val m = LinkedHashMap<Int, String>()
                n.getJSONObject("members").let { mm -> for (id in mm.keys()) m[id.toInt()] = mm.getString(id) }
                networks[k] = Net(n.getString("name"), n.getString("salt"), n.optString("pwHash").ifEmpty { null }.takeIf { !n.isNull("pwHash") },
                    n.optBoolean("public"), n.optString("game").ifEmpty { null }.takeIf { !n.isNull("game") }, m)
            }
        }
    }

    private fun save() {
        if (dataFile == null) return
        val o = JSONObject().put("nextId", nextId)
        o.put("nodes", JSONObject().apply {
            nodes.values.forEach { put(it.id.toString(), JSONObject().put("id", it.id).put("name", it.name).put("pub", it.pub).put("ip", it.ip).put("tokenHash", it.tokenHash)) }
        })
        o.put("networks", JSONObject().apply {
            networks.forEach { (k, n) ->
                put(k, JSONObject().put("name", n.name).put("salt", n.salt).put("pwHash", n.pwHash ?: JSONObject.NULL)
                    .put("public", n.public).put("game", n.game ?: JSONObject.NULL)
                    .put("members", JSONObject().apply { n.members.forEach { (id, r) -> put(id.toString(), r) } }))
            }
        })
        val tmp = File(dataFile.path + ".tmp")
        tmp.writeText(o.toString(1))
        java.nio.file.Files.move(tmp.toPath(), dataFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    // ---------- views ----------

    private fun view(id: Int): JSONObject {
        val self = nodes.getValue(id)
        val nets = JSONArray()
        val peerIds = LinkedHashSet<Int>()
        for (n in networks.values) {
            if (!n.members.containsKey(id)) continue
            nets.put(JSONObject().put("name", n.name).put("role", n.members[id]).put("members", JSONArray(n.members.keys.toList()))
                .put("public", n.public).put("game", n.game ?: JSONObject.NULL))
            n.members.keys.forEach { if (it != id) peerIds.add(it) }
        }
        val peers = JSONArray()
        for (pid in peerIds) {
            val p = nodes[pid] ?: continue
            val pl = live[pid]
            val pubEp = pl?.addr?.let { "${it.hostAddress}:${pl.port}" }
            val eps = JSONArray()
            pubEp?.let { eps.put(it) }
            (localEps[pid] ?: emptyList()).filter { it != pubEp }.forEach { eps.put(it) }
            peers.put(JSONObject().put("id", p.id).put("name", p.name).put("ip", p.ip).put("pub", p.pub).put("online", isOnline(pid)).put("endpoints", eps))
        }
        val l = live[id]
        return JSONObject().put("version", version)
            .put("self", JSONObject().put("id", id).put("ip", self.ip).put("name", self.name)
                .put("publicEndpoint", l?.addr?.let { "${it.hostAddress}:${l.port}" } ?: JSONObject.NULL))
            .put("networks", nets).put("peers", peers).put("relays", JSONArray()).put("relayKey", JSONObject.NULL)
    }

    private fun auth(req: HttpReq): Node? {
        val m = Regex("^Bearer (\\w+)$").find(req.headers["authorization"] ?: "") ?: return null
        val h = sha256(m.groupValues[1])
        return nodes.values.firstOrNull { it.tokenHash == h }
    }

    // ---------- HTTP routes (all called with lock held) ----------

    private fun route(req: HttpReq, body: JSONObject): Pair<Int, JSONObject> {
        val err = { code: Int, msg: String -> code to JSONObject().put("error", msg) }
        val ok = 200 to JSONObject().put("ok", true)
        if (req.method == "POST" && req.path == "/v1/register") {
            val name = body.optString("name").take(32).trim()
            val pub = runCatching { java.util.Base64.getDecoder().decode(body.optString("pub")) }.getOrNull()
            if (name.isEmpty() || pub == null || pub.size != 32) return err(400, "name and 32-byte pub required")
            val pubB64 = java.util.Base64.getEncoder().encodeToString(pub)
            nodes.values.firstOrNull { it.pub == pubB64 }?.let { existing ->
                val me = auth(req)
                if (me == null || me.id != existing.id) return err(409, "key already registered; send its token")
                existing.name = name
                save(); bump()
                return 200 to JSONObject().put("id", existing.id).put("ip", existing.ip).put("udpPort", udpPort)
            }
            val id = nextId++
            val token = hex(ByteArray(24).also { rng.nextBytes(it) })
            nodes[id] = Node(id, name, pubB64, ipForIndex(id), sha256(token))
            save()
            log("register #$id $name ${nodes[id]!!.ip}")
            return 200 to JSONObject().put("id", id).put("ip", nodes[id]!!.ip).put("token", token).put("udpPort", udpPort)
        }
        if (req.method == "GET" && req.path == "/v1/games") return 200 to games
        if (req.method == "GET" && req.path == "/healthz") return 200 to JSONObject().put("ok", true).put("version", version)

        val me = auth(req) ?: return err(401, "unauthorized")
        return when ("${req.method} ${req.path}") {
            "GET /v1/public" -> {
                val game = req.query["game"]
                val rooms = networks.values.filter { it.public && (game == null || it.game == game) }.map { n ->
                    JSONObject().put("name", n.name).put("game", n.game).put("locked", n.pwHash != null)
                        .put("members", n.members.size).put("online", n.members.keys.count { isOnline(it) })
                }.sortedByDescending { it.getInt("online") }.take(200)
                200 to JSONObject().put("rooms", JSONArray(rooms))
            }
            "POST /v1/endpoints" -> {
                val arr = body.optJSONArray("local") ?: JSONArray()
                localEps[me.id] = (0 until arr.length()).map { arr.optString(it) }
                    .filter { Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}$").matches(it) }.take(8)
                bump(); ok
            }
            "POST /v1/networks" -> {
                val name = body.optString("name").trim().take(64)
                val pw = body.optString("password")
                val isPublic = body.optBoolean("public")
                val game = if (isPublic) body.optString("game") else null
                if (name.isEmpty()) return err(400, "name required")
                if (isPublic && (game == null || !games.has(game) || game.startsWith("_"))) return err(400, "public rooms need a known game id")
                if (!isPublic && pw.length < 6) return err(400, "password (6+ chars) required")
                if (networks.containsKey(netKey(name))) return err(409, "network name taken")
                val salt = hex(ByteArray(16).also { rng.nextBytes(it) })
                networks[netKey(name)] = Net(name, salt, if (pw.isNotEmpty()) hashPw(pw, salt) else null, isPublic, game, linkedMapOf(me.id to "admin"))
                save(); bump()
                log("#${me.id} created network '$name'")
                ok
            }
            "POST /v1/networks/join" -> {
                val n = networks[netKey(body.optString("name"))] ?: return err(404, "no such network")
                if (n.pwHash != null) {
                    val got = hashPw(body.optString("password"), n.salt)
                    if (!MessageDigest.isEqual(got.toByteArray(), n.pwHash.toByteArray())) return err(403, "wrong password")
                } else if (!n.public) return err(403, "wrong password")
                if (!n.members.containsKey(me.id)) {
                    if (n.members.size >= MAX_MEMBERS) return err(403, "network full")
                    n.members[me.id] = "member"
                    save(); bump()
                }
                ok
            }
            "POST /v1/networks/leave" -> {
                val key = netKey(body.optString("name"))
                val n = networks[key]
                if (n == null || !n.members.containsKey(me.id)) return err(404, "not a member")
                n.members.remove(me.id)
                if (n.members.values.none { it == "admin" }) {
                    val next = n.members.keys.firstOrNull()
                    if (next != null) n.members[next] = "admin" else networks.remove(key)
                }
                save(); bump(); ok
            }
            else -> err(404, "not found")
        }
    }

    private fun handle(s: Socket) {
        val req = try { Http.parse(BufferedInputStream(s.getInputStream())) } catch (e: Exception) { null }
            ?: return Http.json(s, 413, JSONObject().put("error", "bad request"))
        try {
            if (req.method == "GET" && req.path == "/v1/state") {
                val since = req.query["since"]?.toLongOrNull() ?: 0
                val out = synchronized(lock) {
                    val me = auth(req) ?: return Http.json(s, 401, JSONObject().put("error", "unauthorized"))
                    val end = System.currentTimeMillis() + POLL_MS
                    while (running && version <= since) {
                        val left = end - System.currentTimeMillis()
                        if (left <= 0) break
                        lock.wait(left)
                    }
                    view(me.id)
                }
                return Http.json(s, 200, out)
            }
            val body = if (req.body.isBlank()) JSONObject() else JSONObject(req.body)
            val (code, out) = synchronized(lock) { route(req, body) }
            Http.json(s, code, out)
        } catch (e: Exception) {
            Http.json(s, 400, JSONObject().put("error", e.message ?: e.toString()))
        }
    }

    // ---------- UDP: discovery + relay (mirror of server/udp.js) ----------

    private fun mac16(key: ByteArray, data: ByteArray, len: Int) = Proto.mac16(key, data, 0, len)

    private fun onUdp(msg: ByteArray, len: Int, from: InetSocketAddress) {
        if (len < 1) return
        val bb = ByteBuffer.wrap(msg, 0, len)
        val type = msg[0].toInt() and 0xff
        if (type == T.UDP_REG && len == 29) {
            val id = bb.getInt(1)
            val ts = bb.getLong(5)
            val node = nodes[id] ?: return
            if (!MessageDigest.isEqual(mac16(node.tokenHash.toByteArray(), msg, 13), msg.copyOfRange(13, 29))) return
            if (Math.abs(ts - System.currentTimeMillis()) > 300_000) return
            val l = live.getOrPut(id) { Live() }
            if (ts <= l.lastTs) return
            val changed = l.addr != from.address || l.port != from.port || !isOnline(id)
            l.addr = from.address; l.port = from.port; l.lastSeen = System.currentTimeMillis(); l.lastTs = ts
            val ack = ByteBuffer.allocate(7).put(T.UDP_REG_ACK.toByte()).put(from.address.address).putShort(from.port.toShort()).array()
            udp.send(DatagramPacket(ack, ack.size, from))
            if (changed) bump()
            return
        }
        if (type == T.RELAY && len > 9) {
            val src = bb.getInt(1)
            val dst = bb.getInt(5)
            val ls = live[src] ?: return
            val ld = live[dst] ?: return
            if (ls.addr != from.address || ls.port != from.port) return // anti-spoof
            if (!isOnline(dst) || !sharesNetwork(src, dst)) return
            udp.send(DatagramPacket(msg, 0, len, ld.addr, ld.port))
        }
    }

    // ---------- lifecycle ----------

    fun start(): MiniServer {
        synchronized(lock) { load() }
        val addr = InetAddress.getByName(bind)
        http = ServerSocket(httpPort, 100, addr)
        udp = DatagramSocket(InetSocketAddress(addr, udpPort))
        httpPort = http.localPort
        udpPort = udp.localPort
        running = true
        Thread({
            while (running) {
                val s = try { http.accept() } catch (e: Exception) { break }
                Thread({ s.use { handle(it) } }, "miniserver-http").start()
            }
        }, "miniserver-accept").apply { isDaemon = true }.start()
        Thread({
            val buf = ByteArray(65535)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                try { udp.receive(p) } catch (e: Exception) { if (running) continue else break }
                try { synchronized(lock) { onUdp(buf, p.length, p.socketAddress as InetSocketAddress) } } catch (e: Exception) { log("udp: ${e.message}") }
            }
        }, "miniserver-udp").apply { isDaemon = true }.start()
        Thread({
            var last = emptySet<Int>()
            while (running) {
                try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                synchronized(lock) {
                    val now = live.keys.filter { isOnline(it) }.toSet()
                    if (now != last) bump()
                    last = now
                }
            }
        }, "miniserver-sweep").apply { isDaemon = true }.start()
        log("MiniLAN server http :$httpPort udp :$udpPort")
        return this
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        runCatching { http.close() }
        runCatching { udp.close() }
    }
}
