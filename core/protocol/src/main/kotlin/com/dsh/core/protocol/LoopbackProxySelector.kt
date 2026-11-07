package com.dsh.core.protocol

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Locale

/** The on-device Host is never reached through the user's external network proxy. */
class LoopbackProxySelector(private val delegate: ProxySelector?) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> =
        if (uri.host?.lowercase(Locale.ROOT) in LOOPBACK) listOf(Proxy.NO_PROXY)
        else delegate?.select(uri) ?: listOf(Proxy.NO_PROXY)

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        delegate?.connectFailed(uri, sa, ioe)
    }

    private companion object {
        val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}
