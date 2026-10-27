package com.dsh.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Таблицы локального кэша Room.
 */

@Entity(tableName = "sessions", primaryKeys = ["hostId", "sessionId"])
data class SessionEntity(
    val hostId: String,
    val sessionId: String,
    val title: String?,
    val workspacePath: String?,
    val status: String?,
    val updatedAt: Long?,
    val lastSeq: Long?,
    val archived: Int = 0,
    val pinned: Int = 0,
    val model: String? = null,
    val permissionPreset: String? = null
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: String = "default",
    val defaultModel: String,
    val defaultReasoningLevel: String,
    val idleStopMinutes: Int,
    val keepAliveOnTask: Boolean,
    val autoBootRecovery: Boolean,
    val highRiskConfirm: Boolean,
    val showCodingView: Boolean,
    val transcriptView: String,
    val enableSoundNotice: Boolean,
    val notificationsEnabled: Boolean = true,
    val notifyOnTurnComplete: Boolean = true,
    val notifyOnError: Boolean = true,
    val remoteHostUrl: String = "",
    val remoteHostToken: String = "",
    val workspacePath: String
)

@Entity(tableName = "messages", primaryKeys = ["hostId", "sessionId", "messageId"])
data class MessageEntity(
    val hostId: String,
    val sessionId: String,
    val messageId: String,
    val role: String?,
    val seq: Long?,
    val contentJson: String?,
    val createdAt: Long?,
    val status: String?
)
