package com.dsh.feature.hosts

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Отпечаток TLS из QR-кода сопряжения (`fp`).
 *
 * Принимаются обычные записи: `SHA256:<hex|base64>`, `sha256/<base64>`, hex с двоеточиями.
 * Отпечаток — SHA-256 всего сертификата DER или открытого ключа (SPKI).
 */
class PairingFingerprint private constructor(private val digest: ByteArray) {

    fun matches(certificate: X509Certificate): Boolean {
        val sha = MessageDigest.getInstance("SHA-256")
        val certDigest = sha.digest(certificate.encoded)
        val spkiDigest = sha.digest(certificate.publicKey.encoded)
        return MessageDigest.isEqual(certDigest, digest) || MessageDigest.isEqual(spkiDigest, digest)
    }

    companion object {
        private val HEX = Regex("^[0-9a-fA-F]{64}$")

        /** Разобрать; пусто — null (отпечатка нет), неверный формат — [IllegalArgumentException]. */
        fun parse(raw: String?): PairingFingerprint? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val body = text
                .removePrefix("SHA256:").removePrefix("sha256:")
                .removePrefix("SHA256/").removePrefix("sha256/")
                .trim()
            val hex = body.replace(":", "").replace(" ", "")
            if (HEX.matches(hex)) {
                return PairingFingerprint(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            val decoded = runCatching { java.util.Base64.getDecoder().decode(body) }.getOrNull()
                ?: runCatching { java.util.Base64.getUrlDecoder().decode(body) }.getOrNull()
            require(decoded != null && decoded.size == 32) { com.dsh.core.model.Tr.t("Unrecognised TLS fingerprint format", "Нераспознанный формат отпечатка TLS") }
            return PairingFingerprint(decoded)
        }
    }
}

/**
 * Доверять только сертификату сервера с отпечатком из сопряжения (как known_hosts в SSH).
 *
 * У удалённого шлюза обычно самоподписанный сертификат, проверка по системным CA невозможна;
 * корень доверия — отпечаток из QR. Несовпадение обрывает TLS-рукопожатие — код сопряжения не отправляется (аудит N-07).
 */
class PinnedTrustManager(private val pin: PairingFingerprint) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        leaf.checkValidity()
        if (!pin.matches(leaf)) throw CertificateException("TLS certificate does not match the pairing fingerprint")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object PairingTls {
    /** Клиент на основе [base], доверяющий только [pin]; имя хоста подтверждает отпечаток, а не CA. */
    fun pinnedClient(base: OkHttpClient, pin: PairingFingerprint): OkHttpClient {
        val trustManager = PinnedTrustManager(pin)
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
