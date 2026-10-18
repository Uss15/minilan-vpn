// MiniLAN client engine for Android (mirror of client/engine.js).
package com.minilan

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Base64
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "MiniLAN"
private const val REKEY_MS = 120_000L
private const val DEAD_MS = 30_000L
private const val DIRECT_STALE_MS = 15_000L
private const val KEEPALIVE_MS = 10_000L
private const val PROBE_MS = 5_000L
private const val REG_MS = 15_000L

class ApiException(val status: Int, msg: String) : Exception(msg)

/** Persistent identity (stored by the caller, e.g. SharedPreferences). */
data class Identity(var priv: ByteArray?, var token: String?, var id: Int, var ip: String?)

class Endpoint(val addr: InetAddress, val port: Int) {
    val key get() = "${addr.hostAddress}:$port"
    override fun toString() = key
}

private class Session(val send: ByteArray, val recv: ByteArray) {
    var counter = 0L
    val window = ReplayWindow()
    val created = System.currentTimeMillis()
}

private class Pending(val e: KeyPair, val ts: Long, val startedAt: Long) {
    var attempts = 0
    var sentAt = 0L
}

private class Peer(val id: Int, val pub: ByteArray, st: Proto.PeerStatic) {
    val ss = st.ss
    val macKey = st.macKey
    var name = ""
    var ip = ""
    var ipInt = 0
    var online = false
    var endpoints: List<Endpoint> = emptyList()
    var cur: Session? = null
    var prev: Session? = null
    var pending: Pending? = null
    var lastInitTs = 0L
    var lastSentTs = 0L
    var path: Endpoint? = null
    var pathRelay = false
    var lastRx = 0L
    var lastTx = 0L
    var lastDirectRx = 0L
    var lastProbe = 0L
    var lastPunch = 0L
    var rtt: Long? = null
    var fwUdp: List<IntRange>? = null // null = trusted
    var fwTcp: List<IntRange>? = null
}

private class Relay(val name: String, val region: String, val ep: Endpoint, val key: String, val builtin: Boolean) {
    var rtt: Long? = null
    var sentAt = 0L
    var lastAck = 0L
    fun fresh() = rtt != null && System.currentTimeMillis() - lastAck < 45_000
}

