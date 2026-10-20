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
            normalizeConversationEntity(db)
            createMissingTargetTables(db)
            migrateLegacyMemory(db)
            val columns = tableColumns(db, "usage_record")
            if (columns.isEmpty()) {
                createUsageRecordTable(db, "usage_record")
                createUsageRecordIndexes(db)
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
            backfillUsageIfEmpty(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}


/**
 * Older fork/branch databases can contain ConversationEntity.chat_model_id and indexes that
 * 3.1 removed. Drop indexes first, then the extra columns; Room validates every column and index.
 */
internal fun normalizeConversationEntity(db: SupportSQLiteDatabase) {
    val actual = tableColumns(db, "ConversationEntity")
    if (actual.isEmpty()) return
    val expected = setOf(
        "id", "assistant_id", "title", "nodes", "create_at", "update_at", "suggestions",
        "is_pinned", "custom_system_prompt", "mode_injection_ids", "lorebook_ids",
        "workspace_cwd", "folder_id", "config",
    )
    val indexes = db.query(
        "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'ConversationEntity' AND sql IS NOT NULL"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) }
    }
    indexes.forEach { name ->
        db.execSQL("DROP INDEX IF EXISTS `${name.replace("`", "``")}`")
    }
    for (extra in actual - expected) {
        db.execSQL("ALTER TABLE `ConversationEntity` DROP COLUMN `${extra.replace("`", "``")}`")
    }
    // excp/branch schema 31 has no config column; create it with the target default.
    if ("config" !in actual) {
        db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `config` TEXT NOT NULL DEFAULT ''")
    }
}

/** Create tables that exist in target schema 32 but were absent from older/branch schema 31. */
internal fun createMissingTargetTables(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS `memory_file` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, `content` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`assistant_id`, `path`))")
    db.execSQL("CREATE TABLE IF NOT EXISTS `memory_consolidation` (`conversation_id` TEXT NOT NULL, `consolidated_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE TABLE IF NOT EXISTS `media_creation_session` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `draft` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    db.execSQL("CREATE TABLE IF NOT EXISTS `media_creation_node` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `selected_record_id` TEXT NOT NULL, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_creation_node_session_id` ON `media_creation_node` (`session_id`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `media_creation_record` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `node_id` TEXT NOT NULL, `provider_id` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `model_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `prompt` TEXT NOT NULL, `params` TEXT NOT NULL, `inputs` TEXT NOT NULL, `status` TEXT NOT NULL, `task_id` TEXT, `error` TEXT, `outputs` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`node_id`) REFERENCES `media_creation_node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_creation_record_session_id` ON `media_creation_record` (`session_id`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_creation_record_node_id` ON `media_creation_record` (`node_id`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `translation_history` (`id` TEXT NOT NULL, `source_text` TEXT NOT NULL, `translated_text` TEXT NOT NULL, `target_language` TEXT NOT NULL, `model_id` TEXT NOT NULL, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_history_create_at` ON `translation_history` (`create_at`)")
    createUsageRecordTable(db, "usage_record")
    createUsageRecordIndexes(db)
}

/** excp schema 31 stores memories in MemoryEntity; target 32 uses memory_file. */
internal fun migrateLegacyMemory(db: SupportSQLiteDatabase) {
    val hasLegacy = db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='MemoryEntity'").use { it.moveToFirst() }
    if (!hasLegacy) return
    val grouped = linkedMapOf<String, MutableList<String>>()
    db.query("SELECT assistant_id, content FROM `MemoryEntity` ORDER BY id").use { cursor ->
        while (cursor.moveToNext()) {
            val assistantId = cursor.getString(0) ?: continue
            val content = cursor.getString(1) ?: continue
            grouped.getOrPut(assistantId) { mutableListOf() }.add(content)
        }
    }
    val now = System.currentTimeMillis()
    for ((assistantId, contents) in grouped) {
        val profile = legacyMemoriesToProfile(contents) ?: continue
        db.execSQL(
            "INSERT OR REPLACE INTO `memory_file` (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(assistantId, me.rerere.rikkahub.data.model.MemoryFile.PROFILE_PATH, profile, now),
        )
        db.execSQL(
            "INSERT OR REPLACE INTO `memory_file` (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(assistantId, "_legacy/imported_memories_$now.md", contents.joinToString("\n"), now),
        )
    }
}

/** Backfill usage from assistant messages when the target table was newly created. */
private fun backfillUsageIfEmpty(db: SupportSQLiteDatabase) {
    val count = db.query("SELECT COUNT(*) FROM `usage_record`").use { if (it.moveToFirst()) it.getLong(0) else 0L }
    if (count > 0) return
    db.execSQL(
        """
        INSERT INTO usage_record (
            id, created_at, source, provider_id, model_id, model_name, assistant_id,
            conversation_id, node_id, prompt_tokens, completion_tokens, cached_tokens, total_tokens
        )
        SELECT
            lower(hex(randomblob(16))),
            COALESCE(CAST(strftime('%s', substr(json_extract(j.value, '$.createdAt'), 1, 19), 'utc') AS INTEGER) * 1000, c.create_at),
            'CHAT', NULL, json_extract(j.value, '$.modelId'), json_extract(j.value, '$.modelSnapshot.modelId'),
            c.assistant_id, mn.conversation_id, NULL,
            COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0),
            COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0),
            COALESCE(json_extract(j.value, '$.usage.cachedTokens'), 0),
            COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0) + COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0)
        FROM message_node mn
        JOIN ConversationEntity c ON c.id = mn.conversation_id,
            json_each(CASE WHEN json_valid(mn.messages) THEN mn.messages ELSE '[]' END) j
        WHERE json_extract(j.value, '$.role') = 'assistant'
            AND json_type(j.value, '$.usage') = 'object'
            AND (COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0) > 0
                OR COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0) > 0)
        """.trimIndent()
    )
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
            `total_tokens` INTEGER NOT NULL,
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
