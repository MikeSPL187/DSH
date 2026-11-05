package com.dsh.runtime.host

import org.junit.Assert.*
import org.junit.Test

class IdentityOverlayTest {
    @Test fun keepsDistroAccountsAndUpdatesOnlyTheAndroidIdentity() {
        val base = "root:x:0:0:root:/root:/bin/sh\n_apt:x:42:65534::/nonexistent:/usr/sbin/nologin\ndsh:x:10001:10001::/root:/bin/sh\n"
        val overlay = identityOverlay(base, "dsh:x:10227:10227:dsh:/root:/bin/sh")
        assertTrue(overlay.contains("_apt:x:42:65534:"))
        assertTrue(overlay.contains("root:x:0:0:"))
        assertFalse(overlay.contains("10001"))
        assertEquals(1, overlay.lineSequence().count { it.startsWith("dsh:") })
        assertEquals(overlay, identityOverlay(overlay, "dsh:x:10227:10227:dsh:/root:/bin/sh"))
    }

    @Test fun keepsGroupsNeededByPackageMaintainerScripts() {
        val groups = "root:x:0:\nmail:x:8:\nnogroup:x:65534:\n"
        assertEquals(groups + "dsh:x:10227:\n", identityOverlay(groups, "dsh:x:10227:"))
    }

    @Test fun keepsPackageCreatedAccountsAcrossLaunchesAndRestoresDistroAccounts() {
        val base = "root:x:0:0:root:/root:/bin/sh\n_apt:x:42:65534::/nonexistent:/usr/sbin/nologin\n"
        val previous = "root:x:0:0:root:/root:/bin/sh\nwebapi:x:999:999::/srv/webapi:/usr/sbin/nologin\ndsh:x:10001:10001::/root:/bin/sh\n"
        val overlay = identityOverlay(base, "dsh:x:10227:10227:dsh:/root:/bin/sh", previous)
        assertTrue(overlay.contains("webapi:x:999:999:"))
        assertTrue(overlay.contains("_apt:x:42:65534:"))
        assertEquals(overlay, identityOverlay(base, "dsh:x:10227:10227:dsh:/root:/bin/sh", overlay))
    }
}
