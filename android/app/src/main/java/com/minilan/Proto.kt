// MiniLAN v1 wire format and crypto (mirror of shared/proto.js). See PROTOCOL.md.
package com.minilan

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

object T {
    const val UDP_REG = 0x01
    const val UDP_REG_ACK = 0x02
    const val RELAY = 0x03
    const val HS_INIT = 0x10
    const val HS_RESP = 0x11
    const val DATA = 0x12
    const val PUNCH = 0x14
}

object Ctrl { const val PING = 1; const val PONG = 2 }

class KeyPair(val priv: ByteArray, val pub: ByteArray)

object Proto {
    private val rng = SecureRandom()

    fun keyPairFromPriv(priv: ByteArray): KeyPair {
        val pub = ByteArray(32)
        X25519.generatePublicKey(priv, 0, pub, 0)
        return KeyPair(priv.copyOf(), pub)
    }

    fun genKeyPair(): KeyPair = keyPairFromPriv(ByteArray(32).also { rng.nextBytes(it) })

    fun x25519(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = ByteArray(32)
        if (!X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) throw IllegalArgumentException("x25519: low-order point")
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, len: Int): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, if (salt.isEmpty()) null else salt, info.toByteArray()))
        return ByteArray(len).also { g.generateBytes(it, 0, len) }
    }

    fun mac16(key: ByteArray, data: ByteArray, off: Int = 0, len: Int = data.size): ByteArray {
        val m = HMac(SHA256Digest())
        m.init(KeyParameter(key))
        m.update(data, off, len)
        val out = ByteArray(32)
        m.doFinal(out, 0)
        return out.copyOf(16)
    }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun nonce(counter: Long) = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    fun seal(key: ByteArray, counter: Long, aad: ByteArray, pt: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce(counter), aad))
        val out = ByteArray(c.getOutputSize(pt.size))
        val n = c.processBytes(pt, 0, pt.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    fun open(key: ByteArray, counter: Long, aad: ByteArray, ct: ByteArray, off: Int, len: Int): ByteArray? {
        if (len < 16) return null
        return try {
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), 128, nonce(counter), aad))
            val out = ByteArray(c.getOutputSize(len))
            val n = c.processBytes(ct, off, len, out, 0)
            c.doFinal(out, n)
            out
        } catch (e: Exception) {
            null
        }
    }

    class PeerStatic(val ss: ByteArray, val macKey: ByteArray)

    fun peerStatic(selfPriv: ByteArray, peerPub: ByteArray): PeerStatic {
        val ss = x25519(selfPriv, peerPub)
        return PeerStatic(ss, hkdf(ss, ByteArray(0), "minilan/v1/hs-mac", 32))
    }

    class SessionKeys(val i2r: ByteArray, val r2i: ByteArray)

    fun sessionKeys(ss: ByteArray, ee: ByteArray, eInit: ByteArray, eResp: ByteArray): SessionKeys {
        val okm = hkdf(ss + ee, eInit + eResp, "minilan/v1/session", 64)
        return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
    }

    // ---- packets ----

    private fun hsPacket(type: Int, sender: Int, receiver: Int, eph: ByteArray, ts: Long, macKey: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(65)
        b.put(type.toByte()).putInt(sender).putInt(receiver).put(eph).putLong(ts)
        val arr = b.array()
        System.arraycopy(mac16(macKey, arr, 0, 49), 0, arr, 49, 16)
        return arr
    }

    fun encodeInit(sender: Int, receiver: Int, eph: ByteArray, ts: Long, macKey: ByteArray) = hsPacket(T.HS_INIT, sender, receiver, eph, ts, macKey)
    fun encodeResp(sender: Int, receiver: Int, eph: ByteArray, initTs: Long, macKey: ByteArray) = hsPacket(T.HS_RESP, sender, receiver, eph, initTs, macKey)

    class Hs(val type: Int, val sender: Int, val receiver: Int, val eph: ByteArray, val ts: Long, val raw: ByteArray)

    fun decodeHs(b: ByteArray, off: Int, len: Int): Hs? {
        if (len != 65) return null
        val bb = ByteBuffer.wrap(b, off, len)
        val type = bb.get().toInt() and 0xff
        val sender = bb.int
        val receiver = bb.int
        val eph = ByteArray(32).also { bb.get(it) }
        val ts = bb.long
        return Hs(type, sender, receiver, eph, ts, b.copyOfRange(off, off + len))
    }

    fun verifyHs(hs: Hs, macKey: ByteArray): Boolean =
        MessageDigest.isEqual(mac16(macKey, hs.raw, 0, 49), hs.raw.copyOfRange(49, 65))

    fun encodeData(sender: Int, counter: Long, key: ByteArray, payload: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(13).put(T.DATA.toByte()).putInt(sender).putLong(counter).array()
        return h + seal(key, counter, h, payload)
    }

    fun encodeUdpReg(nodeId: Int, udpKey: String, ts: Long = System.currentTimeMillis()): ByteArray {
        val b = ByteBuffer.allocate(29).put(T.UDP_REG.toByte()).putInt(nodeId).putLong(ts).array()
        System.arraycopy(mac16(udpKey.toByteArray(), b, 0, 13), 0, b, 13, 16)
        return b
    }

    fun encodeRelay(src: Int, dst: Int, inner: ByteArray): ByteArray =
        ByteBuffer.allocate(9 + inner.size).put(T.RELAY.toByte()).putInt(src).putInt(dst).put(inner).array()

    fun encodePunch(sender: Int): ByteArray = ByteBuffer.allocate(5).put(T.PUNCH.toByte()).putInt(sender).array()

    fun encodeCtrl(kind: Int, ts: Long): ByteArray = ByteBuffer.allocate(10).put(0).put(kind.toByte()).putLong(ts).array()

    // ---- addressing ----
    fun ipToInt(s: String): Int = s.split(".").fold(0) { a, o -> (a shl 8) or o.toInt() }
    fun intToIp(n: Int): String = listOf(24, 16, 8, 0).joinToString(".") { ((n ushr it) and 255).toString() }
    val SUBNET = ipToInt("100.77.0.0")
    val BROADCAST = ipToInt("100.77.255.255")
    fun inSubnet(n: Int) = (n and 0xffff0000.toInt()) == SUBNET
}

// 64-entry sliding replay window (counters are < 2^63 in practice).
class ReplayWindow {
    private var max = -1L
    private var bits = 0L
    fun check(n: Long): Boolean {
        if (n > max) return true
        val d = max - n
        return d < 64 && (bits ushr d.toInt()) and 1L == 0L
    }
    fun accept(n: Long) {
        if (n > max) {
            val shift = if (max < 0) 64 else n - max
            bits = if (shift >= 64) 1L else (bits shl shift.toInt()) or 1L
            max = n
        } else {
            bits = bits or (1L shl (max - n).toInt())
        }
    }
}
