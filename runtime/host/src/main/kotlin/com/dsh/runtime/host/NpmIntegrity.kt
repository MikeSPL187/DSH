package com.dsh.runtime.host

import java.security.MessageDigest
import java.util.Base64

/** Проверка архива пакета по хэшу из реестра npm (`dist.integrity` в формате SRI или `dist.shasum`). */
internal object NpmIntegrity {
    private val ALGORITHMS = mapOf("sha512" to "SHA-512", "sha384" to "SHA-384", "sha256" to "SHA-256", "sha1" to "SHA-1")

    /**
     * true — архив совпал хотя бы с одним хэшем SRI (сильнейшим известным) или, если SRI нет, с `shasum`.
     * Нет ни одного хэша — false: непроверяемый архив не ставится.
     */
    fun matches(bytes: ByteArray, integrity: String?, shasum: String?): Boolean {
        val entries = integrity.orEmpty().split(Regex("\\s+")).mapNotNull { token ->
            val algorithm = ALGORITHMS[token.substringBefore('-', "").lowercase()] ?: return@mapNotNull null
            val expected = runCatching { Base64.getDecoder().decode(token.substringAfter('-').substringBefore('?')) }.getOrNull()
                ?: return@mapNotNull null
            algorithm to expected
        }
        if (entries.isNotEmpty()) {
            val strongest = entries.maxBy { (algorithm, _) -> ALGORITHMS.values.reversed().indexOf(algorithm) }.first
            return entries.filter { it.first == strongest }
                .any { (algorithm, expected) -> MessageDigest.isEqual(MessageDigest.getInstance(algorithm).digest(bytes), expected) }
        }
        val sha1 = shasum?.trim()?.lowercase()?.takeIf { it.matches(Regex("^[0-9a-f]{40}$")) } ?: return false
        val actual = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(actual.toByteArray(), sha1.toByteArray())
    }
}
