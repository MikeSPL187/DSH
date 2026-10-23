package com.deepseek.dsh.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class SecretCipherTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = SecretCipher { key }

    @Test
    fun roundTripDoesNotStorePlaintext() {
        val sealed = cipher.encrypt("remote-secret-token")
        assertTrue(cipher.isEncrypted(sealed))
        assertFalse(sealed.contains("remote-secret-token"))
        assertEquals("remote-secret-token", cipher.decrypt(sealed))
        // Fresh IV each time.
        assertNotEquals(sealed, cipher.encrypt("remote-secret-token"))
    }

    @Test
    fun emptyStaysEmptyAndLegacyPlaintextPassesThrough() {
        assertEquals("", cipher.encrypt(""))
        assertEquals("", cipher.decrypt(""))
        assertEquals("legacy-token", cipher.decrypt("legacy-token"))
    }

    @Test
    fun tamperedOrForeignCiphertextIsRejected() {
        val sealed = cipher.encrypt("token")
        val raw = java.util.Base64.getDecoder().decode(sealed.removePrefix("ks1:"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(cipher.decrypt("ks1:" + java.util.Base64.getEncoder().encodeToString(raw)))

        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(SecretCipher { otherKey }.decrypt(sealed))
        assertNull(cipher.decrypt("ks1:not-base64!"))
    }
}
