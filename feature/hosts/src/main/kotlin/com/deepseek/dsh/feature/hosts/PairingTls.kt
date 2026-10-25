package com.deepseek.dsh.feature.hosts

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 配对二维码里的 TLS 指纹（`fp`）。
 *
 * 接受常见写法：`SHA256:<hex|base64>`、`sha256/<base64>`、带冒号的十六进制。
 * 指纹既可以是整张证书 DER 的 SHA-256，也可以是公钥（SPKI）的 SHA-256。
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

        /** 解析；空值返回 null（未提供指纹），格式错误抛出 [IllegalArgumentException]。 */
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
            require(decoded != null && decoded.size == 32) { com.deepseek.dsh.core.model.Tr.t("Unrecognised TLS fingerprint format", "Нераспознанный формат отпечатка TLS") }
            return PairingFingerprint(decoded)
        }
    }
}

/**
 * 只信任与配对指纹一致的服务器证书（类似 SSH 的 known_hosts）。
 *
 * 远程网关通常使用自签证书，系统 CA 校验无从谈起；二维码里的指纹就是信任根。
 * 证书不匹配时在 TLS 握手阶段失败——配对码根本不会被发送（审计 N-07）。
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
    /** 基于 [base] 派生一个只信任 [pin] 的客户端；主机名由指纹而非 CA 证明。 */
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
