package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор строки версии на части и характер обновления.
 *
 * Главный вывод этого уровня — «плагин обязателен, образ необязателен», поэтому закреплена каждая ветка: отставший плагин
 * обязан давать `PLUGIN_REQUIRED`, пересобранный образ — только `ROOTFS_OPTIONAL`.
 */
class RootfsVersionTest {

    private val current = "1.1.0-dsh0.2.1-rc.2-bridge1.1.0"

    @Test
    fun `parses the three-segment form`() {
        val version = RootfsVersion.parse(current)!!
        assertEquals("1.1.0", version.rootfs)
        assertEquals("0.2.1-rc.2", version.dsh)
        assertEquals("1.1.0", version.bridge)
        assertEquals(current, version.encode())
    }

    @Test
    fun `parses the legacy two-segment form with an unknown bridge`() {
        val version = RootfsVersion.parse("1.0.0-dsh0.2.1-rc.2")!!
        assertEquals("1.0.0", version.rootfs)
        assertEquals("0.2.1-rc.2", version.dsh)
        assertNull(version.bridge)
    }

    @Test
    fun `parses a bare rootfs version`() {
        val version = RootfsVersion.parse("1.0.0")!!
        assertEquals("1.0.0", version.rootfs)
        assertNull(version.dsh)
        assertNull(version.bridge)
    }

    @Test
    fun `an unparsable string keeps its whole text as the rootfs segment`() {
        assertEquals("weird", RootfsVersion.parse("weird")!!.rootfs)
    }

    @Test
    fun `an empty or null version is absent`() {
        assertNull(RootfsVersion.parse(null))
        assertNull(RootfsVersion.parse(""))
        assertNull(RootfsVersion.parse("   "))
    }

    @Test
    fun `everything current needs nothing`() {
        val assessment = assessUpdate(current, current, "1.1.0", "1.1.0")
        assertEquals(UpdateNeed.NONE, assessment.need)
        assertFalse(assessment.hasUpdate)
    }

    @Test
    fun `an identical install never asks for anything`() {
        // Регрессия: полоса при запуске сравнивала строки и даже при той же версии показывала «Доступно обновление окружения».
        // Решение — только через assessUpdate, при той же версии всегда NONE.
        for (manifest in listOf("1.0.1-dsh0.2.1-rc.2-bridge1.1.0", "1.1.0-dsh0.2.1-rc.2-bridge1.1.0")) {
            val assessment = assessUpdate(manifest, manifest, "1.1.0", "1.1.0")
            assertEquals("same manifest must be NONE: $manifest", UpdateNeed.NONE, assessment.need)
            assertFalse("same manifest must not report an update: $manifest", assessment.hasUpdate)
        }
    }

    @Test
    fun `the mandatory image update is the only kind shown at startup`() {
        // Полоса при запуске знает только MANDATORY; если отстал лишь плагин, она NONE — при запуске подсказки нет.
        assertEquals(
            RootfsUpdateKind.MANDATORY,
            decideRootfsUpdateKind("1.0.0-dsh0.2.1-rc.1-bridge1.1.0", "1.1.0-dsh0.2.1-rc.2-bridge1.1.0"),
        )
        assertEquals(
            RootfsUpdateKind.NONE,
            decideRootfsUpdateKind("1.1.0-dsh0.2.1-rc.2-bridge1.0.0", "1.1.0-dsh0.2.1-rc.2-bridge1.1.0"),
        )
    }

    @Test
    fun `a rebuilt image with the same engine is not a mandatory update`() {
        // Случилось на эмуляторе: 1.0.0-dsh0.2.1-rc.2 (старый образ без плагина) обновился до
        // 1.0.1-dsh0.2.1-rc.2-bridge1.1.0, часть dsh та же, но при запуске появилось
        // «Скачать окружение заново». Пересобранный образ — только REPACKAGED: необязательно в настройках,
        // запуск (знает только MANDATORY) не останавливается.
        assertEquals(
            RootfsUpdateKind.REPACKAGED,
            decideRootfsUpdateKind("1.0.0-dsh0.2.1-rc.2", "1.0.1-dsh0.2.1-rc.2-bridge1.1.0"),
        )
    }

