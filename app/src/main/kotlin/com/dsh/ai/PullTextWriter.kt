package com.dsh.ai

import android.content.Context
import com.dsh.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Написать по коммитам» в листе запроса на слияние: сообщения коммитов ветки (не больше [MAX_CHARS] символов)
 * уходят DeepSeek, ответ — описание на языке интерфейса. Ошибки — те же понятные тексты, что у сообщения коммита.
 */
object PullTextWriter {
    private const val MAX_CHARS = 8_000

    suspend fun write(context: Context, commits: List<String>): Result<String> = withContext(Dispatchers.IO) {
        if (commits.isEmpty()) return@withContext Result.failure(IllegalStateException(context.getString(R.string.commit_empty)))
        val text = commits.joinToString("\n\n---\n\n").take(MAX_CHARS)
        when (val r = DeepSeekQuick.ask(context.getString(R.string.pr_prompt), text, maxTokens = 400)) {
            is DeepSeekQuick.Outcome.Ok -> CommitMessageWriter.clean(r.text)?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException(context.getString(R.string.commit_empty)))
            DeepSeekQuick.Outcome.NoKey -> Result.failure(IllegalStateException(context.getString(R.string.tm_explain_nokey)))
            DeepSeekQuick.Outcome.BadKey -> Result.failure(IllegalStateException(context.getString(R.string.tm_explain_badkey)))
            DeepSeekQuick.Outcome.NoBalance -> Result.failure(IllegalStateException(context.getString(R.string.tm_explain_balance)))
            DeepSeekQuick.Outcome.Offline -> Result.failure(IllegalStateException(context.getString(R.string.tm_explain_offline)))
        }
    }
}
