package com.dsh.plugin.nativehost

import com.deepseek.dsh.plugin.nativesdk.host.PluginHostApi
import com.deepseek.dsh.plugin.nativesdk.pages.PageContent
import com.deepseek.dsh.plugin.nativesdk.pages.PageEntry
import com.deepseek.dsh.plugin.nativesdk.theme.PluginTheme
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePageRegistryTest {
    // Сообщения проверяются по английскому тексту: фиксируем язык, а не берём локаль машины.
    @org.junit.Before
    fun pinLanguage() {
        com.dsh.core.model.Tr.language = { "en" }
    }

    private val page = TestPage("subscriptions")
    private val subscription = NativePageRegistration(
        pluginId = "changfenhuang.subscription",
        jsPackage = "dsh-plugin-subscription-oauth",
        pages = listOf(page),
    )

    @Test
    fun `auth package and section id resolve to native page`() {
        assertSame(
            page,
            NativePageRegistry.find(
                "dsh-plugin-subscription-oauth#subscriptions",
                listOf(subscription),
            ),
        )
    }

    @Test
    fun `native plugin id remains a valid component owner`() {
        assertSame(
            page,
            NativePageRegistry.find("changfenhuang.subscription#subscriptions", listOf(subscription)),
        )
    }

    @Test
    fun `scoped package version does not change its component identity`() {
        val scoped = subscription.copy(jsPackage = "@scope/subscription@1.2.3")
        assertSame(
            page,
            NativePageRegistry.find("@scope/subscription#subscriptions", listOf(scoped)),
        )
    }

    @Test
    fun `unmatched section or malformed component stays on web fallback`() {
        assertNull(NativePageRegistry.find("dsh-plugin-subscription-oauth#other", listOf(subscription)))
        assertNull(NativePageRegistry.find("dsh-plugin-subscription-oauth", listOf(subscription)))
        assertNull(NativePageRegistry.find("#subscriptions", listOf(subscription)))
    }

    @Test
    fun `rescan without loaded companion removes native route`() {
        NativePageRegistry.replaceAll(listOf(subscription))
        assertSame(page, NativePageRegistry.find("dsh-plugin-subscription-oauth#subscriptions"))

        NativePageRegistry.replaceAll(emptyList())

        assertNull(NativePageRegistry.find("dsh-plugin-subscription-oauth#subscriptions"))
    }

    @Test
    fun `loaded companion without matching page does not take over web ui`() {
        assertNull(
            NativePageRegistry.find(
                "dsh-plugin-subscription-oauth#subscriptions",
                listOf(subscription.copy(pages = emptyList())),
            ),
        )
    }

    @Test
    fun `settings section falls back to a page with the same localized title`() {
        // id раздела веб-клиента и нативной страницы разные, но текст один — совпадение по заголовку.
        val providers = TestPage("providers", title = "Подписки / Subscriptions")
        val match = NativePageRegistry.findSettingsSection(
            componentId = "dsh-plugin-subscription-oauth#subscriptions",
            title = "Подписки",
            registrations = listOf(subscription.copy(pages = listOf(providers))),
        )
        assertSame(providers, match.page)
        assertNull(match.reason)
    }

    @Test
    fun `single settings page maps to a single section when ids differ`() {
        val providers = TestPage("providers", title = "Providers")
        val match = NativePageRegistry.findSettingsSection(
            componentId = "dsh-plugin-subscription-oauth#subscriptions",
            title = "Подписки",
            registrations = listOf(subscription.copy(pages = listOf(providers))),
            allowSinglePageFallback = true,
        )
        assertSame(providers, match.page)
    }

    @Test
    fun `single page fallback is refused when the plugin has several sections`() {
        val providers = TestPage("providers", title = "Providers")
        val match = NativePageRegistry.findSettingsSection(
            componentId = "dsh-plugin-subscription-oauth#subscriptions",
            title = "Подписки",
            registrations = listOf(subscription.copy(pages = listOf(providers))),
            allowSinglePageFallback = false,
        )
        assertNull(match.page)
        assertTrue(match.reason.orEmpty().contains("registered"))
    }

    @Test
    fun `companion loaded without settings pages reports why the web fallback stays`() {
        val match = NativePageRegistry.findSettingsSection(
            componentId = "dsh-plugin-subscription-oauth#subscriptions",
            title = "Подписки",
            registrations = listOf(subscription.copy(pages = emptyList())),
        )
        assertNull(match.page)
        assertTrue(match.reason.orEmpty().contains("registered no pages"))
    }

    @Test
    fun `git url jsPackage matches the repository name`() {
        // В manifest компаньона jsPackage может быть адресом GitHub: сравниваются имя репозитория и имя модуля.
        val fromUrl = subscription.copy(
            jsPackage = "https://github.com/EricFL1998/dsh-plugin-subscription-oauth",
        )
        assertSame(
            page,
            NativePageRegistry.find("dsh-plugin-subscription-oauth#subscriptions", listOf(fromUrl)),
        )
    }

    @Test
    fun `a missing dsh-plugin prefix does not break the pairing`() {
        // С одной стороны `dsh-plugin-subscription-oauth`, с другой `subscription-oauth` —
        // без известных префиксов это один плагин.
        val short = subscription.copy(jsPackage = "subscription-oauth")
        assertSame(
            page,
            NativePageRegistry.find("dsh-plugin-subscription-oauth#subscriptions", listOf(short)),
        )
    }

    @Test
    fun `a page registered under another placement is named in the reason`() {
        val tab = TestPage("subscriptions", title = "Подписки", placement = PageEntry.Placement.PLUGINS)
        val match = NativePageRegistry.findSettingsSection(
            componentId = "dsh-plugin-subscription-oauth#subscriptions",
            title = "Подписки",
            registrations = listOf(subscription.copy(pages = listOf(tab))),
        )
        assertNull(match.page)
        assertTrue(match.reason.orEmpty().contains("PLUGINS:subscriptions"))
    }

    private class TestPage(
        id: String,
        title: String = id,
        placement: PageEntry.Placement = PageEntry.Placement.SETTINGS,
    ) : PageContent {
        override val entry = PageEntry(id, title, placement = placement)
        @androidx.compose.runtime.Composable
        override fun Content(spec: JsonObject, theme: PluginTheme, host: PluginHostApi) = Unit
    }
}
