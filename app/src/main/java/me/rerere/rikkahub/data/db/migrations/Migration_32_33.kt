package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * fork.22 wrote legacy memory paths without a leading slash. The UI normalizes paths to
 * `/...`, so those rows opened as "missing". Repair them in place, losslessly.
 *
 * Also normalizes `usage_record`: a database coming from official 3.1.1 is already at schema 32 but
 * has the official 11-column INTEGER-id table, so this boundary is the only one it passes through
 * before Room validates schema 34. The helper is idempotent and a no-op on a fork table.
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        db.beginTransaction()
        try {
            normalizeUsageRecord(db)
            applyMemoryPathRepair(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

/**
 * Applies [MemoryPathRepair.plan] with parameterized writes. Only `path` (and, on a real conflict,
 * the loser's archived copy / the winner's content) is touched, so an unrelated row keeps both its
 * content and its `updated_at`.
 */
internal fun applyMemoryPathRepair(db: SupportSQLiteDatabase) {
    val rows = db.query("SELECT assistant_id, path, content, updated_at FROM `memory_file`").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val assistantId = cursor.getString(0) ?: continue
                val path = cursor.getString(1) ?: continue
                add(MemoryFileRow(assistantId, path, cursor.getString(2) ?: "", cursor.getLong(3)))
            }
        }
    }
    for (step in MemoryPathRepair.plan(rows)) {
        when (step) {
            is MemoryPathStep.PrefixPath -> db.execSQL(
                "UPDATE `memory_file` SET `path` = ? WHERE `assistant_id` = ? AND `path` = ?",
                arrayOf<Any?>(step.to, step.assistantId, step.from),
            )

            is MemoryPathStep.DropRelative -> db.execSQL(
                "DELETE FROM `memory_file` WHERE `assistant_id` = ? AND `path` = ?",
                arrayOf<Any?>(step.assistantId, step.path),
            )

            is MemoryPathStep.KeepLegacy -> db.execSQL(
                "INSERT INTO `memory_file` (`assistant_id`, `path`, `content`, `updated_at`) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(step.assistantId, step.path, step.content, step.updatedAt),
            )

            is MemoryPathStep.Touch -> db.execSQL(
                "UPDATE `memory_file` SET `updated_at` = MAX(`updated_at`, ?) WHERE `assistant_id` = ? AND `path` = ?",
                arrayOf<Any?>(step.updatedAt, step.assistantId, step.path),
            )

            is MemoryPathStep.WriteCanonical -> db.execSQL(
                "UPDATE `memory_file` SET `content` = ?, `updated_at` = ? WHERE `assistant_id` = ? AND `path` = ?",
                arrayOf<Any?>(step.content, step.updatedAt, step.assistantId, step.path),
            )
        }
    }
}
