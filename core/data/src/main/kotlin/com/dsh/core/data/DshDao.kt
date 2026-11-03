package com.dsh.core.data

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

    /** Отметки «закреплено» по полному набору Host: сессии из [ids] — 1, остальные — 0. */
    @Query("UPDATE sessions SET pinned = CASE WHEN sessionId IN (:ids) THEN 1 ELSE 0 END WHERE hostId = :hostId")
    suspend fun syncSessionsPinned(hostId: String, ids: List<String>)

    /** Отметки «в архиве» по полному набору Host: сессии из [ids] — 1, остальные — 0. */
    @Query("UPDATE sessions SET archived = CASE WHEN sessionId IN (:ids) THEN 1 ELSE 0 END WHERE hostId = :hostId")
    suspend fun syncSessionsArchived(hostId: String, ids: List<String>)

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE hostId = :hostId AND sessionId = :sessionId")
    suspend fun touchSession(hostId: String, sessionId: String, updatedAt: Long)

    @Query("UPDATE sessions SET permissionPreset = :preset WHERE hostId = :hostId AND sessionId = :sessionId")
    suspend fun updateSessionPermission(hostId: String, sessionId: String, preset: String)

    @Query("SELECT * FROM app_settings WHERE id = 'default'")
    fun getAppSettings(): Flow<AppSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAppSettings(settings: AppSettingsEntity)

    // Порядок — по seq события Host: он растёт по всем событиям сессии. createdAt — время получения на телефоне;
    // после перечитывания истории оно перемешано, и лента рассыпалась (живой Host: шаги хода над вопросом,
    // план в начале сессии). Своих записей с seq=0 приложение больше не делает (сообщение приходит эхом Host);
    // у события без seq — время получения, то есть «в конец». createdAt — только для равных seq.
    /** Последний ответ агента в каждой сессии Host — подпись строки в меню. */
    @Query(
        "SELECT m.sessionId AS sessionId, m.contentJson AS content FROM messages m WHERE m.hostId = :hostId AND m.role = 'ASSISTANT' " +
            "AND m.seq = (SELECT MAX(x.seq) FROM messages x WHERE x.hostId = m.hostId AND x.sessionId = m.sessionId AND x.role = 'ASSISTANT')",
    )
    fun lastAssistantMessages(hostId: String): Flow<List<SessionPreviewRow>>

    /**
     * Последние [limit] сообщений сессии по времени (G-17): каждый кадр перечитывает список, и долгие сессии
     * перечитывали всю историю. Более раннее Host отдаёт по прокрутке (`loadOlderHistory`).
     */
    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE hostId = :hostId AND sessionId = :sessionId " +
            "ORDER BY seq DESC, createdAt DESC, messageId DESC LIMIT :limit) ORDER BY seq ASC, createdAt ASC, messageId ASC",
    )
    fun getMessages(hostId: String, sessionId: String, limit: Int = MESSAGE_WINDOW): Flow<List<MessageEntity>>

    /** Сообщения сессий, которых в кэше больше нет (сессия удалена). */
    @Query("DELETE FROM messages WHERE NOT EXISTS (SELECT 1 FROM sessions s WHERE s.hostId = messages.hostId AND s.sessionId = messages.sessionId)")
    suspend fun deleteOrphanMessages(): Int

    /** Всё, кроме последних [keep] сообщений сессии: локальный кэш, история остаётся у Host. */
    @Query(
        "DELETE FROM messages WHERE hostId = :hostId AND sessionId = :sessionId AND messageId NOT IN " +
            "(SELECT messageId FROM messages WHERE hostId = :hostId AND sessionId = :sessionId ORDER BY seq DESC, createdAt DESC LIMIT :keep)",
    )
    suspend fun trimSessionMessages(hostId: String, sessionId: String, keep: Int): Int

    /** Сессии, где сообщений больше [keep]. */
    @Query("SELECT hostId, sessionId FROM messages GROUP BY hostId, sessionId HAVING COUNT(*) > :keep")
    suspend fun sessionsWithMoreMessagesThan(keep: Int): List<SessionKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)
}

/** Сессия Host. */
data class SessionKey(val hostId: String, val sessionId: String)

/** Сколько последних сообщений сессии читается в чат. */
const val MESSAGE_WINDOW = 1500

/** Сколько сообщений сессии хранится локально. */
const val MESSAGE_KEEP = 3000

/** Сессия и текст её последнего ответа. */
data class SessionPreviewRow(val sessionId: String, val content: String?)
