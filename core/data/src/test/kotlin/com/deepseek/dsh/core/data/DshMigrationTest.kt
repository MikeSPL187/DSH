package com.deepseek.dsh.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Аудит F-03/N-13: обновление с базы версии 2 не должно терять историю.
 * База v2 создаётся по схеме релиза до MIGRATION_2_3 (без пяти новых колонок
 * app_settings), затем открывается текущим Room; Room сверяет итоговую схему с сущностями.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DshMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun createV2(name: String) {
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `hosts` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `baseUrl` TEXT, `tlsFingerprint` TEXT, `deviceId` TEXT, `status` TEXT, `lastSeenAt` INTEGER, `sortOrder` INTEGER, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `sessions` (`hostId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `title` TEXT, `workspacePath` TEXT, `status` TEXT, `updatedAt` INTEGER, `lastSeq` INTEGER, `archived` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `model` TEXT, `permissionPreset` TEXT, PRIMARY KEY(`hostId`, `sessionId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `messages` (`hostId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `messageId` TEXT NOT NULL, `role` TEXT, `seq` INTEGER, `contentJson` TEXT, `createdAt` INTEGER, `status` TEXT, PRIMARY KEY(`hostId`, `sessionId`, `messageId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `drafts` (`hostId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `text` TEXT, `attachmentsJson` TEXT, `updatedAt` INTEGER, PRIMARY KEY(`hostId`, `sessionId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `approvals` (`hostId` TEXT NOT NULL, `approvalId` TEXT NOT NULL, `sessionId` TEXT, `payloadJson` TEXT, `state` TEXT, `createdAt` INTEGER, PRIMARY KEY(`hostId`, `approvalId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `surface_catalog` (`clientId` TEXT NOT NULL, `pluginId` TEXT NOT NULL, `slot` TEXT NOT NULL, `title` TEXT, `kind` TEXT, `orderHint` INTEGER, `capabilitiesJson` TEXT, `updatedAt` INTEGER, PRIMARY KEY(`clientId`, `pluginId`, `slot`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`id` TEXT NOT NULL, `defaultModel` TEXT NOT NULL, `defaultReasoningLevel` TEXT NOT NULL, `idleStopMinutes` INTEGER NOT NULL, `keepAliveOnTask` INTEGER NOT NULL, `autoBootRecovery` INTEGER NOT NULL, `highRiskConfirm` INTEGER NOT NULL, `showCodingView` INTEGER NOT NULL, `transcriptView` TEXT NOT NULL, `enableSoundNotice` INTEGER NOT NULL, `workspacePath` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO sessions VALUES ('local-host','s1','Важная сессия','/w','idle',1,7,0,1,'deepseek-chat','workspace-write')")
            db.execSQL("INSERT INTO messages VALUES ('local-host','s1','m1','USER',1,'Привет',1,NULL)")
            db.execSQL("INSERT INTO app_settings VALUES ('default','deepseek-chat','high',7,1,0,1,1,'detailed',1,'/storage/w')")
            db.version = 2
        }
    }

    @Test
    fun migratesV2DatabaseWithoutLosingData() = runBlocking {
        val name = "migration-test.db"
        createV2(name)
        val db = Room.databaseBuilder(context, DshDatabase::class.java, name)
            .addMigrations(*DshMigrations.ALL)
            .build()
        try {
            val dao = db.dshDao()
            val messages = dao.getMessages("local-host", "s1").first()
            assertEquals(listOf("Привет"), messages.map { it.contentJson })
            // v3 → v4: таблицы, которые приложение не читало, удалены.
            val tables = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
            assertTrue(tables.none { it in setOf("hosts", "drafts", "approvals", "surface_catalog") })
            val settings = dao.getAppSettings().first()
            assertNotNull(settings)
            assertEquals(7, settings!!.idleStopMinutes)
            assertEquals("detailed", settings.transcriptView)
            assertTrue("new columns get their defaults", settings.notificationsEnabled && settings.notifyOnError)
            assertEquals("", settings.remoteHostUrl)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
