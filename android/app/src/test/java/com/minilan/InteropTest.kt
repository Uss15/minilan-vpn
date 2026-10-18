// Runs the real Android Engine (on the JVM) against the Node server and a Node desktop peer:
// register, join, handshake, then an ICMP ping through the encrypted tunnel. Needs `node` on PATH.
package com.minilan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            val e = Engine(info.getString("server"), "phone", Identity(null, null, 0, null), {}, {})
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
            e.stop()
        } finally {
            proc.outputStream.close()
            proc.waitFor(5, TimeUnit.SECONDS)
            proc.destroy()
        }
    }
}
