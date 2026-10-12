package com.example.smbtv

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class AesCmacTest {

    private val rfcKey = hex("2b7e151628aed2a6abf7158809cf4f3c")
    private val rfcMessage = hex(
        "6bc1bee22e409f96e93d7e117393172a" +
            "ae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52ef" +
            "f69f2445df4f9b17ad2b417be66c3710"
    )

    @Test
    fun matchesRfc4493Vectors() {
        assertArrayEquals(hex("bb1d6929e95937287fa37d129b756746"), cmac(rfcKey, rfcMessage.copyOf(0)))
        assertArrayEquals(hex("070a16b46b4d4144f79bdd9dd04a287c"), cmac(rfcKey, rfcMessage.copyOf(16)))
        assertArrayEquals(hex("dfa66747de9ae63030ca32611497c827"), cmac(rfcKey, rfcMessage.copyOf(40)))
        assertArrayEquals(hex("51f0bebf7e3b9d92fc49741779363cfe"), cmac(rfcKey, rfcMessage.copyOf(64)))
    }

    @Test
    fun matchesBouncyCastleForRandomMessagesAndChunking() {
        val rnd = Random(42)
        val mac = AesCmac()
        repeat(300) {
            val key = rnd.nextBytes(16)
            val msg = rnd.nextBytes(rnd.nextInt(0, 70_000))
            mac.init(key)
            // Feed in random-sized pieces to exercise the pending-block bookkeeping.
            var off = 0
            while (off < msg.size) {
                val n = minOf(msg.size - off, rnd.nextInt(1, 40))
                if (n == 1 && rnd.nextBoolean()) mac.update(msg[off]) else mac.update(msg, off, n)
                off += n
            }
            assertArrayEquals("len=${msg.size}", bcCmac(key, msg), mac.doFinal())
        }
    }

    @Test
    fun isReusableAfterDoFinalAndReset() {
        val mac = AesCmac()
        mac.init(rfcKey)
        mac.update(byteArrayOf(1, 2, 3))
        mac.reset()
        mac.update(rfcMessage, 0, 16)
        assertArrayEquals(hex("070a16b46b4d4144f79bdd9dd04a287c"), mac.doFinal())
        mac.update(rfcMessage, 0, 40)
        assertArrayEquals(hex("dfa66747de9ae63030ca32611497c827"), mac.doFinal())
    }

    private fun cmac(key: ByteArray, msg: ByteArray): ByteArray =
        AesCmac().apply { init(key); update(msg) }.doFinal()

    private fun bcCmac(key: ByteArray, msg: ByteArray): ByteArray {
        val m = CMac(AESEngine.newInstance())
        m.init(KeyParameter(key))
        m.update(msg, 0, msg.size)
        return ByteArray(m.macSize).also { m.doFinal(it, 0) }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
