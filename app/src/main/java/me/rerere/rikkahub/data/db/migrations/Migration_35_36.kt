package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * schema 35 -> 36：给 usage_record 加"缓存上报覆盖度"，把"上游报了 0 命中"和"上游没报缓存"分开。
 *
 * - `cached_reported INTEGER NULL`：1 = 上游明确上报（0 也是真实 0），NULL = 未知。
 * - `cache_creation_tokens INTEGER NULL`：Claude cache_creation 这类新写入缓存的输入量（属于输入、不是命中）。
 *
 * 只加列，不重建表：已有行原样保留，两列默认 NULL = 未知，**不会**凭空造出 0% 命中率。
 * 唯一有据可依的回填：历史上 `cached_tokens > 0` 的行必然是上游报过缓存（缺省是 0），标成 1；
 * `cached_tokens = 0` 的旧行保持 NULL——那可能只是没报字段，不能假设成真 0。
 *
 * 幂等：备份还原或别的分支已经加过列时，`ADD COLUMN` 会抛「duplicate column name」，
 * 所以先查 PRAGMA 再决定加哪些（与 [Migration_34_35] 同一套写法）。
 */
internal val usageCacheColumns: List<SchemaColumn> = listOf(
    SchemaColumn("cached_reported", "INTEGER DEFAULT NULL"),
    SchemaColumn("cache_creation_tokens", "INTEGER DEFAULT NULL"),
)

val Migration_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(35, 36)
        db.beginTransaction()
        try {
            val existing = db.query("PRAGMA table_info(`usage_record`)").use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) cursor.getString(1)?.let(::add)
                }
            }
            for (column in pendingColumns(existing, usageCacheColumns)) {
                db.execSQL("ALTER TABLE `usage_record` ADD COLUMN `${column.name}` ${column.definition}")
            }
            db.execSQL("UPDATE `usage_record` SET `cached_reported` = 1 WHERE `cached_tokens` > 0 AND `cached_reported` IS NULL")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
