package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_30_32"

/** Official 3.1 backfills message usage while fork.15 keeps its own schema-31 table. */
val Migration_30_32 = object : Migration(30, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(30, 32)
        db.beginTransaction()
        try {
            normalizeConversationEntity(db)
            createMissingTargetTables(db)
            migrateLegacyMemory(db)
            createUsageRecordTable(db, "usage_record")
            createUsageRecordIndexes(db)
            db.execSQL(
                """
                INSERT INTO usage_record (
                    id, created_at, source, provider_id, model_id, model_name, assistant_id,
                    conversation_id, node_id, prompt_tokens, completion_tokens, cached_tokens, total_tokens
                )
                SELECT
                    lower(hex(randomblob(16))),
                    COALESCE(
                        CAST(strftime('%s', substr(json_extract(j.value, '$.createdAt'), 1, 19), 'utc') AS INTEGER) * 1000,
                        c.create_at
                    ),
                    'CHAT', NULL, json_extract(j.value, '$.modelId'),
                    json_extract(j.value, '$.modelSnapshot.modelId'), c.assistant_id,
                    mn.conversation_id, NULL,
                    COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0),
                    COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0),
                    COALESCE(json_extract(j.value, '$.usage.cachedTokens'), 0),
                    COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0) +
                        COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0)
                FROM message_node mn
                JOIN ConversationEntity c ON c.id = mn.conversation_id,
                    json_each(CASE WHEN json_valid(mn.messages) THEN mn.messages ELSE '[]' END) j
                WHERE json_extract(j.value, '$.role') = 'assistant'
                    AND json_type(j.value, '$.usage') = 'object'
                    AND (
                        COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0) > 0
                        OR COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0) > 0
                    )
                """.trimIndent()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

