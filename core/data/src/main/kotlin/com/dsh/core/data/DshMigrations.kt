package com.dsh.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Явные миграции Room: при изменении сущностей поднять version в [DshDatabase], добавить
 * `MIGRATION_n_(n+1)` сюда и закоммитить новую схему из `core/data/schemas/`.
 * Данные пользователя (сессии, сообщения, настройки) миграции не теряют — см. DshMigrationTest.
 */
object DshMigrations {

    /** v2 → v3: переключатели уведомлений и подключение к удалённому Host. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `notificationsEnabled` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `notifyOnTurnComplete` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `notifyOnError` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `remoteHostUrl` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `remoteHostToken` TEXT NOT NULL DEFAULT ''")
        }
    }

    /** v3 → v4: удалены таблицы, которые приложение не читало (hosts, drafts, approvals, surface_catalog). */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (table in listOf("hosts", "drafts", "approvals", "surface_catalog")) db.execSQL("DROP TABLE IF EXISTS `$table`")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4)
}
