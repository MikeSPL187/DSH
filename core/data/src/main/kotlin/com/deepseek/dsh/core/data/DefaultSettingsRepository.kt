package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.model.AppSettings
import com.deepseek.dsh.core.model.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @param secretCipher шифрование токена удалённого Host (аудит F-04/N-14); null — хранить открыто,
 *   только для тестов.
 */
class DefaultSettingsRepository(
    private val dao: DshDao,
    private val scope: CoroutineScope,
    private val secretCipher: SecretCipher? = null,
) : SettingsRepository {

    /** Прочитать токен: шифр расшифровывается, старый открытый текст — как есть; не расшифровать (ключ утерян) — токена нет. */
    private fun revealToken(stored: String): String =
        secretCipher?.decrypt(stored) ?: if (secretCipher == null) stored else ""

    /** Записать токен: если шифрование не удалось, открытый текст не сохраняется — пусть пользователь введёт снова. */
    private fun sealToken(plain: String): String {
        val cipher = secretCipher ?: return plain
        return runCatching { cipher.encrypt(plain) }.getOrDefault("")
    }

    init {
        // Разовая миграция: старые версии хранили токен открыто — шифруем при первом чтении.
        if (secretCipher != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val entity = dao.getAppSettings().first() ?: return@runCatching
                    val token = entity.remoteHostToken
                    if (token.isNotEmpty() && !secretCipher.isEncrypted(token)) {
                        dao.saveAppSettings(entity.copy(remoteHostToken = sealToken(token)))
                    }
                }
            }
        }
    }

    val settingsFlow: StateFlow<AppSettings> = dao.getAppSettings()
        .map { entity ->
            if (entity != null) {
                AppSettings(
                    defaultModel = entity.defaultModel,
                    defaultReasoningLevel = entity.defaultReasoningLevel,
                    idleStopMinutes = entity.idleStopMinutes,
                    keepAliveOnTask = entity.keepAliveOnTask,
                    autoBootRecovery = entity.autoBootRecovery,
                    highRiskConfirm = entity.highRiskConfirm,
                    showCodingView = entity.showCodingView,
                    transcriptView = entity.transcriptView,
                    enableSoundNotice = entity.enableSoundNotice,
                    notificationsEnabled = entity.notificationsEnabled,
                    notifyOnTurnComplete = entity.notifyOnTurnComplete,
                    notifyOnError = entity.notifyOnError,
                    remoteHostUrl = entity.remoteHostUrl,
                    remoteHostToken = revealToken(entity.remoteHostToken),
                    workspacePath = entity.workspacePath
                )
            } else {
                AppSettings()
            }
        }.flowOn(Dispatchers.IO).stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings()
        )

    override suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        settingsFlow.value
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        val updated = transform(settingsFlow.value)
        dao.saveAppSettings(
            AppSettingsEntity(
                id = "default",
                defaultModel = updated.defaultModel,
                defaultReasoningLevel = updated.defaultReasoningLevel,
                idleStopMinutes = updated.idleStopMinutes,
                keepAliveOnTask = updated.keepAliveOnTask,
                autoBootRecovery = updated.autoBootRecovery,
                highRiskConfirm = updated.highRiskConfirm,
                showCodingView = updated.showCodingView,
                transcriptView = updated.transcriptView,
                enableSoundNotice = updated.enableSoundNotice,
                notificationsEnabled = updated.notificationsEnabled,
                notifyOnTurnComplete = updated.notifyOnTurnComplete,
                notifyOnError = updated.notifyOnError,
                remoteHostUrl = updated.remoteHostUrl,
                remoteHostToken = sealToken(updated.remoteHostToken),
                workspacePath = updated.workspacePath
            )
        )
    }
}

