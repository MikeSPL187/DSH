package com.deepseek.dsh.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DshDao {

    @Query("SELECT * FROM hosts ORDER BY sortOrder ASC")
    fun getAllHosts(): Flow<List<HostEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHost(host: HostEntity)

    @Query("SELECT * FROM sessions WHERE hostId = :hostId ORDER BY pinned DESC, updatedAt DESC")
    fun getSessionsForHost(hostId: String): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<SessionEntity>)

    @Query("DELETE FROM sessions WHERE hostId = :hostId AND sessionId = :sessionId")
    suspend fun deleteSession(hostId: String, sessionId: String)

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

    // 会话顺序以墙钟时间为准：用户消息由 App 本地入库（seq=0），AI 消息带 Host
    // 事件 seq；若先按 seq 排，所有用户气泡会被排到 AI 回复之前。createdAt 是
    // 两端共享的时间轴，seq 只作为同一毫秒内的稳定兜底。
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

    @Query("SELECT * FROM surface_catalog WHERE clientId = :clientId")
    fun getSurfaceCatalog(clientId: String): Flow<List<SurfaceCatalogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSurfaces(surfaces: List<SurfaceCatalogEntity>)
}

/** Сессия и текст её последнего ответа. */
data class SessionPreviewRow(val sessionId: String, val content: String?)
