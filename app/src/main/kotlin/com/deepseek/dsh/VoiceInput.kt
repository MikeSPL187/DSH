package com.deepseek.dsh

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.deepseek.dsh.core.data.DshRepository
import com.deepseek.dsh.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Голосовой ввод в поле чата: разрешение на микрофон, запись и распознавание через
 * речевой сервис Host. Результат — [AppRuntimeUiState.voiceTranscript] (чат вставляет его
 * в черновик), ход и ошибки — в полях `voice*` того же состояния.
 */
class VoiceInput internal constructor(
    private val context: Context,
    private val runtimeUi: AppRuntimeUiState,
    private val repository: DshRepository,
    private val hostId: () -> String,
    private val scope: CoroutineScope,
    internal val recorder: VoiceRecorder,
) {
    internal var requestPermission: () -> Unit = {}

    /** Запись не дольше, чем принимает Host (catalog.maxDurationSeconds). */
    internal val maxSeconds = 120

    fun start() {
        val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) begin() else requestPermission()
    }

    internal fun begin() {
        runtimeUi.voiceError = null
        runtimeUi.voiceSeconds = 0
        runtimeUi.voiceRecording = recorder.start()
        if (!runtimeUi.voiceRecording) runtimeUi.voiceError = context.getString(R.string.voice_device_unsupported)
    }

    internal fun denied() {
        runtimeUi.voiceError = context.getString(R.string.voice_permission_denied)
    }

    fun stop() {
        if (!runtimeUi.voiceRecording || runtimeUi.voiceBusy) return
        val wav = recorder.stop()
        runtimeUi.voiceRecording = false
        runtimeUi.voiceLevel = 0f
        if (wav == null) {
            runtimeUi.voiceError = context.getString(R.string.voice_no_speech)
            return
        }
        runtimeUi.voiceBusy = true
        scope.launch {
            try {
                transcribe(wav)
            } finally {
                runtimeUi.voiceBusy = false
            }
        }
    }

    private suspend fun transcribe(wav: ByteArray) {
        val host = hostId()
        val catalog = repository.speech.speechCatalog(host).getOrNull()
        val provider = catalog?.selected
        if (catalog == null || provider == null) {
            runtimeUi.voiceError = context.getString(R.string.voice_service_unavailable)
            return
        }
        // Распознаватель не готов — говорим об этом прямо, а не общим «не удалось распознать».
        val phase = provider.preparation?.phase
        if (phase != "ready" && phase != "standby") {
            runtimeUi.voiceError = context.getString(R.string.voice_prepare_required)
            return
        }
        val encoded = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        repository.speech.speechTranscribe(host, provider.id, catalog.language, encoded).fold(
            onSuccess = { text ->
                if (text.isBlank()) {
                    runtimeUi.voiceError = context.getString(R.string.voice_no_speech)
                } else {
                    runtimeUi.voiceTranscript = text
                    runtimeUi.voiceError = null
                }
            },
            onFailure = { error ->
                runtimeUi.voiceError = context.getString(R.string.voice_failed, error.message ?: error.toString())
            },
        )
    }

    fun cancel() {
        recorder.cancel()
        runtimeUi.voiceRecording = false
        runtimeUi.voiceLevel = 0f
    }
}

/** Голосовой ввод, привязанный к экрану: уровень громкости, таймер записи и освобождение микрофона. */
@Composable
internal fun rememberVoiceInput(
    runtimeUi: AppRuntimeUiState,
    repository: DshRepository,
    hostId: () -> String,
    scope: CoroutineScope,
): VoiceInput {
    val context = LocalContext.current
    val voice = remember { VoiceInput(context, runtimeUi, repository, hostId, scope, VoiceRecorder()) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) voice.begin() else voice.denied() }
    voice.requestPermission = { launcher.launch(android.Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(voice) {
        voice.recorder.onLevel = { level -> runtimeUi.voiceLevel = level }
        onDispose {
            voice.recorder.onLevel = null
            voice.recorder.cancel()
        }
    }
    LaunchedEffect(runtimeUi.voiceRecording) {
        while (runtimeUi.voiceRecording) {
            kotlinx.coroutines.delay(1_000)
            runtimeUi.voiceSeconds += 1
            if (runtimeUi.voiceSeconds >= voice.maxSeconds) {
                voice.stop()
                break
            }
        }
    }
    return voice
}
