package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/** Normalize either official 3.1 schema-31 or fork.15 schema-31 into the unified schema-32 table. */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(31, 32)
        db.beginTransaction()
        try {
            val columns = tableColumns(db, "usage_record")
            if (columns.isEmpty()) {
                createUsageRecordTable(db, "usage_record")
            } else {
                val oldIndexes = db.query(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'usage_record'"
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) cursor.getString(0)?.let(::add)
                    }
                }.filterNot { it.startsWith("sqlite_autoindex") }
                oldIndexes.forEach { db.execSQL("DROP INDEX IF EXISTS `${it.replace("`", "``")}`") }

                createUsageRecordTable(db, "usage_record_new")
                fun col(name: String, fallback: String): String =
                    if (name in columns) "`${name}`" else fallback
                val prompt = "COALESCE(${col("prompt_tokens", "0")}, 0)"
                val completion = "COALESCE(${col("completion_tokens", "0")}, 0)"
                val cached = "COALESCE(${col("cached_tokens", "0")}, 0)"
                db.execSQL(
                    """
                    INSERT INTO `usage_record_new` (
                        id, created_at, source, provider_id, model_id, model_name, assistant_id,
                        conversation_id, node_id, prompt_tokens, completion_tokens, cached_tokens, total_tokens
                    )
                    SELECT
                        ${col("id", "lower(hex(randomblob(16)))")},
                        ${col("created_at", "0")},
                        COALESCE(${col("source", "'CHAT'")}, 'CHAT'),
                        ${col("provider_id", "NULL")},
                        ${col("model_id", "NULL")},
                        ${col("model_name", "NULL")},
                        ${col("assistant_id", "NULL")},
                        ${col("conversation_id", "NULL")},
                        ${col("node_id", "NULL")},
                        $prompt, $completion, $cached,
                        COALESCE(${col("total_tokens", "$prompt + $completion")}, $prompt + $completion)
                    FROM `usage_record`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `usage_record`")
                db.execSQL("ALTER TABLE `usage_record_new` RENAME TO `usage_record`")
                createUsageRecordIndexes(db)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> =
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) cursor.getString(1)?.let(::add)
        }
    }

internal fun createUsageRecordTable(db: SupportSQLiteDatabase, table: String) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$table` (
            `id` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `source` TEXT NOT NULL,
            `provider_id` TEXT,
            `model_id` TEXT,
            `model_name` TEXT,
            `assistant_id` TEXT,
            `conversation_id` TEXT,
            `node_id` TEXT,
            `prompt_tokens` INTEGER NOT NULL,
            `completion_tokens` INTEGER NOT NULL,
            `cached_tokens` INTEGER NOT NULL,
            `total_tokens` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`id`)
        )
        """.trimIndent()
    )
}

internal fun createUsageRecordIndexes(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_conversation_id` ON `usage_record` (`conversation_id`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` ON `usage_record` (`created_at`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_model_id` ON `usage_record` (`model_id`)")
}
