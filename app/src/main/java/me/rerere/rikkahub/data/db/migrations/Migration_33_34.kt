package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * fork schema 34 = fork 33 + official 3.1.1 SSH remote workspace columns.
 *
 * Idempotent on purpose: a database that already came through official 3.1.1 (schema 32) or a
 * backup aligned to it may already have these columns, and ALTER TABLE would fail otherwise.
 */
val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(33, 34)
        db.beginTransaction()
        try {
            val columns = db.query("PRAGMA table_info(`workspaces`)").use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) cursor.getString(1)?.let(::add)
                }
            }
            if ("type" !in columns) {
                db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'LOCAL'")
            }
            if ("ssh_config" !in columns) {
                db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `ssh_config` TEXT")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
