// Android engine with UDP to the server blocked and no direct path: it must learn its address via STUN
// and reach a desktop peer over Relay/TCP (WebSocket). Needs `node` on PATH; everything stays on localhost.
package com.minilan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RelayTcpTest {
    @Test fun androidUsesStunAndRelayTcp() {
        val script = File("../../test/ws-peer.js").canonicalFile
        assumeTrue("node helper not found", script.exists())
        val proc = ProcessBuilder("node", script.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val info = JSONObject(proc.inputStream.bufferedReader().readLine())
            val e = Engine(info.getString("server"), "phone", Identity(null, null, 0, null), {}, {},
                stunServers = listOf(info.getString("stun")), serverUdp = false, noDirect = true, localHosts = emptyList())
            e.register()
            e.joinNetwork("tcpnet", "secret123")
            e.start(java.io.ByteArrayOutputStream())

            val end = System.currentTimeMillis() + 15_000
            fun relays() = e.statusJson().getJSONArray("relays")
            fun tcpSelected() = (0 until relays().length()).any { relays().getJSONObject(it).let { r -> r.getString("name") == "Relay/TCP" && r.getBoolean("selected") } }
            while (System.currentTimeMillis() < end && !(tcpSelected() && e.status().peers.any { it.path == "relay" })) Thread.sleep(50)
            assertNotNull("STUN endpoint not learned", e.stunEndpoint)
            assertTrue("Relay/TCP not in use", tcpSelected())
            val peer = e.status().peers.single()
            assertEquals(info.getString("pcIp"), peer.ip)
            assertEquals("relay", peer.path)
            val rtt = e.ping(peer.id)
            println("relay/tcp OK: stun ${e.stunEndpoint}, ping $rtt ms")
            e.stop()
        } finally {
            proc.outputStream.close()
            proc.waitFor(5, TimeUnit.SECONDS)
            proc.destroy()
        }
    }
}
