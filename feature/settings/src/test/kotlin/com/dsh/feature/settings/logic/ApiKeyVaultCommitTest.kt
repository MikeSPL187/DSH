package com.dsh.feature.settings.logic

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Fault injection: Android applies changes to memory before reporting disk-write failure. */
class ApiKeyVaultCommitTest {
    private fun failingPreferences(initial: Map<String, Any> = emptyMap()): SharedPreferences {
        val memory = initial.toMutableMap()
        fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString", "putLong", "putBoolean" -> { pending[args[0] as String] = args[1]; editor }
                    "remove" -> { pending[args[0] as String] = null; editor }
                    "commit" -> {
                        pending.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
                        false // Includes the rollback attempt: the filesystem can remain full.
                    }
                    else -> error("Unexpected editor operation ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> memory.toMap()
                "edit" -> editor()
                else -> error("Unexpected preference operation ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun failedReplacementRestoresExactOldCipherAndMetadataEvenIfRollbackAlsoReportsFailure() {
        val initial = mapOf("key.enc" to "opaque-old-cipher", "key.last4" to "old4", "key.added" to 123L, "key.lost" to true)
        val p = failingPreferences(initial)
        assertFalse(ApiKeyVault.storeCipher(p, "key", "new4", "opaque-new-cipher", 456L))
        assertEquals(initial, p.all)
    }

    @Test fun failedFirstSaveLeavesNoPhantomKey() {
        val p = failingPreferences()
        assertFalse(ApiKeyVault.storeCipher(p, "key", "new4", "opaque-new-cipher", 456L))
        assertTrue(p.all.isEmpty())
    }
}
