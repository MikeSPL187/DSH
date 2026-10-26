package com.deepseek.dsh.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Запись для голосового ввода: 16 кГц, моно, PCM16.
 *
 * Веб-версия пишет через `MediaRecorder`, затем `decodeAudioData` и `OfflineAudioContext`
 * пересчитывает в WAV 16 кГц моно PCM16; на Android `AudioRecord` сразу пишет с нужной
 * частотой без пересчёта, на выходе тот же WAV (`speech/transcribe` Host
 * принимает только его).
 */
internal class VoiceRecorder {
    /** Частота дискретизации — как требует Host. */
    private val sampleRate = 16_000

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var pcm: ByteArrayOutputStream? = null

    @Volatile
    private var running = false

    /** Уровень записи (RMS 0..1 для индикатора). */
    var onLevel: ((Float) -> Unit)? = null

    /**
     * Начать запись.
     * @returns удалось ли начать (нет микрофона или он занят — false).
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val bufferSize = minBuffer * 2
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (_: Exception) {
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        val output = ByteArrayOutputStream()
        pcm = output
        running = true
        record = recorder
        recorder.startRecording()
        worker = Thread {
            val buffer = ByteArray(minBuffer)
            while (running) {
                val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(0)
                if (read > 0) {
                    output.write(buffer, 0, read)
                    // Уровень RMS: корень из среднего квадрата отсчётов PCM16, в диапазоне 0..1.
                    var sum = 0.0
                    var index = 0
                    while (index + 1 < read) {
                        val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort()
                        val value = sample / 32768.0
                        sum += value * value
                        index += 2
                    }
                    val rms = if (read > 0) kotlin.math.sqrt(sum / (read / 2)) else 0.0
                    onLevel?.invoke((rms * 4).coerceIn(0.0, 1.0).toFloat())
                }
            }
        }.apply {
            name = "dsh-voice-recorder"
            start()
        }
        return true
    }

    /**
     * Закончить запись и вернуть WAV 16 кГц моно PCM16.
     * @returns WAV; null — ничего не записано.
     */
    fun stop(): ByteArray? {
        if (!running) return null
        running = false
        runCatching { worker?.join(300) }
        worker = null
        val recorder = record
        record = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        val data = pcm?.toByteArray()
        pcm = null
        onLevel?.invoke(0f)
        if (data == null || data.size < 320) return null
        return encodeWav(data)
    }

    /** Отбросить текущую запись. */
    fun cancel() {
        if (!running) return
        running = false
        runCatching { worker?.join(200) }
        worker = null
        val recorder = record
        record = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        pcm = null
        onLevel?.invoke(0f)
    }

    /** Обернуть сырые PCM16 в WAV со стандартным заголовком 44 байта. */
    private fun encodeWav(data: ByteArray): ByteArray {
        val total = data.size
        val header = ByteArray(44)
        fun u32(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun u16(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        "RIFF".forEachIndexed { index, char -> header[index] = char.code.toByte() }
        u32(4, 36 + total)
        "WAVE".forEachIndexed { index, char -> header[8 + index] = char.code.toByte() }
        "fmt ".forEachIndexed { index, char -> header[12 + index] = char.code.toByte() }
        u32(16, 16)
        u16(20, 1) // PCM
        u16(22, 1) // mono
        u32(24, sampleRate)
        u32(28, sampleRate * 2) // byte rate = sampleRate * blockAlign
        u16(32, 2) // block align
        u16(34, 16) // bits per sample
        "data".forEachIndexed { index, char -> header[36 + index] = char.code.toByte() }
        u32(40, total)
        return header + data
    }
}
