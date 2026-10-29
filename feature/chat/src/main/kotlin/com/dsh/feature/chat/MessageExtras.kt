package com.dsh.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs

/**
 * Чтение ответа вслух через голосовой движок телефона (Android TextToSpeech, без сети).
 * [speaking] — идёт чтение: по нему показывается «Читаю вслух» со «Стоп».
 */
@Stable
class SpeechReader internal constructor(private val context: android.content.Context) {
    var speaking by mutableStateOf(false)
        private set
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ready = false
    private var pending: List<String>? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Прочитать [text]; false — голосового движка на телефоне нет (вызывающий говорит об этом). */
    fun speak(text: String, onUnavailable: () -> Unit) {
        stop()
        val parts = speechChunks(text)
        if (parts.isEmpty()) return
        val engine = tts
        if (engine != null && ready) { play(engine, parts); return }
        pending = parts
        speaking = true
        if (engine == null) {
            tts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
                main.post {
                    if (status != android.speech.tts.TextToSpeech.SUCCESS) { speaking = false; pending = null; tts = null; onUnavailable(); return@post }
                    ready = true
                    tts?.let { e ->
                        e.language = java.util.Locale.getDefault()
                        e.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(id: String?) = Unit
                            override fun onDone(id: String?) { if (id == LAST) main.post { speaking = false } }
                            @Deprecated("API < 21") override fun onError(id: String?) { main.post { speaking = false } }
                            override fun onStop(id: String?, interrupted: Boolean) { main.post { speaking = false } }
                        })
                        pending?.let { play(e, it) }
                        pending = null
                    }
                }
            }
        }
    }

    private fun play(engine: android.speech.tts.TextToSpeech, parts: List<String>) {
        speaking = true
        parts.forEachIndexed { i, p ->
            engine.speak(p, if (i == 0) android.speech.tts.TextToSpeech.QUEUE_FLUSH else android.speech.tts.TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) LAST else "part$i")
        }
    }

    fun stop() {
        pending = null
        tts?.stop()
        speaking = false
    }

    internal fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        speaking = false
    }

    private companion object { const val LAST = "dsh-last" }
}

/** Один чтец на экран чата: останавливается и освобождается вместе с экраном. */
@Composable
fun rememberSpeechReader(): SpeechReader {
    val context = LocalContext.current
    val reader = remember { SpeechReader(context) }
    DisposableEffect(reader) { onDispose { reader.shutdown() } }
    return reader
}

/**
 * Текст ответа для чтения: без разметки Markdown, блоки кода не читаются (вместо них — короткая пауза),
 * ссылки — только их текст. Делится по абзацам на куски не длиннее предела движка.
 */
internal fun speechChunks(markdown: String, max: Int = 3_500): List<String> {
    val plain = markdown
        .replace(Regex("```[\\s\\S]*?(```|$)"), "\n\n")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+[.)])\\s+"), "")
        .replace(Regex("(\\*\\*|__|\\*|_|~~)(\\S[^*_~]*?)\\1"), "$2")
        .replace(Regex("(?m)^\\s*\\|?[-:| ]{3,}\\|?\\s*$"), "")
        .replace('|', ' ')
    val out = ArrayList<String>()
    val buf = StringBuilder()
    for (para in plain.split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }) {
        var p = para
        while (p.length > max) { out += p.substring(0, max); p = p.substring(max) }
        if (buf.length + p.length + 1 > max && buf.isNotEmpty()) { out += buf.toString(); buf.clear() }
        if (buf.isNotEmpty()) buf.append('\n')
        buf.append(p)
    }
    if (buf.isNotEmpty()) out += buf.toString()
    return out
}

/** Текст для выделения: без разметки Markdown, код блоков сохраняется (без строк ```), ссылки — «текст (адрес)». */
internal fun plainText(markdown: String): String = markdown
    .replace(Regex("(?m)^[ \\t]*```.*$\\n?"), "")
    .replace(Regex("!?\\[([^\\]]*)]\\(([^)]*)\\)"), "$1 ($2)")
    .replace(Regex("`([^`\\n]*)`"), "$1")
    .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
    .replace(Regex("(\\*\\*|__|~~)(\\S[^*_~\\n]*?)\\1"), "$2")
    .trim()

/** Лист «Выделить текст»: весь текст сообщения, выделение пальцем. */
@Composable
internal fun ColumnScope.SelectTextBody(text: String) {
    val c = Dsh.colors
    com.dsh.core.designsystem.DshSheetTitle(stringResource(R.string.select_title))
    val shape = RoundedCornerShape(18.dp)
    SelectionContainer {
        Text(
            remember(text) { plainText(text) }, style = ckBody(15.5.sp, 24.sp), color = c.t1,
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).clip(shape).background(c.s1).border(1.dp, c.line, shape)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
    Text(
        stringResource(R.string.select_hint), style = ckBody(12.5.sp, 17.sp), color = c.t3, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 6.dp, end = 6.dp),
    )
}

/** `.jump`: «К последнему сообщению» — круглая стеклянная кнопка 44 dp. */
@Composable
internal fun JumpButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    val label = stringResource(R.string.chat_jump)
    Box(
        modifier.size(44.dp).floatShadow(CircleShape, c.isDark).clip(CircleShape).background(c.glass.compositeOn(c.bg)).border(1.dp, c.glassLine, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(DshGlyphs.Up, null, tint = c.t1, modifier = Modifier.size(22.dp).rotate(180f)) }
}

/** `.tts`: «Читаю вслух» со «Стоп». */
@Composable
internal fun ReadingPill(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    val stop = stringResource(R.string.tts_stop)
    Row(
        modifier.heightIn(min = 44.dp).floatShadow(CircleShape, c.isDark).clip(CircleShape).background(c.glass.compositeOn(c.bg)).border(1.dp, c.glassLine, CircleShape)
            .padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(DshGlyphs.Speaker, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.tts_reading), style = ckBody(13.5.sp, 18.sp, androidx.compose.ui.text.font.FontWeight.Medium), color = c.t1)
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.s2).clickable(role = Role.Button, onClick = onStop).semantics { contentDescription = stop },
            contentAlignment = Alignment.Center,
        ) { Icon(DshGlyphs.Stop, null, tint = c.t1, modifier = Modifier.size(18.dp)) }
    }
}
