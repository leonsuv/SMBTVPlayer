package com.example.smbtv

import com.hierynomus.security.Mac
import com.hierynomus.security.SecurityProvider
import com.hierynomus.security.bc.BCSecurityProvider
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * smbj crypto provider that serves packet signing from the platform (Conscrypt/BoringSSL,
 * which uses the CPU's AES instructions) instead of BouncyCastle's pure-Java engines.
 *
 * SMB3 servers sign every response with AES-CMAC and smbj verifies each one on its single
 * packet-reader thread; with BouncyCastle that capped a Cortex-A55 TV box at ~2 MB/s.
 * Everything else (MD4/RC4 for the NTLM login, key derivation) is rare and stays on BouncyCastle.
 */
class FastSecurityProvider : SecurityProvider {
    private val bc = BCSecurityProvider()

    override fun getDigest(name: String) = bc.getDigest(name)

    override fun getMac(name: String): Mac = when (name.uppercase()) {
        "AESCMAC" -> AesCmac()
        "HMACSHA256" -> JceMac("HmacSHA256")
        else -> bc.getMac(name)
    }

    override fun getCipher(name: String) = bc.getCipher(name)

    override fun getAEADBlockCipher(name: String) = bc.getAEADBlockCipher(name)

    override fun getDerivationFunction(name: String) = bc.getDerivationFunction(name)
}

private class JceMac(private val algorithm: String) : Mac {
    private val mac = javax.crypto.Mac.getInstance(algorithm)

    override fun init(key: ByteArray) = mac.init(SecretKeySpec(key, algorithm))
    override fun update(b: Byte) = mac.update(b)
    override fun update(array: ByteArray) = mac.update(array)
    override fun update(array: ByteArray, offset: Int, length: Int) = mac.update(array, offset, length)
    override fun doFinal(): ByteArray = mac.doFinal()
    override fun reset() = mac.reset()
}

/**
 * AES-CMAC (RFC 4493) on top of the platform's AES-CBC. All complete blocks except the
 * last go through CBC with a zero IV in bulk; the last block is masked with K1/K2 and
 * pushed through the same chain to produce the tag.
 */
internal class AesCmac : Mac {
    private val cbc = Cipher.getInstance("AES/CBC/NoPadding")
    private lateinit var key: SecretKeySpec
    private val k1 = ByteArray(BLOCK)
    private val k2 = ByteArray(BLOCK)

    /** Holds the trailing 0..16 bytes, which can't be processed until we know they're last. */
    private val pending = ByteArray(BLOCK)
    private var pendingLen = 0
    private var scratch = ByteArray(64 * 1024)

    override fun init(keyBytes: ByteArray) {
        key = SecretKeySpec(keyBytes, "AES")
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, key)
        val l = ecb.doFinal(ByteArray(BLOCK))
        doubleInto(l, k1)
        doubleInto(k1, k2)
        reset()
    }

    override fun update(b: Byte) = update(byteArrayOf(b), 0, 1)

    override fun update(array: ByteArray) = update(array, 0, array.size)

    override fun update(array: ByteArray, offset: Int, length: Int) {
        var off = offset
        var len = length
        if (len <= 0) return
        if (pendingLen + len <= BLOCK) {
            System.arraycopy(array, off, pending, pendingLen, len)
            pendingLen += len
            return
        }
        // More than one block in total: the pending block is no longer the last, so chain it.
        if (pendingLen > 0) {
            val need = BLOCK - pendingLen
            System.arraycopy(array, off, pending, pendingLen, need)
            off += need
            len -= need
            chain(pending, 0, BLOCK)
            pendingLen = 0
        }
        // Chain everything but the final 1..16 bytes in one native call.
        val bulk = ((len - 1) / BLOCK) * BLOCK
        if (bulk > 0) {
            chain(array, off, bulk)
            off += bulk
            len -= bulk
        }
        System.arraycopy(array, off, pending, 0, len)
        pendingLen = len
    }

    override fun doFinal(): ByteArray {
        val last = ByteArray(BLOCK)
        if (pendingLen == BLOCK) {
            for (i in 0 until BLOCK) last[i] = (pending[i].toInt() xor k1[i].toInt()).toByte()
        } else {
            System.arraycopy(pending, 0, last, 0, pendingLen)
            last[pendingLen] = 0x80.toByte()
            for (i in 0 until BLOCK) last[i] = (last[i].toInt() xor k2[i].toInt()).toByte()
        }
        val tag = cbc.doFinal(last)
        reset()
        return tag
    }

    override fun reset() {
        cbc.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(ByteArray(BLOCK)))
        pendingLen = 0
    }

    private fun chain(array: ByteArray, offset: Int, length: Int) {
        if (scratch.size < length) scratch = ByteArray(length)
        cbc.update(array, offset, length, scratch, 0)
    }

    private companion object {
        const val BLOCK = 16

        /** GF(2^128) doubling used to derive the CMAC subkeys. */
        fun doubleInto(src: ByteArray, dst: ByteArray) {
            var carry = 0
            for (i in BLOCK - 1 downTo 0) {
                val v = src[i].toInt() and 0xff
                dst[i] = ((v shl 1) or carry).toByte()
                carry = v ushr 7
            }
            if (src[0].toInt() and 0x80 != 0) dst[BLOCK - 1] = (dst[BLOCK - 1].toInt() xor 0x87).toByte()
        }
    }
}
