package com.dsh.ai

import android.content.Context
import com.dsh.R
import com.dsh.feature.files.DiffRow
import com.dsh.feature.files.FilesGit
import com.dsh.feature.files.GitInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Сообщение коммита»: приложение собирает diff незакоммиченных изменений
 * (не больше [MAX_FILES] файлов и [MAX_CHARS] символов) и просит DeepSeek
 * написать сообщение на языке интерфейса. Ошибки — понятным текстом.
 */
object CommitMessageWriter {
    private const val MAX_FILES = 20
    private const val MAX_LINES_PER_FILE = 80
    private const val MAX_CHARS = 12_000

    suspend fun write(context: Context, git: FilesGit, info: GitInfo): Result<String> = withContext(Dispatchers.IO) {
        val diff = buildDiff(git, info)
        when (val r = DeepSeekQuick.ask(context.getString(R.string.commit_prompt), diff, maxTokens = 200)) {
            is DeepSeekQuick.Outcome.Ok -> clean(r.text)?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException(context.getString(R.string.commit_empty)))
            DeepSeekQuick.Outcome.NoKey -> Result.failure(IllegalStateException(context.getString(R.string.tm_explain_nokey)))
            DeepSeekQuick.Outcome.BadKey -> Result.failure(IllegalStateException(context.getString(R.string.tm_explain_badkey)))
            DeepSeekQuick.Outcome.NoBalance -> Result.failure(IllegalStateException(context.getString(R.string.tm_explain_balance)))
            DeepSeekQuick.Outcome.Offline -> Result.failure(IllegalStateException(context.getString(R.string.tm_explain_offline)))
        }
    }

    private suspend fun buildDiff(git: FilesGit, info: GitInfo): String = buildString {
        if (info.branch.isNotEmpty()) appendLine("branch: ${info.branch}")
        for (ch in info.changes.take(MAX_FILES)) {
            if (length > MAX_CHARS) break
            appendLine("--- ${ch.path} (${ch.kind.name.lowercase()}, +${ch.added} -${ch.deleted})")
            val rows = runCatching { git.diff(ch.path) }.getOrDefault(emptyList())
            rows.filter { it is DiffRow.Hunk || (it is DiffRow.Line && it.sign != ' ') }
                .take(MAX_LINES_PER_FILE)
                .forEach { row ->
                    when (row) {
                        is DiffRow.Hunk -> appendLine(row.text)
                        is DiffRow.Line -> appendLine("${row.sign}${row.text}")
                    }
                }
        }
        if (info.changes.size > MAX_FILES) appendLine("… +${info.changes.size - MAX_FILES} files")
    }.take(MAX_CHARS)

    /** Без обёртки ``` (с языком после неё), кавычек и пустых строк по краям. */
    internal fun clean(raw: String): String? {
        var t = raw.trim()
        if (t.startsWith("```")) t = t.substringAfter('\n', "").substringBeforeLast("```")
        return t.trim().trim('"', '«', '»', '“', '”').trim().takeIf { it.isNotEmpty() }
    }
}
