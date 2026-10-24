package com.deepseek.dsh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.data.SpeechCatalog
import com.deepseek.dsh.core.data.SpeechPreparation
import com.deepseek.dsh.core.data.SpeechProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 语音识别相关调用（Host `speech` Remote）。
 *
 * @param catalog 读目录：服务、语言、准备状态。
 * @param configure 保存服务/语言选择。
 * @param prepare 开始准备（下载/校验/加载模型）。
 * @param cancelPrepare 取消准备。
 */
data class SpeechHooks(
    val catalog: suspend () -> Result<SpeechCatalog>,
    val configure: suspend (providerId: String, language: String) -> Result<Boolean>,
    val prepare: suspend (providerId: String, downloadSource: String?) -> Result<Boolean>,
    val cancelPrepare: suspend (providerId: String) -> Result<Boolean>,
)

/**
 * 「录音输入」插件的原生界面（官方 `plugins.bundle.config` 的 VoicePreparation）。
 *
 * 这里是模型准备卡片：识别服务、识别语言、下载来源、准备进度与失败原因。
 * 文案取自官方 `experimental/client-ui-voice-input/src/client/locales.ts` 的
 * zh 字典；输入区的麦克风是另一块（composer activity），后续单独实现。
 */
internal object VoiceInputPluginUi : PluginUi {
    override val bundle: String = "@deepseek-ai/dsh-experimental-voice-input-bundle"

    @Composable
    override fun Section(hooks: PluginUiHooks) {
        val speech = hooks.speech
        if (speech == null) {
            com.deepseek.dsh.feature.settings.ui.SubNote(stringResource(R.string.voice_unavailable), top = 0.dp)
            return
        }
        VoicePreparationSection(speech)
    }
}

/** 语言代码 → 官方显示名。 */
@Composable
private fun speechLanguageName(code: String): String = when (code) {
    "auto" -> stringResource(R.string.voice_lang_auto)
    "zh" -> stringResource(R.string.voice_lang_zh)
    "en" -> stringResource(R.string.voice_lang_en)
    "yue" -> stringResource(R.string.voice_lang_yue)
    "ja" -> stringResource(R.string.voice_lang_ja)
    "ko" -> stringResource(R.string.voice_lang_ko)
    else -> code
}

@Composable
private fun speechStepName(kind: String): String = when (kind) {
    "check" -> stringResource(R.string.voice_step_check)
    "model" -> stringResource(R.string.voice_step_model)
    "vad" -> stringResource(R.string.voice_step_vad)
    "verify" -> stringResource(R.string.voice_step_verify)
    "load" -> stringResource(R.string.voice_step_load)
    else -> kind
}

@Composable
private fun speechStepStatus(status: String): String = when (status) {
    "pending" -> stringResource(R.string.voice_status_pending)
    "running" -> stringResource(R.string.voice_status_running)
    "done" -> stringResource(R.string.voice_status_done)
    "failed" -> stringResource(R.string.voice_status_failed)
    "cancelled" -> stringResource(R.string.voice_status_cancelled)
    else -> status
}

@Composable
private fun speechPhaseLabel(phase: String): String = when (phase) {
    "ready" -> stringResource(R.string.voice_phase_ready)
    "checking" -> stringResource(R.string.voice_phase_checking)
    "downloading" -> stringResource(R.string.voice_phase_downloading)
    "verifying" -> stringResource(R.string.voice_phase_verifying)
    "loading" -> stringResource(R.string.voice_phase_loading)
    "waking" -> stringResource(R.string.voice_phase_waking)
    "cancelling" -> stringResource(R.string.voice_phase_cancelling)
    "cancelled" -> stringResource(R.string.voice_phase_cancelled)
    "failed" -> stringResource(R.string.voice_phase_failed)
    else -> stringResource(R.string.voice_phase_unprepared)
}

/** 下载来源 → 官方显示名。 */
@Composable
private fun sourceLabel(source: String): String = when (source) {
    "https://huggingface.co" -> "Hugging Face"
    "https://hf-mirror.com" -> stringResource(R.string.voice_source_hf_mirror)
    else -> source
}

/** 这些阶段的准备还在推进，需要轮询目录刷新进度。 */
private val SPEECH_ACTIVE_PHASES = setOf(
    "checking", "downloading", "verifying", "loading", "waking", "cancelling", "preparing",
)

