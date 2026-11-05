package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * schema 34 -> 35：会话表补上「家族根」和「归库锁」两列。
 *
 * 只加列，不碰任何已有数据：`nodes`（消息正文）、`title`、`folder_id`、`is_pinned`、`config` 全部原样保留，
 * 表上已有的索引和 FTS 关联（`message_fts` 靠 id 手动关联，没有触发器）也不受影响——SQLite 的
 * `ALTER TABLE ... ADD COLUMN` 只改表定义，不重建表，所以不需要「建新表 -> 拷数据 -> 改名」那一套。
 *
 * 两列的默认值让旧数据直接可用，不需要回填：
 * - `lineage_id` 空串 = 「以自身 id 为根」，查询用 `COALESCE(NULLIF(lineage_id, ''), id)` 兜底。
 *   旧会话本来就没有分叉记录，空串正好表达「自己是根」，省掉一次全表写。
 * - `folder_assignment_locked` 0 = 没有手动归过档，自动整理可以处理。
 *
 * 幂等：备份还原、或者别的分支已经手动加过这两列时，`ALTER TABLE ADD COLUMN` 会抛
 * 「duplicate column name」让整库打不开，所以先看 PRAGMA 再决定加哪些。
 */
internal data class SchemaColumn(val name: String, val definition: String) {
    val addSql: String
        get() = "ALTER TABLE `conversationentity` ADD COLUMN `$name` $definition"
}

/** 迁移要加的列，顺序即执行顺序。定义与 [me.rerere.rikkahub.data.db.entity.ConversationEntity] 的属性一致。 */
internal val conversationLineageColumns: List<SchemaColumn> = listOf(
    SchemaColumn("lineage_id", "TEXT NOT NULL DEFAULT ''"),
    SchemaColumn("folder_assignment_locked", "INTEGER NOT NULL DEFAULT 0"),
)

/** 已有列里还缺哪些列。已经存在的列一律跳过，宁可少写也不让迁移失败。 */
internal fun pendingColumns(
    existing: Collection<String>,
    wanted: List<SchemaColumn> = conversationLineageColumns,
): List<SchemaColumn> = wanted.filter { it.name !in existing }

val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(34, 35)
        db.beginTransaction()
        try {
            val existing = db.query("PRAGMA table_info(`conversationentity`)").use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) cursor.getString(1)?.let(::add)
                }
            }
            for (column in pendingColumns(existing)) db.execSQL(column.addSql)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
