package com.dsh.terminal

import android.content.Context
import com.dsh.R
import com.dsh.ai.DeepSeekQuick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Объяснить» (`.explain`): приложение само спрашивает DeepSeek о провалившейся команде
 * (команда, код выхода и хвост вывода) и печатает ответ по мере прихода.
 * Последняя строка `FIX: <команда>` становится кнопкой, которая подставляет команду в ввод.
 */
object TermExplainer {
    suspend fun explain(context: Context, block: TermBlock, into: Explain) = withContext(Dispatchers.IO) {
        into.text = ""; into.fix = null; into.error = null; into.done = false
        val tail = block.out.plain().lines().takeLast(40).joinToString("\n").takeLast(4000)
        val user = "$ ${block.cmd}\n(exit ${block.code})\n$tail"
        val res = DeepSeekQuick.ask(context.getString(R.string.tm_explain_prompt), user, maxTokens = 300) { into.text = split(it).first }
        into.error = when (res) {
            is DeepSeekQuick.Outcome.Ok -> null
            DeepSeekQuick.Outcome.NoKey -> context.getString(R.string.tm_explain_nokey)
            DeepSeekQuick.Outcome.BadKey -> context.getString(R.string.tm_explain_badkey)
            DeepSeekQuick.Outcome.NoBalance -> context.getString(R.string.tm_explain_balance)
            DeepSeekQuick.Outcome.Offline -> context.getString(R.string.tm_explain_offline)
        }
        if (res is DeepSeekQuick.Outcome.Ok) {
            val (text, fix) = split(res.text)
            into.text = text; into.fix = fix
        }
        into.done = true
    }

    /** Текст без строки `FIX:` и сама команда-исправление. */
    internal fun split(raw: String): Pair<String, String?> {
        val lines = raw.trimEnd().lines()
        val i = lines.indexOfLast { it.trimStart().startsWith("FIX:") || "FIX:".startsWith(it.trim()) && it.isNotBlank() }
        if (i < 0) return raw.trim() to null
        val fix = lines[i].substringAfter("FIX:", "").trim().trim('`').trim().takeIf { it.isNotEmpty() }
        return lines.filterIndexed { j, _ -> j != i }.joinToString("\n").trim() to fix
    }
}
