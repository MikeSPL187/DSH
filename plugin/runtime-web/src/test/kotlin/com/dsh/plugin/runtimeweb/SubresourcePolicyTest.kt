package com.dsh.plugin.runtimeweb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-34: веб-часть плагина загружает подресурсы только с Host. */
class SubresourcePolicyTest {
    @Test
    fun onlyTheHostAndLocalDataAreAllowed() {
        assertTrue(subresourceAllowed("http://127.0.0.1:4321/api/x", "127.0.0.1", 4321))
        assertTrue(subresourceAllowed("data:image/png;base64,AAAA", "127.0.0.1", 4321))
        assertFalse(subresourceAllowed("https://evil.example/collect?d=secret", "127.0.0.1", 4321))
        assertFalse(subresourceAllowed("http://127.0.0.1:8080/other-local-server", "127.0.0.1", 4321))
        assertFalse(subresourceAllowed("http://127.0.0.1:4321/x", "127.0.0.1", 0))
        assertFalse(subresourceAllowed("file:///data/data/x", "127.0.0.1", 4321))
    }
}
