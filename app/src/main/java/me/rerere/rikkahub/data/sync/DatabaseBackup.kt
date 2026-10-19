package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.migrations.legacyMemoriesToProfile
import me.rerere.rikkahub.data.model.MemoryFile
import java.io.File

internal object DatabaseBackup {
    const val ARCHIVE_DATABASE = "rikka_hub.db"
    const val WAL = "rikka_hub-wal"
    const val SHM = "rikka_hub-shm"

    /** VACUUM INTO includes committed WAL contents in a consistent, standalone snapshot. */
    fun createSnapshot(database: SupportSQLiteDatabase, destination: File) {
        check(!destination.exists()) { "Backup snapshot already exists" }
        database.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
    }

    /** Only opens the staged copy. The caller must place its matching WAL beside it first. */
    fun normalize(context: Context, databaseFile: File) {
        require(databaseFile.isFile && databaseFile.length() > 0) { "Backup database is missing or empty" }
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READWRITE)
        )
        SQLiteDatabase.openDatabase(configuration, null) {
            error("Backup database is corrupt")
        }.use { database ->
            checkpoint(database)
            database.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                    "Backup database failed its integrity check"
                }
            }
        }
        removeSidecars(databaseFile)
    }

    fun checkpoint(database: SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "Could not checkpoint the backup database"
            }
        }
    }

    fun removeSidecars(databaseFile: File) {
        // All connections must be closed and committed WAL data checkpointed before removal.
        check(File(databaseFile.path + "-wal").length() == 0L) { "Backup WAL was not fully checkpointed" }
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = File(databaseFile.path + suffix)
            check(!sidecar.exists() || sidecar.delete()) { "Could not remove staged database sidecar" }
        }
    }
    /**
     * 同版本但不同分支的库（例如 excp 的 schema 31）无法走 Room 迁移，
     * 直接打开会报 identity hash 不匹配。这里把 staging 库对齐到当前 App 的
     * 表结构，并写入当前 App 的 identity hash。
     *
     * 只做结构对齐，不做业务数据合并；多出来的表会被删除，缺的列用目标库的
     * 默认值补齐。原备份不受影响。
     */
    fun alignToTarget(context: Context, databaseFile: File): Boolean {
        val targetFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
        if (!targetFile.isFile || targetFile.length() == 0L) return false
        val targetHash = identityHash(context, targetFile) ?: return false
        val stagedHash = identityHash(context, databaseFile)
        if (stagedHash == targetHash) return false
        val targetVersion = userVersion(context, targetFile)
        if (userVersion(context, databaseFile) != targetVersion) return false

        val target = open(context, targetFile, SQLiteDatabase.OPEN_READONLY)
        val staged = open(context, databaseFile, SQLiteDatabase.OPEN_READWRITE)
        try {
            val targetTables = tableSql(target)
            val stagedTables = tableSql(staged)
            val common = targetTables.keys intersect stagedTables.keys
            // Rebuild with the real target DDL rather than blessing a column-name-only approximation.
            // All writes are confined to the staged file and one transaction.
            staged.execSQL("PRAGMA foreign_keys=OFF")
            staged.beginTransaction()
            try {
                for (name in common + listOf("MemoryEntity").filter { it in stagedTables }) {
                    staged.execSQL("CREATE TEMP TABLE ${quote("fork_restore_$name")} AS SELECT * FROM ${quote(name)}")
                }
                for (name in stagedTables.keys) staged.execSQL("DROP TABLE ${quote(name)}")
                for ((_, sql) in targetTables) staged.execSQL(sql)
                for (name in common) {
                    val saved = "fork_restore_$name"
                    val originalColumns = columns(staged, saved).map { it.name }.toSet()
                    val shared = columns(target, name).map { it.name }.filter { it in originalColumns }
                    val before = rowCount(staged, saved)
                    if (before > 0) {
                        check(shared.isNotEmpty()) { "No compatible columns for $name" }
                        val names = shared.joinToString(",") { quote(it) }
                        // Missing NOT NULL/no-default columns, duplicate keys, and incompatible values fail here.
                        staged.execSQL("INSERT INTO ${quote(name)} ($names) SELECT $names FROM ${quote(saved)}")
                    }
                    check(rowCount(staged, name) == before) { "Row count changed for $name" }
                }
                if ("MemoryEntity" in stagedTables && "MemoryEntity" !in targetTables) {
                    migrateLegacyMemory(staged, "fork_restore_MemoryEntity")
                }
                for ((_, sql) in indexSql(target)) staged.execSQL(sql)
                for (name in targetTables.keys) {
                    check(columns(staged, name) == columns(target, name)) { "Column/key mismatch for $name" }
                    staged.rawQuery("PRAGMA foreign_key_check(${quote(name)})", null).use { cursor ->
                        check(!cursor.moveToFirst()) { "Foreign key violation in $name" }
                    }
                }
                // FTS is not managed by Room: do not silently keep an incompatible foreign branch index.
                val targetFts = ftsColumns(target)
                val stagedFts = ftsColumns(staged)
                check(stagedFts.isEmpty() || stagedFts == targetFts) { "Incompatible message_fts schema" }
                staged.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", arrayOf<Any?>(targetHash))
                staged.version = targetVersion
                staged.setTransactionSuccessful()
            } finally {
                staged.endTransaction()
            }
            return true
        } finally {
            staged.close()
            target.close()
        }
    }

    private fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    private fun rowCount(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM ${quote(table)}", null).use { cursor ->
            check(cursor.moveToFirst()); cursor.getLong(0)
        }

    private fun ftsColumns(db: SQLiteDatabase): List<String> = columns(db, "message_fts").map { it.name }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
    )

    private fun open(context: Context, file: File, flags: Int): SQLiteDatabase {
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(file.absolutePath, flags),
        )
        return SQLiteDatabase.openDatabase(configuration, null) {
            error("Cannot open backup database")
        }
    }

    private fun identityHash(context: Context, file: File): String? = open(
        context,
        file,
        SQLiteDatabase.OPEN_READONLY,
    ).use { database ->
        runCatching {
            database.rawQuery("SELECT identity_hash FROM room_master_table LIMIT 1", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun userVersion(context: Context, file: File): Int = open(
        context,
        file,
        SQLiteDatabase.OPEN_READONLY,
    ).use { database -> runCatching { database.version }.getOrDefault(-1) }

    private fun tableSql(database: SQLiteDatabase): Map<String, String> {
        val result = linkedMapOf<String, String>()
        database.rawQuery(
            "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND sql IS NOT NULL",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val sql = cursor.getString(1) ?: continue
                if (name.isManagedTable()) result[name] = sql
            }
        }
        return result
    }

    private fun indexSql(database: SQLiteDatabase): Map<String, String> {
        val result = linkedMapOf<String, String>()
        database.rawQuery(
            "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val sql = cursor.getString(1) ?: continue
                if (!name.startsWith("sqlite_autoindex")) result[name] = sql
            }
        }
        return result
    }

    private fun columns(database: SQLiteDatabase, table: String): List<ColumnInfo> {
        val result = mutableListOf<ColumnInfo>()
        database.rawQuery("PRAGMA table_info(${quote(table)})", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val type = cursor.getString(2) ?: ""
                val notNull = cursor.getInt(3) != 0
                val defaultValue = if (cursor.isNull(4)) null else cursor.getString(4)
                result += ColumnInfo(name, type, notNull, defaultValue, cursor.getInt(5))
            }
        }
        return result
    }

    private fun String.isManagedTable(): Boolean {
        val lower = lowercase()
        return !lower.startsWith("sqlite_") &&
            lower != "android_metadata" &&
            lower != "room_master_table" &&
            !lower.startsWith("message_fts")
    }


    private fun migrateLegacyMemory(database: SQLiteDatabase, sourceTable: String) {
        val grouped = linkedMapOf<String, MutableList<String>>()
        database.rawQuery("SELECT assistant_id, content FROM ${quote(sourceTable)} ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) {
                val assistantId = cursor.getString(0) ?: error("Legacy memory has no assistant")
                val content = cursor.getString(1) ?: error("Legacy memory has null content")
                grouped.getOrPut(assistantId) { mutableListOf() }.add(content)
            }
        }
        val now = System.currentTimeMillis()
        for ((assistantId, contents) in grouped) {
            val profile = legacyMemoriesToProfile(contents) ?: continue
            val existing = database.rawQuery(
                "SELECT content FROM memory_file WHERE assistant_id = ? AND path = ?",
                arrayOf(assistantId, MemoryFile.PROFILE_PATH),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            val combined = if (existing.isNullOrBlank()) profile else existing + "\n\n" + profile
            database.execSQL(
                "INSERT OR REPLACE INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(assistantId, MemoryFile.PROFILE_PATH, combined, now),
            )
            database.execSQL(
                "INSERT OR REPLACE INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(assistantId, "_legacy/imported_memories_$now.md", contents.joinToString("\n"), now),
            )
        }
    }
}