class Engine(
    serverUrl: String,
    val name: String,
    private val identity: Identity,
    private val saveIdentity: (Identity) -> Unit,
    private val protect: (DatagramSocket) -> Unit,
    /** Public STUN servers used to learn our public UDP address (works even when the server is HTTP-only). */
    private val stunServers: List<String> = DEFAULT_STUN,
    /** false simulates a server whose UDP port is unreachable (tests). */
    private val serverUdp: Boolean = true,
    /** Never try direct paths (tests). */
    private val noDirect: Boolean = false,
    /** Override the LAN endpoints we advertise, as "a.b.c.d" hosts (tests). */
    private val localHosts: List<String>? = null,
) {
    companion object {
        val DEFAULT_STUN = listOf("stun.l.google.com:19302", "stun.cloudflare.com:3478")
        const val STUN_COOKIE = 0x2112A442
    }

    val server = serverUrl.trimEnd('/')
    private val stunAddrs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var stunEndpoint: String? = null; private set
    @Volatile private var ws: WsClient? = null
    @Volatile private var wsConnecting = false
    private var lastWsTry = 0L
    private var startedAt = 0L
    private val pendingPings = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<Long>>()
    private val lock = Object()
    private lateinit var keys: KeyPair
    var id = 0; private set
    var ip = ""; private set
    private var token = ""
    private var udpPort = 0
    private lateinit var serverAddr: InetAddress
    private lateinit var udpKey: String
    private lateinit var sock: DatagramSocket
    private var tunOut: OutputStream? = null
    @Volatile private var running = false
    private var version = 0L
    private val peers = HashMap<Int, Peer>()
    private val byIp = HashMap<Int, Peer>()
    private val relays = LinkedHashMap<String, Relay>()
    private var networks = JSONArray()
    var publicEndpoint: String? = null; private set
    var relayPrefer: String? = null
    var games: JSONObject = JSONObject(); private set
    private val exec = Executors.newScheduledThreadPool(3)
    var onChange: (() -> Unit)? = null

    // ---------- control plane ----------

    fun api(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 10_000): JSONObject {
        val c = URL(server + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("content-type", "application/json")
        if (token.isNotEmpty()) c.setRequestProperty("authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "{}"
        val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (code >= 400) throw ApiException(code, json.optString("error", "HTTP $code"))
        return json
    }

    /** Registers (blocking). Returns the virtual IP to configure on the VPN interface. */
    fun register(): String {
        keys = identity.priv?.let { Proto.keyPairFromPriv(it) } ?: Proto.genKeyPair()
        token = identity.token ?: ""
        val body = JSONObject().put("name", name).put("pub", Base64.getEncoder().encodeToString(keys.pub))
        val r = try {
            api("POST", "/v1/register", body)
        } catch (e: ApiException) {
            if (e.status != 409) throw e
            keys = Proto.genKeyPair(); token = ""
            api("POST", "/v1/register", body.put("pub", Base64.getEncoder().encodeToString(keys.pub)))
        }
        if (r.has("token")) token = r.getString("token")
        id = r.getInt("id"); ip = r.getString("ip"); udpPort = r.getInt("udpPort")
        udpKey = Proto.sha256Hex(token)
        identity.priv = keys.priv; identity.token = token; identity.id = id; identity.ip = ip
        saveIdentity(identity)
        serverAddr = InetAddress.getByName(URL(server).host)
        relays["${serverAddr.hostAddress}:$udpPort"] = Relay("coordinator", "", Endpoint(serverAddr, udpPort), udpKey, true)
        games = runCatching { api("GET", "/v1/games") }.getOrDefault(JSONObject())
        return ip
    }

    /** Starts UDP, timers and the long-poll. tunOut receives packets for this device. */
    fun start(tunOut: OutputStream) {
        this.tunOut = tunOut
        sock = DatagramSocket(0)
        protect(sock)
        running = true
        Thread(::recvLoop, "minilan-udp").start()
        Thread(::pollLoop, "minilan-poll").start()
        startedAt = System.currentTimeMillis()
        exec.execute { reportEndpoints() }
        exec.scheduleAtFixedRate({ safe { sendReg() } }, 0, REG_MS, TimeUnit.MILLISECONDS)
        exec.scheduleAtFixedRate({ safe { queryStun() } }, 0, 60_000, TimeUnit.MILLISECONDS)
        exec.scheduleAtFixedRate({ safe { tick() } }, 500, 500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        exec.shutdownNow()
        runCatching { sock.close() }
        ws?.close()
    }

    private fun reportEndpoints() {
        runCatching { api("POST", "/v1/endpoints", JSONObject().put("local", JSONArray(localEndpoints())).put("public", stunEndpoint ?: JSONObject.NULL)) }
    }

    // ---------- STUN (RFC 5389): learn our public UDP address ----------

    private fun queryStun() {
        for (s in stunServers) {
            val m = Regex("^(.+):(\\d+)$").find(s) ?: continue
            val addr = runCatching { InetAddress.getByName(m.groupValues[1]) }.getOrNull() ?: continue
            val ep = Endpoint(addr, m.groupValues[2].toInt())
            stunAddrs.add(ep.key)
            val req = ByteBuffer.allocate(20).putShort(1).putShort(0).putInt(STUN_COOKIE).put(ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }).array()
            sendRaw(req, ep)
        }
    }

    private fun onStun(msg: ByteArray) {
        val bb = ByteBuffer.wrap(msg)
        if (msg.size < 20 || bb.getShort(0).toInt() != 0x0101) return
        var off = 20
        val end = minOf(msg.size, 20 + (bb.getShort(2).toInt() and 0xffff))
        while (off + 4 <= end) {
            val type = bb.getShort(off).toInt() and 0xffff
            val len = bb.getShort(off + 2).toInt() and 0xffff
            if ((type == 0x0020 || type == 0x8020) && len >= 8 && msg[off + 5].toInt() == 1) {
                val port = (bb.getShort(off + 6).toInt() and 0xffff) xor (STUN_COOKIE ushr 16)
                val ipInt = bb.getInt(off + 8) xor STUN_COOKIE
                val ep = "${Proto.intToIp(ipInt)}:$port"
                if (ep != stunEndpoint) {
                    stunEndpoint = ep
                    if (publicEndpoint == null) publicEndpoint = ep
                    exec.execute { reportEndpoints() }
                }
                return
            }
            off += 4 + len + ((4 - len % 4) % 4)
        }
    }

    // ---------- Relay/TCP over WebSocket ----------

    private fun udpRelayOk() = relays.values.any { it.fresh() }
    private val wsOpen get() = ws?.open == true

    private fun connectWs() {
        if (ws != null || wsConnecting || !running) return
        wsConnecting = true
        lastWsTry = System.currentTimeMillis()
        exec.execute {
            try {
                val url = server.replaceFirst(Regex("^http"), "ws") + "/v1/ws?token=$token"
                ws = WsClient(url, { m ->
                    if (m.size > 9 && (m[0].toInt() and 0xff) == T.RELAY && ByteBuffer.wrap(m).getInt(5) == id) {
                        safe { synchronized(lock) { onPeerPacket(m.copyOfRange(9, m.size), null, ByteBuffer.wrap(m).getInt(1)) } }
                    }
                }, { ws = null }).connect()
                Log.i(TAG, "relay/tcp connected")
            } catch (e: Exception) {
                Log.w(TAG, "relay/tcp failed: ${e.message}")
                ws = null
            } finally {
                wsConnecting = false
            }
        }
    }

    private fun safe(f: () -> Unit) = try { f() } catch (e: Exception) { Log.w(TAG, "error", e) }

    private fun localEndpoints(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress!! }
        .filter { !Proto.inSubnet(Proto.ipToInt(it)) }
        .let { localHosts ?: it }
        .take(6)
        .map { "$it:${sock.localPort}" }

    private fun pollLoop() {
        while (running) {
            try {
                applyState(api("GET", "/v1/state?since=$version", timeoutMs = 35_000))
            } catch (e: Exception) {
                if (!running) return
                Log.w(TAG, "state poll failed: ${e.message}")
                Thread.sleep(2000)
            }
        }
    }

    private fun parseRanges(a: JSONArray?): List<IntRange> = (0 until (a?.length() ?: 0)).map {
        val p = a!!.getString(it).split("-").map(String::toInt)
        p[0]..(p.getOrNull(1) ?: p[0])
    }

    private fun applyState(st: JSONObject) {
        val relayList = st.optJSONArray("relays") ?: JSONArray()
        val relayKey = st.optString("relayKey", "")
        // DNS lookups outside the lock
        val resolved = (0 until relayList.length()).mapNotNull {
            val r = relayList.getJSONObject(it)
            val m = Regex("^(.+):(\\d+)$").find(r.getString("endpoint")) ?: return@mapNotNull null
            val addr = runCatching { InetAddress.getByName(m.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
            Relay(r.getString("name"), r.optString("region"), Endpoint(addr, m.groupValues[2].toInt()), relayKey, false)
        }
        synchronized(lock) {
            version = st.getLong("version")
            networks = st.getJSONArray("networks")
            val seen = HashSet<Int>()
            val arr = st.getJSONArray("peers")
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val pid = p.getInt("id")
                seen.add(pid)
                val pub = Base64.getDecoder().decode(p.getString("pub"))
                var peer = peers[pid]
                if (peer == null || !peer.pub.contentEquals(pub)) {
                    peer?.let { byIp.remove(it.ipInt) }
                    peer = Peer(pid, pub, Proto.peerStatic(keys.priv, pub))
                    peers[pid] = peer
                }
                peer.name = p.getString("name"); peer.ip = p.getString("ip"); peer.ipInt = Proto.ipToInt(peer.ip)
                peer.online = p.getBoolean("online")
                val eps = p.getJSONArray("endpoints")
                peer.endpoints = (0 until eps.length()).mapNotNull { j ->
                    val m = Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+)$").find(eps.getString(j)) ?: return@mapNotNull null
                    Endpoint(InetAddress.getByName(m.groupValues[1]), m.groupValues[2].toInt())
                }
                byIp[peer.ipInt] = peer
            }
            peers.keys.filter { it !in seen }.forEach { byIp.remove(peers.remove(it)!!.ipInt) }
            // game firewall: strangers (public rooms only) get the union of their games' ports
            for (peer in peers.values) {
                val shared = (0 until networks.length()).map { networks.getJSONObject(it) }
                    .filter { n -> n.getJSONArray("members").let { m -> (0 until m.length()).any { m.getInt(it) == peer.id } } }
                if (shared.any { !it.optBoolean("public") }) { peer.fwUdp = null; peer.fwTcp = null; continue }
                peer.fwUdp = shared.flatMap { parseRanges(games.optJSONObject(it.optString("game"))?.optJSONArray("udp")) }
                peer.fwTcp = shared.flatMap { parseRanges(games.optJSONObject(it.optString("game"))?.optJSONArray("tcp")) }
            }
            val want = resolved.map { it.ep.key }.toSet()
            relays.entries.removeIf { !it.value.builtin && it.key !in want }
            for (r in resolved) if (r.ep.key !in relays && relayKey.isNotEmpty()) { relays[r.ep.key] = r; sendRegTo(r) }
        }
        onChange?.invoke()
    }

    // ---------- network actions (call off the main thread) ----------

    fun createNetwork(name: String, password: String, public: Boolean = false, game: String? = null) =
        api("POST", "/v1/networks", JSONObject().put("name", name).put("password", password).put("public", public).put("game", game))
    fun joinNetwork(name: String, password: String) = api("POST", "/v1/networks/join", JSONObject().put("name", name).put("password", password))
    fun leaveNetwork(name: String) = api("POST", "/v1/networks/leave", JSONObject().put("name", name))
    fun publicRooms(game: String) = api("GET", "/v1/public?game=" + URLEncoder.encode(game, "UTF-8")).getJSONArray("rooms")

    data class PeerView(val id: Int, val name: String, val ip: String, val online: Boolean, val path: String, val rtt: Long?, val trusted: Boolean)
    data class Status(val ip: String, val publicEndpoint: String?, val networks: JSONArray, val peers: List<PeerView>, val relay: String)

    fun status(): Status = synchronized(lock) {
        Status(ip, publicEndpoint, networks, peers.values.map {
            val path = when { it.cur == null -> "none"; it.pathRelay -> "relay"; it.path != null -> "direct ${it.path}"; else -> "none" }
            PeerView(it.id, it.name, it.ip, it.online, path, it.rtt, it.fwUdp == null)
        }, bestRelay().let { "${it.name} ${it.rtt ?: "?"} ms" })
    }

    /** Round-trip time to a peer through the tunnel (blocking, call off the main thread). */
    fun ping(peerId: Int, timeoutMs: Long = 3000): Long {
        val ts = System.currentTimeMillis() * 1000 + (Math.random() * 1000).toLong()
        val f = java.util.concurrent.CompletableFuture<Long>()
        synchronized(lock) {
            val peer = peers[peerId] ?: throw ApiException(404, "unknown peer")
            if (peer.cur == null) throw ApiException(409, "no session")
            pendingPings[ts] = f
            sendData(peer, Proto.encodeCtrl(Ctrl.PING, ts))
        }
        return try { f.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: Exception) { pendingPings.remove(ts); throw ApiException(504, "timeout") }
    }

    /** Same JSON shape as the desktop client's /api/status, so the shared web UI works unchanged. */
    fun statusJson(): JSONObject = synchronized(lock) {
        val best = bestRelay()
        JSONObject()
            .put("self", JSONObject().put("id", id).put("name", name).put("ip", ip)
                .put("port", if (::sock.isInitialized) sock.localPort else 0).put("publicEndpoint", publicEndpoint ?: JSONObject.NULL))
            .put("networks", JSONArray(networks.toString()))
            .put("peers", JSONArray(peers.values.map {
                val path = when { it.cur == null -> "none"; it.pathRelay -> "relay"; it.path != null -> "direct ${it.path}"; else -> "none" }
                JSONObject().put("id", it.id).put("name", it.name).put("ip", it.ip).put("online", it.online)
                    .put("trusted", it.fwUdp == null).put("path", path).put("rtt", it.rtt ?: JSONObject.NULL)
            }))
            .put("relays", JSONArray(relays.values.map {
                JSONObject().put("name", it.name).put("region", it.region).put("endpoint", it.ep.key)
                    .put("rtt", it.rtt ?: JSONObject.NULL).put("online", it.fresh()).put("selected", udpRelayOk() && it === best)
            } + (if (ws != null || wsConnecting) listOf(JSONObject().put("name", "Relay/TCP").put("region", "websocket")
                .put("endpoint", URL(server).host).put("rtt", JSONObject.NULL).put("online", wsOpen).put("selected", wsOpen && !udpRelayOk())) else emptyList())))
    }

    // ---------- UDP ----------

    private fun sendRaw(b: ByteArray, ep: Endpoint) {
        runCatching { sock.send(DatagramPacket(b, b.size, InetSocketAddress(ep.addr, ep.port))) }
    }

    private fun sendRegTo(r: Relay) { r.sentAt = System.nanoTime(); sendRaw(Proto.encodeUdpReg(id, r.key), r.ep) }
    private fun sendReg() = synchronized(lock) { relays.values.filter { serverUdp || !it.builtin }.forEach(::sendRegTo) }

    private fun bestRelay(): Relay {
        relayPrefer?.let { want -> relays.values.firstOrNull { it.name == want && it.fresh() }?.let { return it } }
        return relays.values.filter { it.fresh() }.minByOrNull { it.rtt!! } ?: relays.values.first { it.builtin }
    }

    private fun sendVia(peer: Peer, pkt: ByteArray, ep: Endpoint?, relay: Boolean) {
        if (relay) {
            val wrapped = Proto.encodeRelay(id, peer.id, pkt)
            val w = ws
            if (!udpRelayOk() && w != null && w.open) w.send(wrapped) // Relay/TCP
            else sendRaw(wrapped, bestRelay().ep)
        } else if (ep != null) sendRaw(pkt, ep)
    }

    private fun sendData(peer: Peer, payload: ByteArray, ep: Endpoint? = peer.path, relay: Boolean = peer.pathRelay): Boolean {
        val s = peer.cur ?: return false
        if (!relay && ep == null) return false
        sendVia(peer, Proto.encodeData(id, s.counter++, s.send, payload), ep, relay)
        peer.lastTx = System.currentTimeMillis()
        return true
    }

    private fun recvLoop() {
        val buf = ByteArray(65535)
        while (running) {
            val dp = DatagramPacket(buf, buf.size)
            try { sock.receive(dp) } catch (e: Exception) { if (running) continue else return }
            val msg = buf.copyOf(dp.length)
            safe { synchronized(lock) { onUdp(msg, Endpoint(dp.address, dp.port)) } }
        }
    }

    private fun onUdp(msg: ByteArray, from: Endpoint) {
        if (msg.isEmpty()) return
        if (msg.size >= 20 && ByteBuffer.wrap(msg).getInt(4) == STUN_COOKIE && from.key in stunAddrs) { onStun(msg); return }
        val relay = relays[from.key]
        if (relay != null) {
            val t = msg[0].toInt() and 0xff
            if (t == T.UDP_REG_ACK && msg.size == 7) {
                relay.rtt = (System.nanoTime() - relay.sentAt) / 1_000_000
                relay.lastAck = System.currentTimeMillis()
                if (relay.builtin) {
                    val bb = ByteBuffer.wrap(msg)
                    publicEndpoint = "${msg[1].toInt() and 255}.${msg[2].toInt() and 255}.${msg[3].toInt() and 255}.${msg[4].toInt() and 255}:${bb.getShort(5).toInt() and 0xffff}"
                }
            } else if (t == T.RELAY && msg.size > 9 && ByteBuffer.wrap(msg).getInt(5) == id) {
                onPeerPacket(msg.copyOfRange(9, msg.size), null, ByteBuffer.wrap(msg).getInt(1))
            }
            return
        }
        onPeerPacket(msg, from, null)
    }

    private fun markRx(peer: Peer, from: Endpoint?) {
        val now = System.currentTimeMillis()
        peer.lastRx = now
        if (from != null) { peer.path = from; peer.pathRelay = false; peer.lastDirectRx = now }
        else if (peer.path == null && !peer.pathRelay || (!peer.pathRelay && now - peer.lastDirectRx > DIRECT_STALE_MS)) { peer.pathRelay = true; peer.path = null }
    }

    private fun install(peer: Peer, send: ByteArray, recv: ByteArray) { peer.prev = peer.cur; peer.cur = Session(send, recv) }

    // from == null means it arrived via a relay from relaySrc
    private fun onPeerPacket(b: ByteArray, from: Endpoint?, relaySrc: Int?) {
        val type = b[0].toInt() and 0xff
        when (type) {
            T.PUNCH -> return
            T.HS_INIT, T.HS_RESP -> {
                val hs = Proto.decodeHs(b, 0, b.size) ?: return
                if (hs.receiver != id) return
                val peer = peers[hs.sender] ?: return
                if (relaySrc != null && relaySrc != hs.sender) return
                if (!Proto.verifyHs(hs, peer.macKey)) return
                if (type == T.HS_INIT) {
                    if (peer.id > id || hs.ts <= peer.lastInitTs) return
                    peer.lastInitTs = hs.ts
                    val e = Proto.genKeyPair()
                    val k = Proto.sessionKeys(peer.ss, Proto.x25519(e.priv, hs.eph), hs.eph, e.pub)
                    install(peer, k.r2i, k.i2r)
                    markRx(peer, from)
                    sendVia(peer, Proto.encodeResp(id, peer.id, e.pub, hs.ts, peer.macKey), from, from == null)
                } else {
                    val p = peer.pending ?: return
                    if (p.ts != hs.ts) return
                    val k = Proto.sessionKeys(peer.ss, Proto.x25519(p.e.priv, hs.eph), p.e.pub, hs.eph)
                    install(peer, k.i2r, k.r2i)
                    peer.pending = null
                    markRx(peer, from)
                }
                onChange?.invoke()
            }
            T.DATA -> {
                if (b.size < 13 + 16) return
                val bb = ByteBuffer.wrap(b)
                val sender = bb.getInt(1)
                val counter = bb.getLong(5)
                val peer = peers[sender] ?: return
                if (relaySrc != null && relaySrc != sender) return
                val aad = b.copyOfRange(0, 13)
                var pt: ByteArray? = null
                for (s in listOfNotNull(peer.cur, peer.prev)) {
                    if (!s.window.check(counter)) continue
                    pt = Proto.open(s.recv, counter, aad, b, 13, b.size - 13)
                    if (pt != null) { s.window.accept(counter); break }
                }
                if (pt == null) return
                markRx(peer, from)
                if (pt.isEmpty()) return
                if (pt[0].toInt() == 0 && pt.size == 10) {
                    val kind = pt[1].toInt()
                    val ts = ByteBuffer.wrap(pt).getLong(2)
                    if (kind == Ctrl.PING) sendData(peer, Proto.encodeCtrl(Ctrl.PONG, ts), from, from == null)
                    else if (kind == Ctrl.PONG) {
                        val rtt = System.currentTimeMillis() - ts / 1000
                        peer.rtt = rtt
                        pendingPings.remove(ts)?.complete(rtt)
                    }
                    return
                }
                if ((pt[0].toInt() ushr 4) != 4 || pt.size < 20) return
                val src = ByteBuffer.wrap(pt).getInt(12)
                if (src != peer.ipInt) return
                if (!allowedInbound(peer, pt)) return
                runCatching {
                    tunOut?.write(pt) // native ports (e.g. GeneralsZH Android) listen on 255.255.255.255:port
                    val copy = unicastBroadcast(pt)
                    if (copy !== pt) tunOut?.write(copy) // Wine/Winlator games only hear their own address
                }
            }
        }
    }

    /**
     * Games running under Wine on Android (e.g. C&C Generals in Winlator) bind their UDP socket to our 100.77 address,
     * and Linux doesn't deliver broadcasts to sockets bound to a unicast address. A unicast copy of each incoming LAN
     * broadcast (255.255.255.255 or 100.77.255.255), re-addressed to our own IP, lets those games hear lobby
     * announcements; the original broadcast is delivered as well for games with a broadcast socket.
     */
    internal fun unicastBroadcast(pkt: ByteArray): ByteArray {
        if (pkt.size < 28 || (pkt[9].toInt() and 0xff) != 17) return pkt
        val bb = ByteBuffer.wrap(pkt)
        val dst = bb.getInt(16)
        if (dst != -1 && dst != Proto.BROADCAST) return pkt
        val out = pkt.copyOf()
        val ob = ByteBuffer.wrap(out)
        val ihl = (out[0].toInt() and 15) * 4
        ob.putInt(16, Proto.ipToInt(ip))
        ob.putShort(10, 0)
        var sum = 0
        for (i in 0 until ihl step 2) sum += ob.getShort(i).toInt() and 0xffff
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        ob.putShort(10, sum.inv().toShort())
        ob.putShort(ihl + 6, 0) // UDP checksum is optional over IPv4; 0 = not computed
        return out
    }

    private fun allowedInbound(peer: Peer, pkt: ByteArray): Boolean {
        val udp = peer.fwUdp ?: return true
        val proto = pkt[9].toInt() and 0xff
        if (proto == 1) return true
        if (proto != 6 && proto != 17) return false
        val ihl = (pkt[0].toInt() and 15) * 4
        if (pkt.size < ihl + 4) return false
        val bb = ByteBuffer.wrap(pkt)
        val sport = bb.getShort(ihl).toInt() and 0xffff
        val dport = bb.getShort(ihl + 2).toInt() and 0xffff
        val ranges = if (proto == 17) udp else peer.fwTcp!!
        return ranges.any { dport in it || sport in it }
    }

    /** Called by the VPN service for every packet the device sends into 100.77.0.0/16. */
    fun fromTun(pkt: ByteArray) = synchronized(lock) {
        if (pkt.size < 20 || (pkt[0].toInt() ushr 4) != 4) return@synchronized
        val dst = ByteBuffer.wrap(pkt).getInt(16)
        if (dst == -1 || dst == Proto.BROADCAST || (dst ushr 28) == 0xe) {
            peers.values.filter { it.cur != null }.forEach { sendData(it, pkt) }
            return@synchronized
        }
        byIp[dst]?.let { sendData(it, pkt) }
    }

    // ---------- timers ----------

    private fun tick() = synchronized(lock) {
        val now = System.currentTimeMillis()
        // No relay answered over UDP (UDP blocked or the server sits behind an HTTP-only tunnel): use Relay/TCP.
        if (ws == null && !udpRelayOk() && now - startedAt > 2500 && now - lastWsTry > 3000) connectWs()
        for (peer in peers.values) {
            if (peer.cur != null && now - peer.lastRx > DEAD_MS) {
                peer.cur = null; peer.prev = null; peer.path = null; peer.pathRelay = false
            }
            if (!peer.online) continue
            val direct = if (noDirect) emptyList() else peer.endpoints
            if (id < peer.id) {
                val needHs = peer.cur == null || now - peer.cur!!.created > REKEY_MS
                val p0 = peer.pending
                if (needHs && (p0 == null || now - p0.sentAt >= 1000)) {
                    if (p0 == null || now - p0.startedAt > 10_000) {
                        val ts = maxOf(now, peer.lastSentTs + 1)
                        peer.lastSentTs = ts
                        peer.pending = Pending(Proto.genKeyPair(), ts, now)
                    }
                    val p = peer.pending!!
                    p.attempts++; p.sentAt = now
                    val pkt = Proto.encodeInit(id, peer.id, p.e.pub, p.ts, peer.macKey)
                    if (peer.cur != null && (peer.path != null || peer.pathRelay)) {
                        sendVia(peer, pkt, peer.path, peer.pathRelay)
                    } else {
                        direct.forEach { sendRaw(pkt, it) }
                        if (p.attempts >= 3) sendVia(peer, pkt, null, true)
                    }
                }
            } else if (peer.cur == null && now - peer.lastPunch >= 1000) {
                peer.lastPunch = now
                direct.forEach { sendRaw(Proto.encodePunch(id), it) }
            }
            if (peer.cur != null && now - peer.lastTx > KEEPALIVE_MS) sendData(peer, Proto.encodeCtrl(Ctrl.PING, now * 1000))
            if (peer.cur != null && peer.pathRelay && direct.isNotEmpty() && now - peer.lastProbe > PROBE_MS) {
                peer.lastProbe = now
                direct.forEach { sendData(peer, Proto.encodeCtrl(Ctrl.PING, now * 1000), it, false) }
            }
        }
    }
}
