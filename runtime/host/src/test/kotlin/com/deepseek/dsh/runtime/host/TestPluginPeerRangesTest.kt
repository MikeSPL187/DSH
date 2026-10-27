package com.deepseek.dsh.runtime.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит A-06: peer-диапазоны тестовых плагинов должны принимать пакеты Host, которые
 * реально стоят в rootfs. В образе 1.0.12 это @deepseek-ai/dsh-* 0.2.1-rc.3 и cordis 4.0.5
 * (проверено по распакованному rootfs); встроенный в APK манифест ещё указывает rc.2.
 * Образцы — только имя, версия и peer-диапазоны из package.json двух плагинов (ресурсы теста):
 * чужой код плагинов в репозитории не хранится.
 */
class TestPluginPeerRangesTest {
    private val hostVersions = listOf("0.2.1-rc.2", "0.2.1-rc.3")
    private val cordisVersion = "4.0.5"

    private fun peers(path: String): Map<String, String> {
        val text = checkNotNull(javaClass.getResource(path)) { "нет образца $path" }.readText()
        val json = Json.parseToJsonElement(text).jsonObject
        val peers = json["peerDependencies"] as? JsonObject ?: return emptyMap()
        return peers.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun peerRangesAcceptTheShippedHost() {
        listOf(
            "/peer-ranges/dsh-archive-manager.package.json",
            "/peer-ranges/dsh-genui.package.json",
        ).forEach { path ->
            peers(path).filterKeys { it.startsWith("@deepseek-ai/") }.forEach { (name, range) ->
                val versions = if (name == "@deepseek-ai/cordis") listOf(cordisVersion) else hostVersions
                versions.forEach { version ->
                    assertTrue("$path: $name@$version does not satisfy \"$range\"", Semver.satisfies(version, range))
                }
            }
        }
    }
}