    @Test
    fun `a patched image ignores the dsh segment`() {
        // Образ со вшитыми при сборке патчами внутри «последний DSH + патчи», годятся и rc.3, и rc.2;
        // необязательное обновление — только когда выросла часть rootfs.
        assertEquals(
            RootfsUpdateKind.NONE,
            decideRootfsUpdateKind(
                "1.0.12-dsh0.2.1-rc.3-bridge2.0.7",
                "1.0.12-dsh0.2.1-rc.3-bridge2.0.7",
                installedPatched = true,
                availablePatched = true,
            ),
        )
        assertEquals(
            RootfsUpdateKind.REPACKAGED,
            decideRootfsUpdateKind(
                "1.0.12-dsh0.2.1-rc.3-bridge2.0.7",
                "1.0.13-dsh0.2.1-rc.4-bridge2.0.7",
                installedPatched = true,
                availablePatched = true,
            ),
        )
        // Установлен образ с патчами, удалённо — только старый без патчей: к откату не ведём.
        assertEquals(
            RootfsUpdateKind.NONE,
            decideRootfsUpdateKind(
                "1.0.13-dsh0.2.1-rc.4-bridge2.0.7",
                "1.0.12-dsh0.2.1-rc.3-bridge2.0.7",
                installedPatched = true,
                availablePatched = false,
            ),
        )
        val assessment = assessUpdate(
            installed = "1.0.12-dsh0.2.1-rc.3-bridge2.0.7",
            available = "1.0.13-dsh0.2.1-rc.4-bridge2.0.7",
            latestPlugin = null,
            installedPlugin = null,
            installedPatched = true,
            availablePatched = true,
        )
        assertEquals(UpdateNeed.ROOTFS_OPTIONAL, assessment.need)
    }

    @Test
    fun `an engine mismatch wins over a newer rootfs segment`() {
        // Пользователь первым получил образ 1.0.12 с rc.3, а цель патчей текущего APK — rc.2:
        // хотя 1.0.12 > 1.0.11, нужно вернуть образ под rc.2, а не оставаться на дереве со смешанными версиями.
        assertEquals(
            RootfsUpdateKind.MANDATORY,
            decideRootfsUpdateKind(
                "1.0.12-dsh0.2.1-rc.3-bridge2.0.7",
                "1.0.11-dsh0.2.1-rc.2-bridge2.0.7",
            ),
        )
    }

    @Test
    fun `an older bundled manifest never offers a downgrade`() {
        // Встроенный запасной манифест (assets/rootfs-manifest.json) с выпусками не обновляется; без сети он
        // может быть старее установленного образа. Старая версия не должна считаться обновлением, иначе поведёт к откату.
        assertEquals(
            RootfsUpdateKind.NONE,
            decideRootfsUpdateKind("1.0.9-dsh0.2.1-rc.2-bridge2.0.1", "1.0.4-dsh0.2.1-rc.2-bridge1.3.1"),
        )
    }

    @Test
    fun `an engine change is mandatory even when the rootfs segment matches`() {
        assertEquals(
            RootfsUpdateKind.MANDATORY,
            decideRootfsUpdateKind("1.0.1-dsh0.2.1-rc.1-bridge1.1.0", "1.0.1-dsh0.2.1-rc.2-bridge1.1.0"),
        )
    }

    @Test
    fun `an uninstalled rootfs is not an update`() {
        // Без установки этим занимается первый запуск, сообщать об обновлении здесь не надо.
        assertEquals(UpdateNeed.NONE, assessUpdate(null, current, "1.1.0", null).need)
    }

    @Test
    fun `an installed plugin newer than the image needs nothing`() {
        // Пользователь уже отдельно обновил плагин: старая версия, вложенная в образ, больше не значит «отстал».
        val assessment = assessUpdate(
            installed = "1.1.0-dsh0.2.1-rc.2-bridge1.0.0",
            available = "1.1.0-dsh0.2.1-rc.2-bridge1.0.0",
            latestPlugin = "1.1.0",
            installedPlugin = "1.1.0",
        )
        assertEquals(UpdateNeed.NONE, assessment.need)
    }

    @Test
    fun `a plugin behind the latest release is required`() {
        val assessment = assessUpdate(
            installed = "1.0.0-dsh0.2.1-rc.2-bridge1.0.0",
            available = "1.0.0-dsh0.2.1-rc.2-bridge1.0.0",
            latestPlugin = "1.1.0",
            installedPlugin = "1.0.0",
        )
        assertEquals(UpdateNeed.PLUGIN_REQUIRED, assessment.need)
        assertTrue(assessment.hasUpdate)
        assertTrue(assessment.canUpdatePlugin)
    }

