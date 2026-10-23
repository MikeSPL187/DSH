package com.deepseek.dsh.tooling.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * v4.0 设计文档 §5.6: 契约验证与回放器
 */
@Serializable
data class ContractFixture(
    val contractVersion: String,
    val expectedDigest: String,
    val mockEvents: List<FixtureEvent> = emptyList()
)

@Serializable
data class FixtureEvent(
    val type: String,
    val hostId: String? = null,
    val sessionId: String? = null,
    val delta: String? = null,
    val generation: Long? = null
)

class ContractReplayer(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    /** 规范化编码：固定字段顺序、省略缺省值，保证同一组事件只有一种字节表示。 */
    private val canonicalJson = Json { encodeDefaults = false; explicitNulls = false; prettyPrint = false }

    /**
     * 读取并校验夹具：[ContractFixture.expectedDigest] 必须等于 [eventsDigest]（审计 F-19）。
     * 被改动过的 mockEvents 会被拒绝，而不是当作有效契约回放。
     */
    fun loadAndVerifyFixture(fixtureFile: File): Result<ContractFixture> {
        if (!fixtureFile.exists()) {
            return Result.failure(IllegalArgumentException("Fixture not found: ${fixtureFile.absolutePath}"))
        }

        return try {
            val content = fixtureFile.readText(Charsets.UTF_8)
            val fixture = json.decodeFromString<ContractFixture>(content)
            val actual = eventsDigest(fixture.mockEvents)
            if (!MessageDigest.isEqual(
                    actual.toByteArray(),
                    fixture.expectedDigest.removePrefix("sha256:").lowercase().toByteArray(),
                )
            ) {
                Result.failure(
                    IllegalStateException("Fixture digest mismatch: expected ${fixture.expectedDigest}, actual $actual"),
                )
            } else {
                Result.success(fixture)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** mockEvents 的 SHA-256：对规范化 JSON 数组求哈希（小写十六进制）。 */
    fun eventsDigest(events: List<FixtureEvent>): String =
        calculateDigest(canonicalJson.encodeToString(ListSerializer(FixtureEvent.serializer()), events))

    fun calculateDigest(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
