package com.deepseek.dsh.runtime.host

/**
 * Разбор строки версии rootfs (единственный источник правды).
 *
 * Строка из трёх частей через `-`:
 *
 * ```
 * <rootfs>-dsh<dsh>-bridge<bridge>
 * 1.1.0-dsh0.2.1-rc.2-bridge1.1.0
 * ```
 *
 * Смысл частей разный, поэтому «нужно ли обновление» решается по частям, а не по всей строке.
 */
data class RootfsVersion(
    /** Семантическая версия самого rootfs, например `1.1.0`. */
    val rootfs: String,
    /** Версия вложенного движка DSH, например `0.2.1-rc.2`; в старых строках нет — null. */
    val dsh: String?,
    /** Версия вложенного bridge-плагина, например `1.1.0`; в старых строках нет — null. */
    val bridge: String?,
) {
    /** Собрать обратно в вид протокола — для журнала и показа. */
    fun encode(): String = buildString {
        append(rootfs)
        dsh?.let { append("-dsh").append(it) }
        bridge?.let { append("-bridge").append(it) }
    }

    companion object {
        /** Часть `-dsh<version>`, сразу после части rootfs. */
        private val DSH_SEGMENT = Regex("""-dsh(\d[^-]*(?:-[^-]+)*?)(?=-bridge|$)""")
        /** Часть `-bridge<version>` всегда в конце. */
        private val BRIDGE_SEGMENT = Regex("""-bridge(\S+)$""")

        /**
         * Разобрать строку версии. Незнакомый вид — «вся строка — rootfs, остальное неизвестно»:
         * при сравнении это даёт «обязательное обновление», ошибка разбора не превращается в «обновлять не нужно».
         * @param raw `runtimeVersion` из манифеста или метки установки.
         * @return результат; пустая строка — null.
         */
        fun parse(raw: String?): RootfsVersion? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val bridgeMatch = BRIDGE_SEGMENT.find(text)
            val bridge = bridgeMatch?.groupValues?.get(1)
            val withoutBridge = if (bridgeMatch != null) text.removeRange(bridgeMatch.range) else text
            val dshMatch = DSH_SEGMENT.find(withoutBridge)
            val dsh = dshMatch?.groupValues?.get(1)
            val rootfs = if (dshMatch != null) withoutBridge.removeRange(dshMatch.range) else withoutBridge
            return RootfsVersion(
                rootfs = rootfs.ifBlank { withoutBridge },
                dsh = dsh?.takeIf { it.isNotBlank() },
                bridge = bridge?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * Характер обновления **образа** — чтобы запуск решил, стоит ли прерывать пользователя.
 *
 * Решают **часть dsh** и **часть rootfs**, но с разной настойчивостью:
 *
 * - изменилась часть dsh → [MANDATORY]: сменился движок, патчи окружения в старом образе могут не подойти,
 *   образ нужно переустановить.
 * - dsh та же, rootfs изменилась → [REPACKAGED]: образ пересобран. Возможно, чтобы вложить новый
 *   плагин (его уже можно обновить на месте), а возможно, ради **исправления, которое есть только в образе**
 *   (например, нативные зависимости или ошибка сборки). Запуск не останавливается, в настройках — необязательный вход,
 *   чтобы пользователь сам поставил исправленный образ.
 * - обе части те же → [NONE].
 *
 * Раньше часть rootfs здесь не учитывалась вовсе, и у исправлений уровня образа не было пути к пользователю.
 * Теперь: полоса при запуске — только для [MANDATORY], пересобранный образ — только в Настройки → Обновления.
 */
enum class RootfsUpdateKind {
    /** Переустанавливать образ не нужно. */
    NONE,

    /** Движок тот же, образ пересобран: необязательное обновление в настройках, запуск не прерывается. */
    REPACKAGED,

    /** Образ обязательно переустановить: сменился движок dsh. */
    MANDATORY,
}

/**
 * Нужно ли переустановить образ. Это **единственная реализация** решения: и запуск, и настройки идут сюда.
 *
 * «Обязательно» решает только часть dsh; при той же dsh и выросшей части rootfs — [REPACKAGED],
 * необязательное обновление в настройках. Не удалось разобрать любую сторону — [RootfsUpdateKind.MANDATORY]:
 * лучше спросить лишний раз, чем из-за незнакомого формата решить «уже последняя» и оставить пользователя
 * с неработающим окружением.
 * @param installed строка установленной версии; null — не установлено или не прочитать.
 * @param available строка доступной версии; null — манифеста нет.
 * @return характер обновления образа.
 */
fun decideRootfsUpdateKind(
    installed: String?,
    available: String?,
    installedPatched: Boolean = false,
    availablePatched: Boolean = false,
): RootfsUpdateKind {
    if (installed == null || available == null) return RootfsUpdateKind.NONE
    val local = RootfsVersion.parse(installed) ?: return RootfsUpdateKind.MANDATORY
    val remote = RootfsVersion.parse(available) ?: return RootfsUpdateKind.MANDATORY
    // Образ со вшитыми на сборке патчами Android не привязан к части DSH: внутри «последний
    // DSH + патчи», смена движка не требует полнофайловых патчей APK. Обновление — только по части rootfs.
    if (availablePatched) {
        val order = comparePluginVersions(remote.rootfs, local.rootfs)
        if (order != null && order <= 0) return RootfsUpdateKind.NONE
        return if (local.rootfs != remote.rootfs) RootfsUpdateKind.REPACKAGED else RootfsUpdateKind.NONE
    }
    // Установлен новый образ со вшитыми патчами, а удалённо есть только старый без них: не откатываться к старой модели.
    if (installedPatched) return RootfsUpdateKind.NONE
    // Несовпадение движка важнее «образ старее»: патчи APK заменяют файлы целиком по версии движка,
    // и даже если пользователь поставил более новый образ (например, первым получил rc.3), при несовпадении движка
    // нужно вернуть образ, соответствующий этому APK, а не оставлять как есть из-за большей части rootfs.
    if (local.dsh != remote.dsh) return RootfsUpdateKind.MANDATORY
    // Запасной офлайн-манифест может быть старее установленного образа (встроенный в assets не обновляется с выпусками):
    // старая версия не должна считаться обновлением, иначе пользователя поведут на откат с загрузкой 300 МБ.
    val rootfsOrder = comparePluginVersions(remote.rootfs, local.rootfs)
    if (rootfsOrder != null && rootfsOrder < 0) return RootfsUpdateKind.NONE
    return when {
        local.rootfs != remote.rootfs -> RootfsUpdateKind.REPACKAGED
        else -> RootfsUpdateKind.NONE
    }
}

/**
 * Какое обновление показать пользователю в настройках.
 *
 * В отличие от [RootfsUpdateKind], который отвечает «переустанавливать ли образ», здесь ответ
 * «что делать пользователю», и явно разделён приоритет двух путей:
 *
 * - **Плагин — обязательное обновление.** Это вторая сторона контракта клиента: Host и приложение должны говорить
 *   на одном протоколе, отставший плагин — нехватка функций. Но стоит это мало (десятки КБ исходников), поэтому
 *   «обязательно» значит подсказать и провести через обновление, а не заставить качать весь пакет.
 * - **Образ целиком — необязательно.** Старый образ работает; навязывать загрузку сотен МБ не нужно.
 *
 * Плагин важнее образа: сначала закрыть контракт, потом думать о смене носителя.
 */
enum class UpdateNeed {
    /** Обновление не нужно. */
    NONE,

    /** Плагин отстал: обязательно, через «Обновить плагин» (установка на месте в rootfs). */
    PLUGIN_REQUIRED,

    /** Образ пересобран: необязательно, загрузка целиком. */
    ROOTFS_OPTIONAL,
}

/**
 * Полный вывод проверки обновлений.
 *
 * @param need какое обновление нужно.
 * @param installed строка установленной версии.
 * @param available строка доступной версии (`runtimeVersion` из манифеста rootfs).
 * @param latestPlugin последний выпуск в репозитории плагина; не узнали — null.
 * @param installedPlugin действующая версия плагина (сначала работающая версия от Host,
 *   затем записанная на диске в rootfs); не прочитать — null.
 */
data class UpdateAssessment(
    val need: UpdateNeed = UpdateNeed.NONE,
    val installed: String? = null,
    val available: String? = null,
    val latestPlugin: String? = null,
    val installedPlugin: String? = null,
) {
    /** Нужно ли подсказать пользователю. */
    val hasUpdate: Boolean get() = need != UpdateNeed.NONE

    /**
     * Отстал ли плагин от последнего выпуска.
     *
     * Только если последняя версия известна и установленная действительно старее (или неизвестна); если установленная
     * новее последнего выпуска (например, неопубликованная сборка разработки), это не отставание, и откат запрещён.
     */
    val pluginOutdated: Boolean get() = installed != null &&
        isPluginBehind(installedPlugin, latestPlugin)

    /**
     * Доступна ли кнопка «Обновить плагин».
     *
     * Только когда rootfs установлен, последняя версия известна и установленная действительно отстаёт:
     * при той же версии, более новой установленной или без сети нажатие ни к чему
     * (поставило бы не то или просто упало).
     */
    val canUpdatePlugin: Boolean get() = pluginOutdated
}

/**
 * Сравнить версии плагина (`1.24.0` и `1.1.0`).
 *
 * Только `x.y.z` из трёх частей, по числам; предварительная версия с `-rc.1` и т. п. меньше выпуска
 * того же номера. Пусто или не три части — null: вызывающий переходит к осторожному «отличается —
 * значит отстала», а не считает незнакомую версию последней.
 * @param left левая версия.
 * @param right правая версия.
 * @return отрицательное — left старее, 0 — равны, положительное — left новее; несравнимы — null.
 */
fun comparePluginVersions(left: String?, right: String?): Int? {
    fun parse(raw: String?): Pair<List<Int>, Boolean>? {
        val text = raw?.trim()?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: return null
        val numeric = text.substringBefore('-').substringBefore('+')
        val segments = numeric.split('.')
        if (segments.size != 3) return null
        val numbers = segments.map { it.toIntOrNull() ?: return null }
        return numbers to text.contains('-')
    }
    val a = parse(left) ?: return null
    val b = parse(right) ?: return null
    for (index in 0..2) {
        val difference = a.first[index].compareTo(b.first[index])
        if (difference != 0) return difference
    }
    return when {
        a.second == b.second -> 0
        a.second -> -1
        else -> 1
    }
}

/**
 * Отстала ли установленная версия от последнего выпуска.
 *
 * Разбираемые версии сравниваются по числам (поэтому `1.24.0` не считается отставшей от `1.1.0`
 * только из-за разных строк); неразбираемые — «отличается — значит отстала»: лучше подсказать лишний раз, чем принять
 * незнакомую версию за последнюю и навсегда оставить вторую сторону контракта на старом протоколе.
 * @param installed установленная версия; null — не прочитать.
 * @param latest последний выпуск; null — не узнали.
 * @return нужно ли обновление.
 */
fun isPluginBehind(installed: String?, latest: String?): Boolean {
    if (latest.isNullOrBlank()) return false
    if (installed.isNullOrBlank()) return true
    val comparison = comparePluginVersions(latest, installed)
    return if (comparison != null) comparison > 0 else latest.trim() != installed.trim()
}

/**
 * Сравнить части версий и вывести решение об обновлении.
 *
 * Правила:
 * 1. Плагин (`latestPlugin` против действующей версии): **последний выпуск действительно новее →
 *    PLUGIN_REQUIRED**. Сравнение по числам; если установленная новее последнего выпуска — не отставание.
 *    Действующая версия — работающая по данным Host, если не прочитать — записанная в профиле;
 *    «вложено в образ» не считается — это лишь запись сборки.
 * 2. Образ (сменилась часть dsh или rootfs): **ROOTFS_OPTIONAL**, только подсказка в настройках,
 *    запуск не прерывается. Смена dsh — сменился движок; смена rootfs — образ пересобран,
 *    возможно, с исправлением, которое есть только в образе.
 *
 * Плагин важнее образа: плагин — вторая сторона контракта, образ — лишь носитель.
 *
 * @param installed строка версии из метки установки; null — rootfs не установлен.
 * @param available строка версии из манифеста rootfs; null — манифеста нет.
 * @param latestPlugin последний выпуск репозитория плагина; null — не узнавали или ошибка.
 * @param installedPlugin действующая версия плагина; null — ни работающей, ни записанной не прочитать.
 */
fun assessUpdate(
    installed: String?,
    available: String?,
    latestPlugin: String?,
    installedPlugin: String?,
    installedPatched: Boolean = false,
    availablePatched: Boolean = false,
): UpdateAssessment {
    // Без установки «обновления» нет: первый запуск поставит образ и вложенный плагин.
    // Сообщение «плагин отстал» здесь лишь показало бы новому пользователю невыполнимую подсказку до установки.
    if (installed == null) {
        return UpdateAssessment(
            need = UpdateNeed.NONE,
            installed = null,
            available = available,
            latestPlugin = latestPlugin,
            installedPlugin = installedPlugin,
        )
    }

    // 1. Плагин: только действующая версия (работающая по данным Host или записанная
    //    в rootfs). Вложенная в образ часть `-bridge` — лишь запись сборки, «установленной» она не считается:
    //    дом плагина — профиль, и «вложено в образ» не значит «есть сейчас».
    if (latestPlugin != null) {
        if (isPluginBehind(installedPlugin, latestPlugin)) {
            return UpdateAssessment(
                need = UpdateNeed.PLUGIN_REQUIRED,
                installed = installed,
                available = available,
                latestPlugin = latestPlugin,
                installedPlugin = installedPlugin,
            )
        }
    }

    // 2. Образ: сменился движок dsh — загрузить весь пакет заново; движок тот же, а образ пересобран — тоже
    //    необязательное обновление: пересборка может вложить новый плагин или нести исправление, которое
    //    есть только в образе (нативные зависимости, ошибка сборки), но прерывать запуск ради этого нельзя.
    if (decideRootfsUpdateKind(installed, available, installedPatched, availablePatched) != RootfsUpdateKind.NONE) {
        return UpdateAssessment(
            need = UpdateNeed.ROOTFS_OPTIONAL,
            installed = installed,
            available = available,
            latestPlugin = latestPlugin,
            installedPlugin = installedPlugin,
        )
    }

    return UpdateAssessment(
        need = UpdateNeed.NONE,
        installed = installed,
        available = available,
        latestPlugin = latestPlugin,
        installedPlugin = installedPlugin,
    )
}