    @Test
    fun `a plugin packaged older than the image is required even before the image lands`() {
        // Образ уже указывает на новый плагин, а локально старый: сначала как обязательное обновление плагина.
        val assessment = assessUpdate(
            installed = "1.0.0-dsh0.2.1-rc.2-bridge1.0.0",
            available = current,
            latestPlugin = "1.1.0",
            installedPlugin = "1.0.0",
        )
        assertEquals(UpdateNeed.PLUGIN_REQUIRED, assessment.need)
    }

    @Test
    fun `an unknown installed plugin is never assumed current`() {
        // Если действующую версию не прочитать, версия, вложенная в образ, не считается «установленной»:
        // файл могли удалить или заменить — безопаснее считать отставшим и один раз подсказать обновление.
        val assessment = assessUpdate(
            installed = "1.0.0-dsh0.2.1-rc.2-bridge1.0.0",
            available = current,
            latestPlugin = "1.1.0",
            installedPlugin = null,
        )
        assertEquals(UpdateNeed.PLUGIN_REQUIRED, assessment.need)
        assertNull(assessment.installedPlugin)
        assertTrue(assessment.canUpdatePlugin)
    }

    @Test
    fun `an engine bump is an optional image update even when the plugin is current`() {
        val assessment = assessUpdate(
            installed = "1.0.0-dsh0.2.1-rc.1-bridge1.1.0",
            available = "1.1.0-dsh0.2.1-rc.2-bridge1.1.0",
            latestPlugin = "1.1.0",
            installedPlugin = "1.1.0",
        )
        assertEquals(UpdateNeed.ROOTFS_OPTIONAL, assessment.need)
        assertTrue(assessment.hasUpdate)
        // Необязательное обновление образа не должно показывать кнопку «Обновить плагин».
        assertFalse(assessment.canUpdatePlugin)
    }

    @Test
    fun `an engine bump alone is an optional image update`() {
        val assessment = assessUpdate(
            installed = "1.1.0-dsh0.2.1-rc.1-bridge1.1.0",
            available = "1.1.0-dsh0.2.1-rc.2-bridge1.1.0",
            latestPlugin = "1.1.0",
            installedPlugin = "1.1.0",
        )
        assertEquals(UpdateNeed.ROOTFS_OPTIONAL, assessment.need)
    }

    @Test
    fun `a repackaged image with the same engine offers an optional image update`() {
        // Выросла часть rootfs — образ пересобран: возможно, лишь вложен новый плагин, а возможно,
        // есть исправление, которое бывает только в образе. Плагин уже последний, поэтому только необязательное обновление образа
        // и без остановки запуска.
        val assessment = assessUpdate(
            installed = "1.0.0-dsh0.2.1-rc.2-bridge1.1.0",
            available = "1.0.1-dsh0.2.1-rc.2-bridge1.1.0",
            latestPlugin = "1.1.0",
            installedPlugin = "1.1.0",
        )
        assertEquals(UpdateNeed.ROOTFS_OPTIONAL, assessment.need)
        assertTrue(assessment.hasUpdate)
        assertFalse(assessment.canUpdatePlugin)
    }

    @Test
    fun `an image that only differs by its packaged plugin needs nothing once the plugin is current`() {
        // В образе 1.0.0, но пользователь уже обновил плагин до 1.1.0: качать весь пакет не нужно.
        val assessment = assessUpdate(
            installed = "1.1.0-dsh0.2.1-rc.2-bridge1.0.0",
            available = current,
            latestPlugin = "1.1.0",
            installedPlugin = "1.1.0",
        )
        assertEquals(UpdateNeed.NONE, assessment.need)
    }

    @Test
    fun `without a latest-plugin answer the plugin is not judged`() {
        // При ошибке запроса нельзя по части образа заключить, что плагин отстал, — будет ложное сообщение. Часть образа
        // тоже совпадает, поэтому нет ни обновления плагина, ни обновления образа.
        val assessment = assessUpdate(
            installed = "1.1.0-dsh0.2.1-rc.2-bridge1.0.0",
            available = current,
            latestPlugin = null,
            installedPlugin = "1.0.0",
        )
        assertEquals(UpdateNeed.NONE, assessment.need)
    }

    @Test
    fun `a legacy install without a bridge segment is required on the plugin side`() {
        val assessment = assessUpdate(
            installed = "1.0.0-dsh0.2.1-rc.2",
            available = current,
            latestPlugin = "1.1.0",
            installedPlugin = null,
        )
        assertEquals(UpdateNeed.PLUGIN_REQUIRED, assessment.need)
    }

