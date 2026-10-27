package com.dsh.feature.settings.logic

import com.dsh.core.designsystem.kit.*
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Копия API-ключа на устройстве, зашифрованная ключом Android Keystore (AES-GCM).
 *
 * Нужна, чтобы «Настройки» показывали последние 4 символа, дату добавления и живой баланс
 * провайдера. Сам ключ наружу не отдаётся: [last4] и [addedAt] — для показа, [use] —
 * только для запроса баланса внутри приложения. Копировать или показать ключ целиком нельзя.
 */
object ApiKeyVault {
    private const val PREFS = "dsh_key_vault"
    private const val ALIAS = "dsh_api_key_vault"

    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    /**
     * Запомнить ключ для ссылки [ref] (например `DEEPSEEK_API_KEY`).
     * @return false — Keystore не зашифровал ключ (G-36): тогда ничего не сохраняется, и «Настройки» не покажут
     *   «ключ …abcd сохранён», который нельзя использовать.
     */
    fun save(ref: String, key: String): Boolean {
        val p = prefs() ?: return false
        val enc = runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
            Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(key.toByteArray()), Base64.NO_WRAP)
        }.getOrNull()
        if (enc == null) {
            delete(ref)
            return false
        }
        p.edit()
            .putString("$ref.last4", key.takeLast(4))
            .putLong("$ref.added", System.currentTimeMillis())
            .putString("$ref.enc", enc)
            .remove("$ref.lost")
            .apply()
        return true
    }

    /** Сохранённый ключ больше не расшифровывается (ключ Keystore потерян): его нужно ввести заново. */
    fun lost(ref: String): Boolean = prefs()?.getBoolean("$ref.lost", false) == true

    fun last4(ref: String): String? = prefs()?.getString("$ref.last4", null)

    fun addedAt(ref: String): Long? = prefs()?.getLong("$ref.added", 0L)?.takeIf { it > 0 }

    fun has(ref: String): Boolean = last4(ref) != null

    /** Расшифровать ключ на время [block] — только для запросов к провайдеру из приложения. */
    suspend fun <T> use(ref: String, block: suspend (String) -> T): T? {
        val enc = prefs()?.getString("$ref.enc", null) ?: return null
        val key = runCatching {
            val (iv, data) = enc.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv)) }
            String(c.doFinal(data))
        }.getOrNull()
        if (key == null) {
            // Ключ Keystore потерян (сброс блокировки экрана, перенос на другой телефон): копия бесполезна — убрать её
            // и запомнить, что ключ нужно ввести заново, а не молчать (G-36).
            delete(ref)
            prefs()?.edit()?.putBoolean("$ref.lost", true)?.apply()
            return null
        }
        return block(key)
    }

    fun delete(ref: String) {
        prefs()?.edit()?.remove("$ref.last4")?.remove("$ref.added")?.remove("$ref.enc")?.apply()
    }

    fun clearAll() { prefs()?.edit()?.clear()?.apply() }
}
