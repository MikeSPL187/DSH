package com.deepseek.dsh.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 显式迁移。新增实体字段时：提高 [DshDatabase] 的 version，在这里追加
 * `MIGRATION_n_(n+1)`，并提交 `core/data/schemas/` 下导出的新 schema。
 */
object DshMigrations {

    /** v2 → v3：通知开关与远程 Host 连接设置（commit 7610ba9）。 */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `notificationsEnabled` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `notifyOnTurnComplete` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `notifyOnError` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `remoteHostUrl` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `remoteHostToken` TEXT NOT NULL DEFAULT ''")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3)
}
