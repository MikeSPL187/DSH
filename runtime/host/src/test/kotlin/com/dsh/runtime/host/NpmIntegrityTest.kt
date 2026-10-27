package com.dsh.runtime.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class NpmIntegrityTest {
    private val data = "package contents".toByteArray()
    private fun sri(algorithm: String, name: String, bytes: ByteArray = data) =
        "$name-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(bytes))
    private val sha1Hex = MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun `совпадение по integrity и shasum`() {
        assertTrue(NpmIntegrity.matches(data, sri("SHA-512", "sha512"), null))
        assertTrue(NpmIntegrity.matches(data, null, sha1Hex))
        assertTrue(NpmIntegrity.matches(data, "${sri("SHA-1", "sha1")} ${sri("SHA-512", "sha512")}", null))
    }

    @Test
    fun `подменённый архив и отсутствие хэша отклоняются`() {
        val tampered = "evil".toByteArray()
        assertFalse(NpmIntegrity.matches(tampered, sri("SHA-512", "sha512"), sha1Hex))
        assertFalse(NpmIntegrity.matches(tampered, null, sha1Hex))
        assertFalse(NpmIntegrity.matches(data, null, null))
        assertFalse(NpmIntegrity.matches(data, "md5-abc", null))
        // Слабый хэш не спасает при неверном сильном: проверяется сильнейший.
        assertFalse(NpmIntegrity.matches(data, "${sri("SHA-1", "sha1")} ${sri("SHA-512", "sha512", tampered)}", null))
    }
}
