package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * fork.22 wrote legacy memory paths without a leading slash. The UI normalizes paths to
 * `/...`, so those rows opened as "missing". Repair them in place.
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE OR REPLACE `memory_file` SET `path` = '/' || `path` WHERE `path` NOT LIKE '/%'"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
