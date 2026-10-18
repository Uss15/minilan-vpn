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
    private val name: String,
    private val identity: Identity,
    private val saveIdentity: (Identity) -> Unit,
    private val protect: (DatagramSocket) -> Unit,
) {
    private val server = serverUrl.trimEnd('/')
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
        val local = JSONArray(localEndpoints())
        exec.execute { runCatching { api("POST", "/v1/endpoints", JSONObject().put("local", local)) } }
        exec.scheduleAtFixedRate({ safe { sendReg() } }, 0, REG_MS, TimeUnit.MILLISECONDS)
        exec.scheduleAtFixedRate({ safe { tick() } }, 500, 500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        exec.shutdownNow()
        runCatching { sock.close() }
    }

    private fun safe(f: () -> Unit) = try { f() } catch (e: Exception) { Log.w(TAG, "error", e) }

    private fun localEndpoints(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress!! }
        .filter { !Proto.inSubnet(Proto.ipToInt(it)) }
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

    // ---------- UDP ----------

    private fun sendRaw(b: ByteArray, ep: Endpoint) {
        runCatching { sock.send(DatagramPacket(b, b.size, InetSocketAddress(ep.addr, ep.port))) }
    }

    private fun sendRegTo(r: Relay) { r.sentAt = System.nanoTime(); sendRaw(Proto.encodeUdpReg(id, r.key), r.ep) }
    private fun sendReg() = synchronized(lock) { relays.values.forEach(::sendRegTo) }

    private fun bestRelay(): Relay {
        relayPrefer?.let { want -> relays.values.firstOrNull { it.name == want && it.fresh() }?.let { return it } }
        return relays.values.filter { it.fresh() }.minByOrNull { it.rtt!! } ?: relays.values.first { it.builtin }
    }

    private fun sendVia(peer: Peer, pkt: ByteArray, ep: Endpoint?, relay: Boolean) {
        if (relay) sendRaw(Proto.encodeRelay(id, peer.id, pkt), bestRelay().ep)
        else if (ep != null) sendRaw(pkt, ep)
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
                    else if (kind == Ctrl.PONG) peer.rtt = System.currentTimeMillis() - ts / 1000
                    return
                }
                if ((pt[0].toInt() ushr 4) != 4 || pt.size < 20) return
                val src = ByteBuffer.wrap(pt).getInt(12)
                if (src != peer.ipInt) return
                if (!allowedInbound(peer, pt)) return
                runCatching { tunOut?.write(pt) }
            }
        }
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
        for (peer in peers.values) {
            if (peer.cur != null && now - peer.lastRx > DEAD_MS) {
                peer.cur = null; peer.prev = null; peer.path = null; peer.pathRelay = false
            }
            if (!peer.online) continue
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
                        peer.endpoints.forEach { sendRaw(pkt, it) }
                        if (p.attempts >= 3) sendVia(peer, pkt, null, true)
                    }
                }
            } else if (peer.cur == null && now - peer.lastPunch >= 1000) {
                peer.lastPunch = now
                peer.endpoints.forEach { sendRaw(Proto.encodePunch(id), it) }
            }
            if (peer.cur != null && now - peer.lastTx > KEEPALIVE_MS) sendData(peer, Proto.encodeCtrl(Ctrl.PING, now * 1000))
            if (peer.cur != null && peer.pathRelay && peer.endpoints.isNotEmpty() && now - peer.lastProbe > PROBE_MS) {
                peer.lastProbe = now
                peer.endpoints.forEach { sendData(peer, Proto.encodeCtrl(Ctrl.PING, now * 1000), it, false) }
            }
        }
    }
}