@Composable
private fun VoicePreparationSection(speech: SpeechHooks) {
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<SpeechCatalog?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var downloadSource by remember { mutableStateOf("") }

    LaunchedEffect(reload) {
        speech.catalog().fold(
            onSuccess = { catalog = it; loadError = null },
            onFailure = { loadError = context.getString(R.string.voice_unavailable) },
        )
    }
    // Пока модель готовится — опрашиваем каталог (у Host это поток состояния).
    val active = catalog?.selected?.preparation?.phase in SPEECH_ACTIVE_PHASES
    LaunchedEffect(active, reload) {
        while (active) {
            delay(1500)
            speech.catalog().onSuccess { catalog = it }
        }
    }

    val current = catalog
    if (current == null) {
        com.deepseek.dsh.feature.settings.ui.SubNote(loadError ?: stringResource(R.string.voice_reading), top = 0.dp)
        return
    }
    val provider = current.selected
    if (provider == null) {
        com.deepseek.dsh.feature.settings.ui.SubNote(stringResource(R.string.voice_unavailable), top = 0.dp)
        return
    }

    com.deepseek.dsh.feature.settings.ui.GLabel(stringResource(R.string.voice_service))
    ChoiceChipFlow(
        options = current.providers.map { item -> item.id to item.name },
        selected = provider.id,
        enabled = !busy,
        onSelect = { providerId ->
            scope.launch {
                busy = true
                speech.configure(providerId, current.language).onSuccess { reload += 1 }
                busy = false
            }
        },
    )
    com.deepseek.dsh.feature.settings.ui.SubNote(
        stringResource(if (provider.location == "host-local") R.string.voice_audio_local else R.string.voice_audio_cloud),
    )

    com.deepseek.dsh.feature.settings.ui.GLabel(stringResource(R.string.voice_language))
    ChoiceChipFlow(
        options = provider.languages.map { language -> language to speechLanguageName(language) },
        selected = current.language,
        enabled = !busy,
        onSelect = { language ->
            scope.launch {
                busy = true
                speech.configure(provider.id, language).onSuccess { reload += 1 }
                busy = false
            }
        },
    )

    // Карточка подготовки модели.
    val preparation = provider.preparation
    val phase = preparation?.phase ?: "unprepared"
    com.deepseek.dsh.feature.settings.ui.StCard(Modifier.padding(top = 16.dp)) {
        Text(provider.name, style = com.deepseek.dsh.core.designsystem.Dsh.type.bodyMedium, color = c.t1)
        Text(
            speechPhaseLabel(phase),
            style = com.deepseek.dsh.core.designsystem.Dsh.type.footnote,
            color = when (phase) { "failed" -> c.danger; "ready" -> c.ok; else -> c.t2 },
            modifier = Modifier.padding(top = 2.dp),
        )
        if (phase == "unprepared" && provider.location == "host-local") {
            provider.setupEstimate?.let { estimate ->
                Text(
                    stringResource(
                        R.string.voice_estimate,
                        estimate.recommendedDiskBytes / 1_000_000_000,
                        estimate.expectedMemoryBytes / 1_000_000_000,
                        estimate.minimumMinutes,
                        estimate.maximumMinutes,
                    ),
                    style = com.deepseek.dsh.core.designsystem.Dsh.type.footnote, color = c.t3, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        preparation?.totalBytes?.let { total ->
            val completed = preparation.completedBytes
            if (total > 0 && completed != null) {
                val fraction = (completed.toFloat() / total).coerceIn(0f, 1f)
                com.deepseek.dsh.feature.settings.ui.Meter(
                    stringResource(R.string.voice_progress, completed / 1_000_000, total / 1_000_000, (fraction * 100).toInt()),
                    "${(fraction * 100).toInt()}%",
                    fraction,
                )
            }
        }
        preparation?.steps?.takeIf { it.isNotEmpty() }?.let { steps ->
            Column(Modifier.padding(top = 8.dp)) {
                steps.forEach { step ->
                    Text(
                        "${speechStepName(step.kind)} · ${speechStepStatus(step.status)}",
                        style = com.deepseek.dsh.core.designsystem.Dsh.type.footnote,
                        color = if (step.status == "failed") c.danger else c.t3,
                    )
                }
            }
        }
        if (phase == "failed" && preparation?.message != null) {
            Text(stringResource(R.string.voice_prepare_failed, preparation.message.orEmpty()), style = com.deepseek.dsh.core.designsystem.Dsh.type.footnote, color = c.danger, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.voice_prepare_hint), style = com.deepseek.dsh.core.designsystem.Dsh.type.footnote, color = c.t3)
            preparation.source?.let { Text(stringResource(R.string.voice_download_origin, it), style = com.deepseek.dsh.core.designsystem.Dsh.type.footnote, color = c.t3) }
        }
        if (provider.downloadSources.isNotEmpty()) {
            Text(stringResource(R.string.voice_model_source), style = com.deepseek.dsh.core.designsystem.Dsh.type.footnote, color = c.t2, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            ChoiceChipFlow(
                options = buildList {
                    if (provider.downloadSources.size > 1) add("" to stringResource(R.string.voice_auto_source))
                    provider.downloadSources.forEach { source -> add(source to sourceLabel(source)) }
                },
                selected = downloadSource,
                enabled = !busy,
                onSelect = { downloadSource = it },
            )
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phase == "unprepared" || phase == "failed" || phase == "cancelled") {
                com.deepseek.dsh.feature.settings.ui.KBtn(
                    stringResource(if (phase == "unprepared") R.string.voice_download_prepare else R.string.voice_retry_prepare),
                    {
                        scope.launch {
                            busy = true
                            speech.prepare(provider.id, downloadSource.ifBlank { null })
                            reload += 1
                            busy = false
                        }
                    },
                    kind = com.deepseek.dsh.feature.settings.ui.BtnKind.Primary, height = 44.dp, enabled = !busy, busy = busy,
                )
            }
            if (phase in SPEECH_ACTIVE_PHASES) {
                com.deepseek.dsh.feature.settings.ui.KBtn(
                    stringResource(R.string.voice_cancel_prepare),
                    {
                        scope.launch {
                            busy = true
                            speech.cancelPrepare(provider.id)
                            reload += 1
                            busy = false
                        }
                    },
                    height = 44.dp, enabled = !busy,
                )
            }
        }
    }
}
