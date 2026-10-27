package com.dsh.plugin.nativehost

import com.deepseek.dsh.plugin.nativesdk.pages.PageContent
import com.deepseek.dsh.plugin.nativesdk.pages.PageEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pages contributed by companion APKs that were successfully loaded in this process.
 *
 * A discovered APK is not enough to take over a Web settings section: its ABI/signature
 * checks and class loading must have succeeded, and it must have registered a page whose
 * id matches the Web `settings.section` id. The registry is replaced after every package
 * rescan so uninstalling or disabling a companion also removes its native route.
 */
data class NativePageRegistration(
    val pluginId: String,
    val jsPackage: String?,
    val pages: List<PageContent>,
)

object NativePageRegistry {
    private val mutableRegistrations = MutableStateFlow<List<NativePageRegistration>>(emptyList())

    /** Observed by Compose so a package install/uninstall updates an open settings page. */
    val registrations: StateFlow<List<NativePageRegistration>> = mutableRegistrations.asStateFlow()

    fun replaceAll(registrations: List<NativePageRegistration>) {
        mutableRegistrations.value = registrations
    }

    /** Resolve the exact Web component (`<package>#<settings.section id>`) to a native page. */
    fun find(componentId: String): PageContent? =
        find(componentId, mutableRegistrations.value)

    fun find(
        componentId: String,
        registrations: List<NativePageRegistration>,
    ): PageContent? {
        val separator = componentId.lastIndexOf('#')
        if (separator <= 0 || separator == componentId.lastIndex) return null
        val owner = componentId.substring(0, separator)
        val pageId = componentId.substring(separator + 1)

        return registrations.firstNotNullOfOrNull { registration ->
            if (!registration.owns(owner)) return@firstNotNullOfOrNull null
            registration.pages.firstOrNull { page ->
                page.entry.placement == PageEntry.Placement.SETTINGS && page.entry.id == pageId
            }
        }
    }

    /**
     * Сопоставить компонент `settings.section` с нативной страницей настроек компаньона мягче, чем [find].
     *
     * Точное совпадение `<package>#<id>` требует одинаковых id с обеих сторон; на практике id нативной
     * страницы автор называет по-своему (например, раздел веб-клиента `subscriptions`, нативная страница —
     * `providers`). Если точного совпадения нет, по порядку:
     *
     * 1. **Одинаковый заголовок**: тот же текст (в том числе смешанный, вроде «Подписки / Subscriptions» —
     *    без пробелов и разделителей один содержит другой);
     * 2. **Один к одному**: вызывающий подтвердил, что у плагина один `settings.section`, а компаньон
     *    зарегистрировал одну страницу настроек — это одна и та же страница.
     *
     * Не нашлось — [SettingsPageMatch.reason]: «компаньон не загружен / нет страницы настроек /
     * id не совпали (список зарегистрированных)» показывается пользователю, а не молча открывается веб-интерфейс.
     *
     * @param componentId компонент `<package>#<id>`.
     * @param title заголовок раздела настроек (на языке интерфейса).
     * @param registrations страницы загруженных компаньонов.
     * @param allowSinglePageFallback разрешить сопоставление один к одному (у плагина один раздел).
     */
    fun findSettingsSection(
        componentId: String,
        title: String?,
        registrations: List<NativePageRegistration>,
        allowSinglePageFallback: Boolean = false,
    ): SettingsPageMatch {
        find(componentId, registrations)?.let { return SettingsPageMatch(it) }
        val separator = componentId.lastIndexOf('#')
        if (separator <= 0 || separator == componentId.lastIndex) return SettingsPageMatch(null)
        val owner = componentId.substring(0, separator)
        val ownedRegistrations = registrations.filter { it.owns(owner) }
        val allOwnedPages = ownedRegistrations.flatMap { it.pages }
        val settingsPages = allOwnedPages
            .filter { it.entry.placement == PageEntry.Placement.SETTINGS }
        if (settingsPages.isEmpty()) {
            return SettingsPageMatch(
                page = null,
                reason = when {
                    ownedRegistrations.isEmpty() -> com.dsh.core.model.Tr.t("Native companion is not loaded or not paired ($owner)", "Нативный компаньон не загружен или не сопряжён ($owner)")
                    allOwnedPages.isEmpty() -> com.dsh.core.model.Tr.t("Native companion is loaded but registered no pages", "Нативный компаньон загружен, но не зарегистрировал страниц")
                    else -> com.dsh.core.model.Tr.t("Native companion has no SETTINGS page; registered ", "У нативного компаньона нет страницы SETTINGS; зарегистрированы ") +
                        allOwnedPages.joinToString { "${it.entry.placement}:${it.entry.id}" }
                },
            )
        }
        val wanted = normalizeLabel(title)
        if (wanted != null) {
            settingsPages.firstOrNull { normalizeLabel(it.entry.title) == wanted }?.let {
                return SettingsPageMatch(it)
            }
            settingsPages.firstOrNull {
                val candidate = normalizeLabel(it.entry.title)
                candidate != null && (candidate.contains(wanted) || wanted.contains(candidate))
            }?.let { return SettingsPageMatch(it) }
        }
        if (allowSinglePageFallback && settingsPages.size == 1) {
            return SettingsPageMatch(settingsPages.first())
        }
        val ids = settingsPages.joinToString(", ") { it.entry.id }
        return SettingsPageMatch(page = null, reason = com.dsh.core.model.Tr.t("No native page matched $componentId; registered [$ids]", "Нативная страница не найдена: $componentId; зарегистрированы [$ids]"))
    }

    /** Итог сопоставления: найденная нативная страница или причина для интерфейса. */
    data class SettingsPageMatch(
        val page: PageContent?,
        val reason: String? = null,
    )

    /** Текст в нижнем регистре без пробелов и обычных разделителей — для сравнения заголовков. */
    private fun normalizeLabel(raw: String?): String? = raw
        ?.replace(Regex("[\\s/·|,，、()（）\\[\\]]"), "")
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    private fun NativePageRegistration.owns(owner: String): Boolean {
        if (owner == pluginId || owner == jsPackage) return true
        val declared = jsPackage?.takeIf { it.isNotBlank() } ?: return false
        val candidates = buildSet {
            add(declared.substringBeforeLast('@'))
            add(declared.substringAfterLast('/').substringBefore('@'))
            if (declared.contains("://") || declared.startsWith("git+")) {
                // Адрес GitHub/Git: имя пакета — имя репозитория (без .git / #ref / ?query).
                val path = declared.substringBefore('#').substringBefore('?').trimEnd('/')
                add(path.substringAfterLast('/').removeSuffix(".git"))
            }
        }
        // Часто с одной стороны `dsh-plugin-x`, с другой просто `x` — сравнить ещё раз без известных префиксов.
        val ownerAliases = buildSet {
            add(owner)
            PACKAGE_PREFIXES.forEach { prefix ->
                if (owner.startsWith(prefix)) add(owner.removePrefix(prefix))
            }
        }
        return candidates.any { candidate ->
            if (candidate.isBlank()) return@any false
            val aliases = buildSet {
                add(candidate)
                PACKAGE_PREFIXES.forEach { prefix ->
                    if (candidate.startsWith(prefix)) add(candidate.removePrefix(prefix))
                }
            }
            ownerAliases.any { it in aliases }
        }
    }

    /** Частые префиксы имени пакета, не меняющие плагин. */
    private val PACKAGE_PREFIXES = listOf("dsh-plugin-", "dsh-")
}