    @Test
    fun `plugin versions compare numerically rather than as text`() {
        // Регрессия: из-за неравенства строк 1.24.0 считалась отставшей от 1.1.0, и «обновление» было откатом.
        assertEquals(1, comparePluginVersions("1.24.0", "1.1.0"))
        assertEquals(-1, comparePluginVersions("1.1.0", "1.24.0"))
        assertEquals(1, comparePluginVersions("1.10.0", "1.9.9"))
        assertEquals(0, comparePluginVersions("1.1.0", "1.1.0"))
        assertEquals(0, comparePluginVersions("v1.1.0", "1.1.0"))
        assertEquals(-1, comparePluginVersions("1.1.0-rc.1", "1.1.0"))
        assertNull(comparePluginVersions("1.1", "1.1.0"))
        assertNull(comparePluginVersions(null, "1.1.0"))
    }

    @Test
    fun `a newer installed plugin is never a downgrade target`() {
        // Локально неопубликованная сборка разработки (1.24.0), последний выпуск в репозитории — 1.1.0:
        // это не отставание, и кнопка не должна стать «Обновить» (это откат). Сам образ пересобран,
        // поэтому допустимо только необязательное обновление образа.
        val assessment = assessUpdate(
            installed = "1.0.0-dsh0.2.1-rc.2-bridge1.1.0",
            available = current,
            latestPlugin = "1.1.0",
            installedPlugin = "1.24.0",
        )
        assertEquals(UpdateNeed.ROOTFS_OPTIONAL, assessment.need)
        assertFalse(assessment.pluginOutdated)
        assertFalse(assessment.canUpdatePlugin)
    }

    @Test
    fun `a newer published release still counts as an update`() {
        val assessment = assessUpdate(
            installed = current,
            available = current,
            latestPlugin = "1.2.0",
            installedPlugin = "1.1.0",
        )
        assertEquals(UpdateNeed.PLUGIN_REQUIRED, assessment.need)
        assertTrue(assessment.pluginOutdated)
        assertTrue(assessment.canUpdatePlugin)
    }

    @Test
    fun `without a latest release the update action stays disabled`() {
        // Без сети нажатие только упадёт; интерфейс остаётся «нельзя определить», кнопка недоступна.
        val assessment = assessUpdate(
            installed = current,
            available = current,
            latestPlugin = null,
            installedPlugin = "1.0.0",
        )
        assertFalse(assessment.pluginOutdated)
        assertFalse(assessment.canUpdatePlugin)
    }

    @Test
    fun `an unknown installed version can still be updated when the latest release is known`() {
        // В старом образе нет части `-bridge`, и плагина на диске нет: определить нельзя, считаем «отстал» —
        // одно обновление — самый безопасный путь к согласованному состоянию.
        val assessment = assessUpdate(
            installed = "1.0.0-dsh0.2.1-rc.2",
            available = current,
            latestPlugin = "1.1.0",
            installedPlugin = null,
        )
        assertEquals(UpdateNeed.PLUGIN_REQUIRED, assessment.need)
        assertTrue(assessment.pluginOutdated)
        assertTrue(assessment.canUpdatePlugin)
    }

    @Test
    fun `dsh compatibility rejects a rootfs built for another engine`() {
        val expected = "0.2.1-rc.2"
        assertTrue(RootfsNaming.isDshCompatible("1.0.11-dsh0.2.1-rc.2-bridge2.0.7", expected))
        assertFalse(RootfsNaming.isDshCompatible("1.0.12-dsh0.2.1-rc.3-bridge2.0.7", expected))
        assertFalse(RootfsNaming.isDshCompatible("1.0.0-dsh0.2.1-rc.2", "0.2.1-rc.3"))
        // Если разобрать или решить нельзя — совместимостью не притворяемся.
        assertFalse(RootfsNaming.isDshCompatible("weird", expected))
        assertFalse(RootfsNaming.isDshCompatible(null, expected))
        // Без встроенного манифеста — прежнее поведение (решить нельзя — пропускаем).
        assertTrue(RootfsNaming.isDshCompatible("1.0.12-dsh0.2.1-rc.3-bridge2.0.7", null))
        assertEquals("0.2.1-rc.2", RootfsNaming.dshVersion("1.0.11-dsh0.2.1-rc.2-bridge2.0.7"))
        assertNull(RootfsNaming.dshVersion("weird"))
    }
}
