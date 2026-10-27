package com.dsh.tooling.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Проверка и воспроизведение контракта.
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
    /** Каноническое кодирование: порядок полей постоянный, значения по умолчанию пропускаются — у набора событий одно байтовое представление. */
    private val canonicalJson = Json { encodeDefaults = false; explicitNulls = false; prettyPrint = false }

    /**
     * Прочитать и проверить образец: [ContractFixture.expectedDigest] должен совпадать с [eventsDigest] (аудит F-19).
     * Изменённые mockEvents отклоняются, а не воспроизводятся как действующий контракт.
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

    /** SHA-256 mockEvents: хэш канонического массива JSON (hex в нижнем регистре). */
    fun eventsDigest(events: List<FixtureEvent>): String =
        calculateDigest(canonicalJson.encodeToString(ListSerializer(FixtureEvent.serializer()), events))

    fun calculateDigest(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
