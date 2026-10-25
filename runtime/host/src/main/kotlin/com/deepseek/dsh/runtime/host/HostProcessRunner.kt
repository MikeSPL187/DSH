package com.deepseek.dsh.runtime.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Жизненный цикл дочернего процесса DSH под proot/bxroot.
 *
 * Здесь же правило «состояние не врёт»: DSH может тихо выйти посреди запуска (частая причина —
 * ошибка загрузки плагина, см. `startup failed: N required plugins did
 * not activate` в `.captures/latest-host.log`); раньше `waitFor()` никто не ждал, RuntimePhase навсегда оставался STARTING,
 * порт и токен не читались никогда. Теперь есть сторож выхода и кольцевой буфер последнего вывода: как только процесс вышел,
 * в [RuntimeStatus] пишутся ERROR, код выхода и последние строки (без секретов).
 */
class HostProcessRunner(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    /** Защищает [process], [logLines], [stopping] и смену состояния. */
    private val lock = Any()

    @Volatile
    private var process: Process? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var watchdogJob: Job? = null

    /** Последний вывод (без секретов) — при ошибке сразу показывается интерфейсу. */
    private val logLines = ArrayDeque<String>()

    /** true во время [terminate] по инициативе приложения: сторож выхода не считает это сбоем. */
    private var stopping = false

    private val _statusFlow = MutableStateFlow(
        RuntimeStatus(phase = RuntimePhase.STOPPED)
    )
    val statusFlow: StateFlow<RuntimeStatus> = _statusFlow.asStateFlow()

    @Synchronized
    fun isRunning(): Boolean {
        return process?.isAlive == true
    }

    suspend fun launchProcess(
        command: List<String>,
        workingDir: File,
        logFile: File? = null,
        environment: Map<String, String> = emptyMap(),
        onEndpointFound: ((ProcessOutputParser.ParsedEndpoint) -> Unit)? = null
    ): Result<Process> = withContext(Dispatchers.IO) {
        if (isRunning()) {
            return@withContext Result.failure(IllegalStateException("Host process is already running"))
        }

        // Новый запуск: убрать вывод, код выхода и метку «остановлено вручную» прошлого раза.
        synchronized(lock) {
            logLines.clear()
            stopping = false
            process = null
            _statusFlow.value = RuntimeStatus(
                phase = RuntimePhase.STARTING,
                logTail = emptyList(),
                updatedAtMillis = System.currentTimeMillis()
            )
        }

        try {
            val pb = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)
            pb.environment().putAll(environment)

            val proc = pb.start()
            // Это не та же критическая секция, что сброс выше: за это время stop() мог уже пройти
            // (stopping=true, process ещё null). Тогда только что запущенный процесс надо убить
            // самим, иначе останется бесхозный proot при состоянии STOPPED.
            val aborted = synchronized(lock) {
                if (stopping) {
                    true
                } else {
                    process = proc
                    false
                }
            }
            if (aborted) {
                runCatching { proc.destroyForcibly() }
                return@withContext Result.failure(IllegalStateException(HostStrings.get(R.string.rt_start_cancelled, "Startup was stopped")))
            }

            val pid = try {
                proc.javaClass.getDeclaredMethod("getPid").invoke(proc) as? Long
            } catch (_: Exception) {
                null
            }

            // stdout — разбор порта и токена.
            val outJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val current = line ?: continue
                        appendLogLine(current)
                        if (logFile != null) {
                            runCatching { logFile.appendText("[STDOUT] $current\n") }
                        }
                        val endpoint = ProcessOutputParser.parseLine(current)
                        if (endpoint != null) {
                            publishStatus { status ->
                                // Порт принимается, только пока запуск идёт и остановки не было: остаточный вывод после
                                // terminate() не может вернуть STOPPED в STARTING (сторож уже закончил,
                                // интерфейс застрял бы на запуске); URL после markReady
                                // так же не может понизить RUNNING до STARTING.
                                if (status.phase == RuntimePhase.STARTING && !stopping) {
                                    status.copy(
                                        phase = RuntimePhase.STARTING,
                                        port = endpoint.port,
                                        token = endpoint.token,
                                        pid = pid
                                    )
                                } else {
                                    status
                                }
                            }
                            onEndpointFound?.invoke(endpoint)
                        }
                    }
                }
            }
            stdoutJob = outJob

            // stderr — только в журнал (текст ошибки сторож берёт из кольцевого буфера).
            val errJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    val reader = BufferedReader(InputStreamReader(proc.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val current = line ?: continue
                        appendLogLine(current)
                        if (logFile != null) {
                            runCatching { logFile.appendText("[STDERR] $current\n") }
                        }
                    }
                }
            }
            stderrJob = errJob

            startExitWatchdog(proc, outJob, errJob)

            Result.success(proc)
        } catch (e: Exception) {
            publishStatus {
                it.copy(
                    phase = RuntimePhase.ERROR,
                    lastError = e.message ?: HostStrings.get(R.string.rt_cannot_start, "Could not start the Host process")
                )
            }
            Result.failure(e)
        }
    }

    /**
     * Дождаться выхода процесса и записать в состояние «последний вывод + код выхода».
     *
     * Обязательно отдельный корутин: при сбое запуска DSH [terminate] никто не вызовет, и без этого пути
     * интерфейс вечно показывал бы STARTING.
     */
    private fun startExitWatchdog(proc: Process, outJob: Job, errJob: Job) {
        val job = scope.launch(Dispatchers.IO) {
            val exitCode = runCatching { proc.waitFor() }.getOrNull() ?: return@launch
            // Выход процесса не значит, что каналы прочитаны: даём корутинам чтения дочитать последние строки —
            // только тогда «последняя строка» в ошибке может быть настоящей причиной (ERR_MODULE_NOT_FOUND и т. п.).
            withTimeoutOrNull(STREAM_DRAIN_TIMEOUT_MS) {
                outJob.join()
                errJob.join()
            }
            onProcessExited(proc, exitCode)
        }
        synchronized(lock) {
            watchdogJob = job
        }
    }

    private fun onProcessExited(proc: Process, exitCode: Int) {
        synchronized(lock) {
            // Остановка вручную или за это время уже новый процесс — это не сбой.
            if (stopping || process !== proc) return
            val current = _statusFlow.value
            if (current.phase != RuntimePhase.STARTING && current.phase != RuntimePhase.RUNNING) {
                return
            }
            val tail = logLines.toList()
            val detail = lastMeaningfulLine(tail) ?: HostStrings.get(R.string.rt_no_output, "no output")
            val message = if (current.phase == RuntimePhase.STARTING && current.port == null) {
                HostStrings.get(R.string.rt_host_exited_code, "The Host process exited (exit=%1\$d): %2\$s", exitCode, detail)
            } else {
                HostStrings.get(R.string.rt_host_crashed_code, "The Host process exited unexpectedly (exit=%1\$d): %2\$s", exitCode, detail)
            }
            process = null
            _statusFlow.value = current.copy(
                phase = RuntimePhase.ERROR,
                lastError = redact(message),
                exitCode = exitCode,
                logTail = tail,
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    fun markReady() {
        publishStatus { current ->
            if (current.phase == RuntimePhase.STARTING) {
                current.copy(phase = RuntimePhase.RUNNING)
            } else {
                current
            }
        }
    }

    /** После установки rootfs перевести в STOPPED (можно запускать). */
    fun markInstalled() {
        synchronized(lock) {
            logLines.clear()
            process = null
            stopping = false
            _statusFlow.value = RuntimeStatus(
                phase = RuntimePhase.STOPPED,
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    /**
     * Перевести состояние в ERROR извне (например, таймаут ожидания порта при запуске).
     *
     * Хвост последнего вывода сохраняется: ошибка показывается в настройках, по ней пользователь поймёт причину.
     */
    fun markError(message: String) {
        publishStatus { current ->
            current.copy(
                phase = RuntimePhase.ERROR,
                lastError = redact(message)
            )
        }
    }

    suspend fun terminate(): Result<Unit> = withContext(Dispatchers.IO) {
        val proc = synchronized(lock) {
            stopping = true
            process
        }

        try {
            // v4.0 §4.4: SIGTERM -> 5s -> SIGKILL
            if (proc != null && proc.isAlive) {
                proc.destroy()
                var waitedMs = 0L
                while (proc.isAlive && waitedMs < 5000L) {
                    kotlinx.coroutines.delay(200L)
                    waitedMs += 200L
                }
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
            }

            // Сначала остановить сторожа и чтение потоков, затем STOPPED — иначе сторож принял бы ручную остановку за ERROR.
            cancelJobs()
            synchronized(lock) {
                if (process === proc) process = null
                _statusFlow.value = RuntimeStatus(
                    phase = RuntimePhase.STOPPED,
                    logTail = logLines.toList(),
                    updatedAtMillis = System.currentTimeMillis()
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cancelJobs() {
        synchronized(lock) {
            watchdogJob?.cancel()
            stdoutJob?.cancel()
            stderrJob?.cancel()
            watchdogJob = null
            stdoutJob = null
            stderrJob = null
        }
    }

    private fun appendLogLine(line: String) {
        val redacted = redact(line)
        synchronized(lock) {
            logLines.addLast(redacted)
            while (logLines.size > MAX_LOG_LINES) {
                logLines.removeFirst()
            }
        }
    }

    /** Прочитать-изменить-записать состояние, всегда добавляя хвост вывода и время обновления. */
    private fun publishStatus(transform: (RuntimeStatus) -> RuntimeStatus) {
        synchronized(lock) {
            _statusFlow.value = transform(_statusFlow.value).copy(
                logTail = logLines.toList(),
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    private fun lastMeaningfulLine(lines: List<String>): String? {
        for (line in lines.asReversed()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Строки стека («at ...») пропускаются: они говорят, где упало, а не почему.
            if (trimmed.startsWith("at ")) continue
            return trimmed.take(MAX_ERROR_LINE_CHARS)
        }
        return lines.lastOrNull { it.isNotBlank() }?.trim()?.take(MAX_ERROR_LINE_CHARS)
    }

    private fun redact(text: String): String = TOKEN_PATTERN.replace(text, "token=***")

    private companion object {
        /** Строк в кольцевом буфере: хватает найти причину сбоя запуска и не раздуть поток состояния. */
        const val MAX_LOG_LINES = 120

        /** Наибольшая длина «последней строки» в тексте ошибки, чтобы весь стек не попал в интерфейс. */
        const val MAX_ERROR_LINE_CHARS = 320

        /** Сколько ждать дочитывания stdout/stderr после выхода процесса. */
        const val STREAM_DRAIN_TIMEOUT_MS = 2_000L

        /** Токены в журнале и тексте ошибок всегда вычищаются. */
        val TOKEN_PATTERN = Regex("""token=[A-Za-z0-9_\-]+""")
    }
}
