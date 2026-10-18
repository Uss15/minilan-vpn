// The phone-hosted server (Kotlin) must be a drop-in for the Node server: run two real desktop
// clients (Node) plus the Android engine against it. Needs `node` on PATH.
package com.minilan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class MiniServerTest {
    private val games = JSONObject(File("../../shared/games.json").readText())

    @Test fun desktopClientsWorkAgainstPhoneServer() {
        val script = File("../../test/clients-against.js").canonicalFile
        assumeTrue("node helper not found", script.exists())
        val data = File.createTempFile("minilan", ".json").apply { delete() }
        val srv = MiniServer(data, games, httpPort = 0, udpPort = 0, bind = "127.0.0.1").start()
        try {
            val p = ProcessBuilder("node", script.path, "http://127.0.0.1:${srv.boundHttpPort}").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("node clients did not finish", p.waitFor(60, TimeUnit.SECONDS))
            println(out.trim())
            assertEquals("node clients failed: $out", 0, p.exitValue())
            assertTrue(out.contains("OK 100.77."))
            // State survives a restart (same file format as the Node server).
            srv.stop()
            val again = MiniServer(data, games, httpPort = 0, udpPort = 0, bind = "127.0.0.1").start()
            val saved = JSONObject(data.readText())
            assertEquals(2, saved.getJSONObject("nodes").length())
            assertTrue(saved.getJSONObject("networks").has("lan"))
            again.stop()
        } finally {
            srv.stop()
            data.delete()
        }
    }

    @Test fun androidEngineWorksAgainstPhoneServer() {
        val srv = MiniServer(null, games, httpPort = 0, udpPort = 0, bind = "127.0.0.1").start()
        try {
            val url = "http://127.0.0.1:${srv.boundHttpPort}"
            val a = Engine(url, "phone-a", Identity(null, null, 0, null), {}, {}, stunServers = emptyList())
            val b = Engine(url, "phone-b", Identity(null, null, 0, null), {}, {}, stunServers = emptyList())
            a.register(); b.register()
            a.createNetwork("hotspot", "secret123")
            b.joinNetwork("hotspot", "secret123")
            a.start(java.io.ByteArrayOutputStream()); b.start(java.io.ByteArrayOutputStream())
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && (a.status().peers.none { it.path.startsWith("direct") } || b.status().peers.none { it.path.startsWith("direct") })) Thread.sleep(50)
            val rtt = a.ping(b.id)
            println("phone<->phone via phone server OK, ping $rtt ms")
            assertTrue(rtt >= 0)
            a.stop(); b.stop()
        } finally {
            srv.stop()
        }
    }
}
