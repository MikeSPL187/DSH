package com.deepseek.dsh.feature.hosts

import com.deepseek.dsh.core.model.PairPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Audit N-06 / N-07. */
class RemotePairingSecurityTest {

    private val certificate: X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(PEM.byteInputStream()) as X509Certificate

    /** Минимальный HTTP-шлюз на одно соединение: читает запрос и отвечает фиксированным JSON. */
    private fun withGateway(body: String, block: (String) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(contentLength) { reader.read() }
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        write(bytes)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            block("http://127.0.0.1:${server.localPort}")
        } finally {
            server.close()
            thread.join(2000)
        }
    }

    private fun payload(url: String, fp: String = "") =
        PairPayload(name = "gw", url = url, fp = fp, pair = "PAIR-1")

    @Test
    fun successWithoutTokenIsAFailure() = withGateway("{}") { url ->
        val result = runBlocking { RemotePairingManager().executePairing(payload(url), "phone") }
        assertTrue(result.isFailure)
    }

    @Test
    fun nonStringOrShortTokenIsAFailure() {
        withGateway("""{"token": 12345}""") { url ->
            assertTrue(runBlocking { RemotePairingManager().executePairing(payload(url), "phone") }.isFailure)
        }
        withGateway("""{"token": "short"}""") { url ->
            assertTrue(runBlocking { RemotePairingManager().executePairing(payload(url), "phone") }.isFailure)
        }
    }

    @Test
    fun realTokenIsAccepted() = withGateway("""{"token": "tok_0123456789abcdef"}""") { url ->
        val result = runBlocking { RemotePairingManager().executePairing(payload(url), "phone") }
        assertEquals("tok_0123456789abcdef", result.getOrThrow().deviceToken)
    }

    @Test
    fun cleartextRemoteGatewayIsRefused() {
        val result = runBlocking {
            RemotePairingManager().executePairing(payload("http://192.168.1.5:8080"), "phone")
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun fingerprintFormats() {
        val hex = "2A:1C:E5:4F:C9:F3:17:0F:11:EA:67:C2:4D:BF:A6:9A:B1:76:B9:AF:48:15:7E:92:F3:35:15:D6:67:8C:57:AF"
        assertTrue(PairingFingerprint.parse("SHA256:$hex")!!.matches(certificate))
        assertTrue(PairingFingerprint.parse(hex.replace(":", "").lowercase())!!.matches(certificate))
        val spki = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded),
        )
        assertTrue(PairingFingerprint.parse("sha256/$spki")!!.matches(certificate))
        assertFalse(PairingFingerprint.parse("SHA256:" + "00".repeat(32))!!.matches(certificate))
        assertNull(PairingFingerprint.parse(""))
        assertTrue(runCatching { PairingFingerprint.parse("SHA256:abcd1234ef5678") }.isFailure)
    }

    @Test
    fun pinnedTrustManagerRejectsMismatch() {
        val good = PinnedTrustManager(PairingFingerprint.parse("SHA256:" + sha256Hex(certificate.encoded))!!)
        good.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
        val bad = PinnedTrustManager(PairingFingerprint.parse("SHA256:" + "11".repeat(32))!!)
        assertTrue(runCatching { bad.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA") }
            .exceptionOrNull() is CertificateException)
    }

    private fun sha256Hex(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PEM = """
-----BEGIN CERTIFICATE-----
MIIBfTCCASOgAwIBAgIUSPWDbHq6AjkBqRsZ3t7rSPDQxv4wCgYIKoZIzj0EAwIw
EzERMA8GA1UEAwwIZHNoLXRlc3QwIBcNMjYxMDIzMTcwMjAxWhgPMjEyNjA5Mjkx
NzAyMDFaMBMxETAPBgNVBAMMCGRzaC10ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0D
AQcDQgAEsB6DKuCmnBRptqui38VAieETsMGYgAuQEU23SUZc1L4K02MEME3KQ6gb
K1JhrPRu8fUs+UPPS9sSIZ8/I97P+KNTMFEwHQYDVR0OBBYEFNwxmvpyubt2STKf
UZizu8Xi/qKkMB8GA1UdIwQYMBaAFNwxmvpyubt2STKfUZizu8Xi/qKkMA8GA1Ud
EwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAI9u1OIbO2zOAc6BaUqF98kf
Po87VTOx3FK0lsCTYv96AiBCEGFIh8KaqqtfQsI9F4oPpOz+aEDM5ftII6SBg/kU
7w==
-----END CERTIFICATE-----
"""
    }
}
