package com.dsh.feature.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dsh.core.data.DshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Local theme is immediately usable offline; Host synchronization never rolls back a user choice. */
object HostTheme {
    private var selectedPreference by mutableStateOf("system")
    val preference: String get() = selectedPreference
    var fontSize by mutableStateOf<Int?>(null)
        private set
    val syncErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var prefs: SharedPreferences? = null
    private var pending = false
    private val mutex = Mutex()
    private var synchronizer: (() -> Unit)? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("dsh_theme", Context.MODE_PRIVATE)
        selectedPreference = prefs?.getString("preference", "system")?.takeIf { it in VALUES } ?: "system"
        pending = prefs?.getBoolean("pending", false) == true
    }

    fun wire(repository: DshRepository, hostId: String, scope: CoroutineScope) {
        synchronizer = { scope.launch {
            mutex.withLock {
                val doc = runCatching { repository.hostSettings.namespace(hostId, NS) }.getOrNull()
                if (doc == null) {
                    if (pending) syncErrors.tryEmit(Unit)
                    return@withLock
                }
                fontSize = (doc.value["fontSize"] as? JsonPrimitive)?.intOrNull
                if (pending) {
                    val chosen = preference
                    val ok = runCatching {
                        repository.hostSettings.setSettings(hostId, NS, listOf(listOf("preference") to JsonPrimitive(chosen)), doc.revision)
                    }.getOrDefault(false)
                    if (ok && preference == chosen) {
                        pending = false
                        prefs?.edit()?.putBoolean("pending", false)?.apply()
                    } else if (!ok) syncErrors.tryEmit(Unit)
                } else {
                    val remote = (doc.value["preference"] as? JsonPrimitive)?.contentOrNull
                    if (remote in VALUES) {
                        selectedPreference = remote!!
                        prefs?.edit()?.putString("preference", preference)?.apply()
                    }
                }
            }
        } }
        refresh()
    }

    fun refresh() { synchronizer?.invoke() }
    fun setPreference(value: String) {
        require(value in VALUES)
        selectedPreference = value
        pending = true
        prefs?.edit()?.putString("preference", value)?.putBoolean("pending", true)?.apply()
        refresh()
    }
    private val VALUES = setOf("light", "dark", "system")
    private const val NS = "ui-theme"
}
