package com.dsh.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-18, G-46: «Новая версия» — только для действительно более новой выпущенной версии нашего репозитория. */
class UpdateVersionTest {
    @Test
    fun olderOrEqualReleaseIsNotAnUpdate() {
        // Раньше любая отличающаяся строка считалась новой: сборка 1.0 звала «обновиться» на 1.0.6 чужого upstream
        // и на более старую 0.9.
        assertFalse(isNewerVersion("0.9", "1.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0"))
        assertFalse(isNewerVersion("1.0", "1.0-debug"))
        assertTrue(isNewerVersion("1.0.10", "1.0.9"))
        assertTrue(isNewerVersion("1.1", "1.0-debug"))
        assertFalse(isNewerVersion("garbage", "1.0"))
    }

    @Test
    fun draftsPrereleasesAndForeignTagsAreSkipped() {
        val best = pickLatestRelease(
            listOf(
                ReleaseEntry("app-v2.0.0", draft = true, prerelease = false),
                ReleaseEntry("app-v1.9.0-rc.1", draft = false, prerelease = true),
                ReleaseEntry("rootfs-v9.9.9", draft = false, prerelease = false),
                ReleaseEntry("app-v1.0.9", draft = false, prerelease = false),
                ReleaseEntry("app-v1.0.10", draft = false, prerelease = false),
            ),
        )
        assertEquals("app-v1.0.10", best?.tag)
    }

    @Test
    fun releaseLinkStaysInOurRepository() {
        assertEquals(DSH_RELEASES_PAGE, safeReleaseUrl("https://evil.example/app.apk"))
        assertEquals(DSH_RELEASES_PAGE, safeReleaseUrl("https://github.com/EricFL1998/deepseek-harness-mobile/releases/tag/app-v1.0.6"))
        val ours = "https://github.com/MikeSPL187/DSH/releases/tag/app-v1.1.0"
        assertEquals(ours, safeReleaseUrl(ours))
    }
}
