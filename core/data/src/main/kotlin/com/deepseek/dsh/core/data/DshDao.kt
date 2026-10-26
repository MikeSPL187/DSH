package com.deepseek.dsh.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DshDao {

    @Query("SELECT * FROM sessions WHERE hostId = :hostId ORDER BY pinned DESC, updatedAt DESC")
    fun getSessionsForHost(hostId: String): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<SessionEntity>)

    @Query("UPDATE sessions SET model = :model WHERE hostId = :hostId AND sessionId = :sessionId")
    suspend fun updateSessionModel(hostId: String, sessionId: String, model: String)

    @Query("UPDATE sessions SET archived = :archived WHERE hostId = :hostId AND sessionId = :sessionId")
    suspend fun updateSessionArchived(hostId: String, sessionId: String, archived: Int)

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE hostId = :hostId AND sessionId = :sessionId")
    suspend fun touchSession(hostId: String, sessionId: String, updatedAt: Long)

    @Query("UPDATE sessions SET permissionPreset = :preset WHERE hostId = :hostId AND sessionId = :sessionId")
    suspend fun updateSessionPermission(hostId: String, sessionId: String, preset: String)

    @Query("SELECT * FROM app_settings WHERE id = 'default'")
    fun getAppSettings(): Flow<AppSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAppSettings(settings: AppSettingsEntity)

    // Порядок — по времени: у сообщений, записанных приложением, seq=0, у ответов —
    // seq события Host; сортировка по seq поставила бы все сообщения пользователя перед ответами.
    // createdAt — общая шкала, seq лишь упорядочивает записи одной миллисекунды.
    /** Последний ответ агента в каждой сессии Host — подпись строки в меню. */
    @Query(
        "SELECT m.sessionId AS sessionId, m.contentJson AS content FROM messages m WHERE m.hostId = :hostId AND m.role = 'ASSISTANT' " +
            "AND m.createdAt = (SELECT MAX(x.createdAt) FROM messages x WHERE x.hostId = m.hostId AND x.sessionId = m.sessionId AND x.role = 'ASSISTANT')",
    )
    fun lastAssistantMessages(hostId: String): Flow<List<SessionPreviewRow>>

    @Query("SELECT * FROM messages WHERE hostId = :hostId AND sessionId = :sessionId ORDER BY createdAt ASC, seq ASC, messageId ASC")
    fun getMessages(hostId: String, sessionId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)
}

/** Сессия и текст её последнего ответа. */
data class SessionPreviewRow(val sessionId: String, val content: String?)
