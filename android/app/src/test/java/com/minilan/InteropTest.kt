// Runs the real Android Engine (on the JVM) against the Node server and a Node desktop peer:
// register, join, handshake, then an ICMP ping through the encrypted tunnel. Needs `node` on PATH.
package com.minilan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class InteropTest {
    private class PacketSink : OutputStream() {
        val packets = LinkedBlockingQueue<ByteArray>()
        override fun write(b: Int) = throw UnsupportedOperationException()
        override fun write(b: ByteArray, off: Int, len: Int) { packets.add(b.copyOfRange(off, off + len)) }
    }

    private fun checksum(b: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < b.size) { sum += ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255); i += 2 }
        if (b.size % 2 == 1) sum += (b.last().toInt() and 255) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv() and 0xffff
    }

    private fun icmpEcho(src: String, dst: String, seq: Int): ByteArray {
        val icmp = ByteBuffer.allocate(8 + 4).put(8).put(0).putShort(0).putShort(0x4d4c).putShort(seq.toShort()).put("ping".toByteArray()).array()
        ByteBuffer.wrap(icmp).putShort(2, checksum(icmp).toShort())
        val ip = ByteBuffer.allocate(20).put(0x45).put(0).putShort((20 + icmp.size).toShort()).putShort(1).putShort(0)
            .put(64).put(1).putShort(0).putInt(Proto.ipToInt(src)).putInt(Proto.ipToInt(dst)).array()
        ByteBuffer.wrap(ip).putShort(10, checksum(ip).toShort())
        return ip + icmp
    }

    @Test fun androidEngineTalksToDesktopPeer() {
        val script = File("../../test/interop-peer.js").canonicalFile
        assumeTrue("node helper not found", script.exists())
        val proc = ProcessBuilder("node", script.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val info = JSONObject(proc.inputStream.bufferedReader().readLine())
            val sink = PacketSink()
            val e = Engine(info.getString("server"), "phone", Identity(null, null, 0, null), {}, {}, stunServers = emptyList())
            val myIp = e.register()
            e.joinNetwork("interop", "secret123")
            e.start(sink)

            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && e.status().peers.none { it.path.startsWith("direct") }) Thread.sleep(50)
            val peer = e.status().peers.single()
            assertEquals(info.getString("pcIp"), peer.ip)

            e.fromTun(icmpEcho(myIp, peer.ip, 7))
            val reply = sink.packets.poll(5, TimeUnit.SECONDS)
            assertNotNull("no echo reply through the tunnel", reply)
            val bb = ByteBuffer.wrap(reply!!)
            assertEquals(1, reply[9].toInt())                       // ICMP
            assertEquals(0, reply[20].toInt())                      // echo reply
            assertEquals(Proto.ipToInt(peer.ip), bb.getInt(12))     // from the PC
            assertEquals(7, bb.getShort(26).toInt())                // our sequence number
            println("interop OK: phone $myIp <-> pc ${peer.ip} via ${peer.path}")

            // A PC's LAN broadcast (Generals lobby, UDP 8086) arrives twice: as the original broadcast (for games
            // with a broadcast socket) and re-addressed to the phone's own IP (for Wine/Winlator games).
            sink.packets.clear()
            proc.outputStream.write("bcast\n".toByteArray()); proc.outputStream.flush()
            val orig = sink.packets.poll(5, TimeUnit.SECONDS)
            assertNotNull("broadcast not delivered", orig)
            assertEquals(-1, ByteBuffer.wrap(orig!!).getInt(16))          // still 255.255.255.255
            val b = sink.packets.poll(5, TimeUnit.SECONDS)
            assertNotNull("unicast copy not delivered", b)
            val bbb = ByteBuffer.wrap(b!!)
            assertEquals(17, b[9].toInt())
            assertEquals(Proto.ipToInt(myIp), bbb.getInt(16))             // dst rewritten to us
            assertEquals(Proto.ipToInt(peer.ip), bbb.getInt(12))          // from the PC
            assertEquals(8086, bbb.getShort(22).toInt() and 0xffff)      // Generals lobby port
            assertEquals(0, checksum(b.copyOfRange(0, 20)))              // valid IP header checksum
            assertEquals("generals lobby", String(b, 28, b.size - 28))
            println("broadcast OK: 255.255.255.255:8086 delivered as-is and as $myIp:8086")

            // The in-app API server must expose the same shape the shared web UI expects.
            val api = LocalApi({ File("../../client/web/index.html").inputStream() }, { JSONObject() }, { e }).start()
            fun call(path: String, token: String?, body: String? = null): Pair<Int, String> {
                val c = java.net.URL("http://127.0.0.1:${api.port}$path").openConnection() as java.net.HttpURLConnection
                token?.let { c.setRequestProperty("x-minilan", it) }
                if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
                val code = c.responseCode
                return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
            }
            assertEquals(403, call("/api/status", null).first)
            assertEquals(403, call("/api/status", "wrong-token").first)
            val page = call("/", null)
            assertEquals(200, page.first)
            assertTrue(page.second.contains("MiniLANAndroid"))
            val st = JSONObject(call("/api/status", api.token).second)
            assertTrue(st.getBoolean("connected"))
            assertEquals("android", st.getString("platform"))
            assertEquals(myIp, st.getJSONObject("self").getString("ip"))
            assertEquals(peer.ip, st.getJSONArray("peers").getJSONObject(0).getString("ip"))
            assertTrue(st.getJSONArray("relays").getJSONObject(0).getBoolean("selected"))
            assertEquals(1, st.getJSONArray("networks").length())
            val ping = call("/api/ping", api.token, JSONObject().put("id", peer.id.toString()).toString())
            assertEquals(200, ping.first)
            val rtt = JSONObject(ping.second).getLong("rtt")
            assertTrue(rtt >= 0)
            assertEquals(400, call("/api/mode", api.token, JSONObject().put("mode", "host").toString()).first)
            println("local API OK: status + ping $rtt ms")
            api.stop()
            e.stop()
        } finally {
            proc.outputStream.close()
            proc.waitFor(5, TimeUnit.SECONDS)
            proc.destroy()
        }
    }
}
