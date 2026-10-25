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
            // Official 3.1/3.1.1 ships an 11-column `id INTEGER AUTOINCREMENT` usage_record while the
            // fork table has a TEXT id plus node_id/total_tokens. The shared helper keeps 31 -> 32 and
            // 32 -> 33 on one normalization path (see normalizeUsageRecord).
            normalizeUsageRecord(db)
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

/**
 * Archive path for the raw legacy memories kept beside the generated profile.
 *
 * The leading slash is required: `memory_file` paths are stored as `/...` and every read/write goes
 * through `MemoryFile.normalizePath` + an exact-path lookup (`MemoryDAO.getFile`). A relative path is
 * listed in the UI but can never be opened, saved or deleted again — the row is orphaned. This is the
 * same defect the schema 32 -> 33 repair fixes for `Migration_27_28`'s old rows.
 */
internal fun legacyMemoryArchivePath(now: Long): String = "/_legacy/imported_memories_$now.md"

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
            arrayOf<Any?>(assistantId, legacyMemoryArchivePath(now), contents.joinToString("\n"), now),
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

/** Column name -> declared type, used to tell an official INTEGER-id table from the fork TEXT one. */
internal fun tableColumnTypes(db: SupportSQLiteDatabase, table: String): Map<String, String> =
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                put(name, cursor.getString(2) ?: "")
            }
        }
    }

/** Target schema-34 columns of usage_record, in the order Room declares them. */
internal val usageRecordColumns = listOf(
    "id", "created_at", "source", "provider_id", "model_id", "model_name", "assistant_id",
    "conversation_id", "node_id", "prompt_tokens", "completion_tokens", "cached_tokens", "total_tokens",
)

/** Column names alone do not prove a fork table is valid (older branches had DEFAULT 0 drift). */
private fun hasCanonicalUsageConstraints(db: SupportSQLiteDatabase): Boolean {
    val textColumns = setOf("id", "source", "provider_id", "model_id", "model_name", "assistant_id", "conversation_id", "node_id")
    val requiredColumns = setOf("id", "created_at", "source", "prompt_tokens", "completion_tokens", "cached_tokens", "total_tokens")
    return db.query("PRAGMA table_info(`usage_record`)").use { cursor ->
        var matches = true
        while (cursor.moveToNext()) {
            val name = cursor.getString(1)
            val type = if (name in textColumns) "TEXT" else "INTEGER"
            matches = matches && cursor.getString(2).equals(type, ignoreCase = true) &&
                (cursor.getInt(3) != 0) == (name in requiredColumns) &&
                cursor.isNull(4) && cursor.getInt(5) == (if (name == "id") 1 else 0)
        }
        matches
    }
}

/**
 * Normalize any schema-31/32 `usage_record` into the fork table, in place and idempotently.
 *
 * Official 3.1/3.1.1 declares `id INTEGER PRIMARY KEY AUTOINCREMENT` with 11 columns; the fork table
 * is a TEXT id plus node_id/total_tokens. Room validates every column, so a database that came from
 * official 3.1.1 (schema 32) fails validation — and a backup aligned to it fails the import check —
 * unless this runs before 32 -> 33. A fork-shaped table is a no-op apart from recreating indexes.
 *
 * Existing ids, token counts and an existing total_tokens survive; when the column is missing
 * total_tokens is prompt + completion. INTEGER ids are CAST so the TEXT column really stores text.
 */
internal fun normalizeUsageRecord(db: SupportSQLiteDatabase) {
    val actual = tableColumnTypes(db, "usage_record")
    if (actual.isEmpty()) {
        createUsageRecordTable(db, "usage_record")
        createUsageRecordIndexes(db)
        return
    }
    val idIsText = actual["id"]?.contains("TEXT", ignoreCase = true) == true
    if (idIsText && actual.keys == usageRecordColumns.toSet() && hasCanonicalUsageConstraints(db)) {
        createUsageRecordIndexes(db)
        return
    }
    val oldIndexes = db.query(
        "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'usage_record' AND sql IS NOT NULL"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) }
    }.filterNot { it.startsWith("sqlite_autoindex") }
    oldIndexes.forEach { db.execSQL("DROP INDEX IF EXISTS `${it.replace("`", "``")}`") }

    createUsageRecordTable(db, "usage_record_new")
    fun col(name: String, fallback: String): String = if (name in actual) "`$name`" else fallback
    val prompt = "COALESCE(${col("prompt_tokens", "0")}, 0)"
    val completion = "COALESCE(${col("completion_tokens", "0")}, 0)"
    val cached = "COALESCE(${col("cached_tokens", "0")}, 0)"
    val total = "COALESCE(${col("total_tokens", "$prompt + $completion")}, $prompt + $completion)"
    val id = when {
        "id" !in actual -> "lower(hex(randomblob(16)))"
        actual.getValue("id").contains("INT", ignoreCase = true) -> "CAST(`id` AS TEXT)"
        else -> "`id`"
    }
    db.execSQL(
        """
        INSERT INTO `usage_record_new` (
            id, created_at, source, provider_id, model_id, model_name, assistant_id,
            conversation_id, node_id, prompt_tokens, completion_tokens, cached_tokens, total_tokens
        )
        SELECT
            $id,
            ${col("created_at", "0")},
            COALESCE(${col("source", "'CHAT'")}, 'CHAT'),
            ${col("provider_id", "NULL")},
            ${col("model_id", "NULL")},
            ${col("model_name", "NULL")},
            ${col("assistant_id", "NULL")},
            ${col("conversation_id", "NULL")},
            ${col("node_id", "NULL")},
            $prompt, $completion, $cached, $total
        FROM `usage_record`
        """.trimIndent()
    )
    db.execSQL("DROP TABLE `usage_record`")
    db.execSQL("ALTER TABLE `usage_record_new` RENAME TO `usage_record`")
    createUsageRecordIndexes(db)
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
