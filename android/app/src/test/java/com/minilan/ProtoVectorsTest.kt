// Checks the Kotlin crypto/packets byte-for-byte against vectors produced by the Node implementation
// (regenerate with `npm run vectors` in the repo root).
package com.minilan

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoVectorsTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("vectors.json").readText())
    private fun hex(k: String) = v.getString(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun keysAndAgreement() {
        assertArrayEquals(hex("pubA"), Proto.keyPairFromPriv(hex("privA")).pub)
        assertArrayEquals(hex("pubB"), Proto.keyPairFromPriv(hex("privB")).pub)
        val st = Proto.peerStatic(hex("privA"), hex("pubB"))
        assertArrayEquals(hex("ss"), st.ss)
        assertArrayEquals(hex("ss"), Proto.peerStatic(hex("privB"), hex("pubA")).ss)
        assertArrayEquals(hex("macKey"), st.macKey)
        val ee = Proto.x25519(hex("privEI"), hex("pubER"))
        assertArrayEquals(hex("ee"), ee)
        val k = Proto.sessionKeys(st.ss, ee, hex("pubEI"), hex("pubER"))
        assertArrayEquals(hex("i2r"), k.i2r)
        assertArrayEquals(hex("r2i"), k.r2i)
    }

    @Test fun packets() {
        val mac = hex("macKey")
        val ts = v.getLong("ts")
        assertArrayEquals(hex("init"), Proto.encodeInit(1, 2, hex("pubEI"), ts, mac))
        assertArrayEquals(hex("resp"), Proto.encodeResp(2, 1, hex("pubER"), ts, mac))
        assertArrayEquals(hex("data"), Proto.encodeData(1, 5, hex("i2r"), hex("payload")))
        assertEquals(v.getString("udpKey"), Proto.sha256Hex(v.getString("token")))
        assertArrayEquals(hex("udpReg"), Proto.encodeUdpReg(7, v.getString("udpKey"), ts))
        assertArrayEquals(hex("relay"), Proto.encodeRelay(1, 2, byteArrayOf(0xff.toByte())))
        assertArrayEquals(hex("ctrl"), Proto.encodeCtrl(1, ts))
    }

    @Test fun decryptAndVerify() {
        val data = hex("data")
        val pt = Proto.open(hex("i2r"), 5, data.copyOfRange(0, 13), data, 13, data.size - 13)
        assertNotNull(pt)
        assertArrayEquals(hex("payload"), pt)
        val init = hex("init")
        val hs = Proto.decodeHs(init, 0, init.size)!!
        assertTrue(Proto.verifyHs(hs, hex("macKey")))
        init[20] = (init[20].toInt() xor 1).toByte()
        assertTrue(!Proto.verifyHs(Proto.decodeHs(init, 0, init.size)!!, hex("macKey")))
    }

    @Test fun replayWindow() {
        val w = ReplayWindow()
        for (n in listOf(0L, 1L, 5L, 3L)) { assertTrue(w.check(n)); w.accept(n) }
        assertTrue(!w.check(3) && !w.check(5) && w.check(4))
        w.accept(200)
        assertTrue(!w.check(100))
    }

    @Test fun addressing() {
        assertEquals("100.77.0.1", Proto.intToIp(Proto.ipToInt("100.77.0.1")))
        assertTrue(Proto.inSubnet(Proto.ipToInt("100.77.3.9")))
        assertTrue(!Proto.inSubnet(Proto.ipToInt("10.0.0.1")))
    }
}
