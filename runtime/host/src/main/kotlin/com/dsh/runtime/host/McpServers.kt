package com.dsh.runtime.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * MCP-серверы, добавленные в приложении: записи `@deepseek-ai/dsh-mcp-client` в слое патчей `dsh-web-app` (тот же,
 * что у плагинов из приложения), каждая — в блоке с меткой `# android-mcp: <имя>`. Реестр (без секретов) — в
 * личных файлах приложения; токен лежит в хранилище ключей Host, в записи — только ссылка ([tokenRef]).
 * Изменения вступают в силу после перезапуска Host.
 */
class McpServers(private val layout: RuntimeLayout, private val registryFile: File = File(layout.runtimeMetaDir, "mcp-servers.json")) {

    @Serializable
    data class Server(
        val name: String,
        /** `http` (Streamable HTTP) или `stdio` (команда в Linux на телефоне). */
        val kind: String,
        val url: String = "",
        val command: String = "",
        val args: List<String> = emptyList(),
        /** Есть ли токен: заголовок `Authorization: Bearer` из ссылки [tokenRef]. */
        val hasToken: Boolean = false,
        /** Последние 4 символа токена — единственное, что о нём показывает приложение. */
        val tokenTail: String = "",
    ) {
        /** Id записи профиля: по нему Host сообщает состояние. */
        val entryId: String get() = "mcp-$name"
        val tokenRef: String get() = tokenRefOf(name)
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun list(): List<Server> = runCatching { json.decodeFromString<List<Server>>(registryFile.readText()) }.getOrDefault(emptyList())

    /** Добавить или заменить сервер; блок в слое патчей пишется сразу. */
    fun put(server: Server) {
        require(validName(server.name)) { "bad name" }
        when (server.kind) {
            HTTP -> require(validUrl(server.url)) { "bad url" }
            STDIO -> require(server.command.isNotBlank() && server.command.none { it.isWhitespace() }) { "bad command" }
            else -> error("bad kind")
        }
        val others = list().filter { it.name != server.name }
        // Ссылка на токен не различает регистр и «-»/«_»: «a-b» и «A_B» делили бы один токен.
        require(others.none { it.tokenRef == server.tokenRef }) { "name taken" }
        val next = others + server
        save(next)
    }

    fun remove(name: String) = save(list().filter { it.name != name })

    private fun save(servers: List<Server>) {
        registryFile.parentFile?.mkdirs()
        atomicWrite(registryFile, json.encodeToString(servers))
        val patch = webAppPatchFile(layout)
        if (!patch.isFile) error("dsh-web-app patch layer is missing")
        atomicWrite(patch, mount(patch.readText(), servers))
    }

    companion object {
        const val HTTP = "http"
        const val STDIO = "stdio"
        private const val MARKER = "# android-mcp: "
        private val NAME = Regex("^[A-Za-z0-9_-]{1,32}$")
        private val URL = Regex("""^(https://[^\s/?#]+(?:[/?#]\S*)?|http://(localhost|127\.0\.0\.1)(:\d{1,5})?(/\S*)?)$""")

        /** Имя сервера `dsh-mcp-client`: `[A-Za-z0-9_-]{1,32}`. */
        fun validName(name: String): Boolean = NAME.matches(name)

        /** https-адрес или http только на этот телефон (`localhost`) — как политика сети приложения. */
        fun validUrl(url: String): Boolean = URL.matches(url)

        /** Ссылка на токен в хранилище ключей Host: `MCP_<ИМЯ>_TOKEN`, допустимая `credentialRef`. */
        fun tokenRefOf(name: String): String = "MCP_" + name.uppercase().replace('-', '_') + "_TOKEN"

        /** Разбить строку команды по пробелам: первая часть — программа, остальное — аргументы (оболочки нет). */
        fun splitCommand(line: String): Pair<String, List<String>>? {
            val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return parts.firstOrNull()?.let { it to parts.drop(1) }
        }

        /** Строка YAML в двойных кавычках: экранирование как в JSON (YAML 1.2 его принимает). */
        private fun q(s: String) = JsonPrimitive(s).toString()

        /** Блок записи для одного сервера. */
        fun render(s: Server): String = buildString {
            append(MARKER).append(s.name).append('\n')
            append("- insert:\n")
            append("    - id: ").append(q(s.entryId)).append('\n')
            append("      name: '@deepseek-ai/dsh-mcp-client'\n")
            append("      config:\n")
            append("        serverName: ").append(q(s.name)).append('\n')
            if (s.kind == HTTP) {
                append("        transport: streamable-http\n")
                append("        url: ").append(q(s.url)).append('\n')
                if (s.hasToken) {
                    append("        credentialHeaders:\n")
                    append("          Authorization:\n")
                    append("            ref: ").append(q(s.tokenRef)).append('\n')
                    append("            prefix: \"Bearer \"\n")
                }
            } else {
                append("        transport: stdio\n")
                append("        command: ").append(q(s.command)).append('\n')
                append("        args: [").append(s.args.joinToString(", ") { q(it) }).append("]\n")
                append("        cwd: \"/workspace\"\n")
            }
        }

        /**
         * Текст слоя патчей с блоками [servers]: старые блоки `# android-mcp:` убираются (до следующей метки
         * `# android-` или конца файла), новые дописываются в конец. Чужие блоки (`# android-installed:`) не трогаются.
         */
        fun mount(text: String, servers: List<Server>): String {
            val lines = text.lines()
            val kept = ArrayList<String>()
            var skipping = false
            for (line in lines) {
                if (line.startsWith("# android-")) skipping = line.startsWith(MARKER)
                if (!skipping) kept += line
            }
            while (kept.isNotEmpty() && kept.last().isBlank()) kept.removeAt(kept.lastIndex)
            val base = kept.joinToString("\n").let { if (it.trim() == "[]" || it.isBlank()) "" else it }
            val blocks = servers.joinToString("\n") { render(it) }
            return when {
                base.isEmpty() && blocks.isEmpty() -> "[]\n"
                base.isEmpty() -> blocks
                blocks.isEmpty() -> base + "\n"
                else -> base + "\n\n" + blocks
            }
        }

        private fun atomicWrite(file: File, text: String) {
            val tmp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
            try {
                java.io.FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() }
                if (!tmp.renameTo(file)) error("cannot write ${file.name}")
            } finally {
                tmp.delete()
            }
        }
    }
}

/** Файл патча `dsh-web-app`: слой, к которому приложение подключает свои записи (плагины, MCP-серверы). */
internal fun webAppPatchFile(layout: RuntimeLayout): File = File(
    layout.rootfsDir,
    "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/cordis.patch.yml",
)
