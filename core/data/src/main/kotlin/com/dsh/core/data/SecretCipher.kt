package com.dsh.core.data

import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование полей: токены и другие секреты пишутся в Room под AES-256-GCM (аудит F-04/N-14).
 *
 * Ключ — в Android Keystore (не извлекается и не уходит с устройства с бэкапом), поэтому даже
 * если `dsh_app.db` попадёт в бэкап, будет скопирована с root или в отчёт, токен не прочитать.
 *
 * Формат: `ks1:` + Base64(IV 12 байт ‖ шифр + тег GCM). Значение без префикса — старый открытый текст:
 * [decrypt] вернёт его как есть, вызывающий зашифрует и перезапишет (разовая миграция).
 */
class SecretCipher(private val keyProvider: () -> SecretKey) {

    /** Зашифровать; пустая строка остаётся пустой («не задано»). */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "unexpected GCM IV size ${iv.size}" }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + java.util.Base64.getEncoder().encodeToString(iv + sealed)
    }

    /**
     * Расшифровать; старый открытый текст — как есть. Повреждённый шифр или утерянный ключ (например, Keystore очищен после переустановки) — null:
     * вызывающий просит ввести заново, а не падает.
     */
    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return ""
        if (!isEncrypted(stored)) return stored
        return runCatching {
            val raw = java.util.Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            require(raw.size > IV_BYTES) { "ciphertext too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    companion object {
        private const val PREFIX = "ks1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"

        /** Ключ AES в Android Keystore; создаётся при первом использовании. */
        fun androidKeystore(alias: String = "dsh_secret_v1"): SecretCipher = SecretCipher {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE,
            ).apply {
                init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        alias,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
    }
}
