package com.deepseek.dsh.core.data

import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 字段级密文：把令牌等秘密以 AES-256-GCM 加密后再写进 Room（审计 F-04/N-14）。
 *
 * 密钥默认来自 Android Keystore（不可导出、不随数据备份/拷贝离开设备），因此即便
 * `dsh_app.db` 被备份、root 后拷走或出现在诊断包里，令牌也读不出来。
 *
 * 存储格式：`ks1:` + Base64(12 字节 IV ‖ 密文+GCM tag)。没有前缀的值视为旧版明文，
 * [decrypt] 原样返回，由调用方重新加密写回（一次性迁移）。
 */
class SecretCipher(private val keyProvider: () -> SecretKey) {

    /** 加密；空串保持为空串（表示「未设置」）。 */
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
     * 解密；旧版明文原样返回。密文损坏或密钥丢失（例如重装后 Keystore 被清空）时返回 null，
     * 调用方应当把它当作「需要重新输入」而不是崩溃。
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

        /** 由 Android Keystore 托管的 AES 密钥；首次使用时生成。 */
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
