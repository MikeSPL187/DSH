package com.dsh.runtime.host

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Куда агенту можно ходить в сеть (G-50, гигиена; решение владельца). По умолчанию — только нужное для работы:
 * провайдеры моделей, реестры пакетов (npm, PyPI), GitHub/GitLab, зеркала Debian, модели речи; плюс домены,
 * объявленные установленными плагинами Host, адреса настроенных провайдеров и разрешённые пользователем.
 * «Полный доступ к сети» снимает ограничение. Проверяет [AgentNetworkProxy]; это не граница прав.
 */
object AgentNetworkPolicy {
    private const val PREFS = "dsh_agent_network"
    private const val KEY_FULL = "full_access"
    private const val KEY_ALLOWED = "allowed_hosts"
    private const val MAX_RECENT_BLOCKED = 20

    @Volatile
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext ?: context
    }

    /** Нужное для работы агента: провайдер по умолчанию, пакеты, git-хостинги, Debian, модели речи. */
    val BASE_HOSTS: Set<String> = setOf(
        "deepseek.com",
        "npmjs.org", "npmjs.com", "yarnpkg.com", "npmmirror.com",
        "pypi.org", "pythonhosted.org",
        "github.com", "githubusercontent.com", "gitlab.com", "bitbucket.org", "codeberg.org",
        "debian.org",
        "huggingface.co", "hf.co", "hf-mirror.com",
    )

    /** Домены из плагинов Host и адреса провайдеров моделей — обновляются при запуске Host и чтении настроек. */
    @Volatile
    var dynamicHosts: Set<String> = emptySet()
        private set

    private val _recentBlocked = MutableStateFlow<List<String>>(emptyList())

    /** Недавно не пропущенные адреса — в «Настройки → Агент», с кнопкой «Разрешить». */
    val recentBlocked: StateFlow<List<String>> = _recentBlocked.asStateFlow()

    private val _changes = MutableStateFlow(0)

    /** Растёт при каждом изменении настроек — экран настроек перечитывает их. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun isFullAccess(): Boolean = runCatching {
        prefs()!!.getBoolean(KEY_FULL, false)
    }.getOrDefault(false)

    fun setFullAccess(full: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_FULL, full)?.apply()
        _changes.value++
    }

    fun userHosts(): Set<String> = runCatching {
        prefs()!!.getStringSet(KEY_ALLOWED, emptySet()).orEmpty().toSet()
    }.getOrDefault(emptySet())

    fun allowHost(host: String) {
        val clean = host.lowercase().trim().trimEnd('.')
        if (clean.isEmpty()) return
        prefs()?.edit()?.putStringSet(KEY_ALLOWED, userHosts() + clean)?.apply()
        _recentBlocked.value = _recentBlocked.value - clean
        _changes.value++
    }

    fun removeHost(host: String) {
        prefs()?.edit()?.putStringSet(KEY_ALLOWED, userHosts() - host)?.apply()
        _changes.value++
    }

    /** Добавить домены из плагинов и адреса провайдеров (`https://host/...` или просто host). */
    fun addDynamic(hostsOrUrls: Collection<String>) {
        val hosts = hostsOrUrls.mapNotNull { hostOf(it) }.toSet()
        if (!dynamicHosts.containsAll(hosts)) dynamicHosts = dynamicHosts + hosts
    }

    fun isAllowed(host: String): Boolean =
        isFullAccess() || hostMatches(host, BASE_HOSTS + dynamicHosts + userHosts())

    fun noteBlocked(host: String) {
        _recentBlocked.value = (listOf(host) + (_recentBlocked.value - host)).take(MAX_RECENT_BLOCKED)
    }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Имя хоста из адреса или имени; не похоже на хост — null. */
fun hostOf(value: String): String? {
    val trimmed = value.trim()
    val host = if ("://" in trimmed) runCatching { java.net.URI(trimmed).host }.getOrNull() else trimmed.substringBefore('/').substringBefore(':')
    return host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() && Regex("""[a-z0-9.\-]+""").matches(it) && '.' in it }
}
