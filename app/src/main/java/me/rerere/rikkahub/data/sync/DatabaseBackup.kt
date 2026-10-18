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

            // 旧版逐条记忆：目标结构里已经没有 MemoryEntity，先转换成 profile 文件再删表
            if ("MemoryEntity" in stagedTables && "MemoryEntity" !in targetTables) {
                migrateLegacyMemory(staged)
            }
            for (name in stagedTables.keys - targetTables.keys) {
                if (name.isManagedTable()) staged.execSQL("DROP TABLE IF EXISTS `$name`")
            }
            for ((name, sql) in targetTables) {
                if (name !in stagedTables) staged.execSQL(sql)
            }
            for (name in targetTables.keys intersect stagedTables.keys) {
                val stagedColumns = columns(staged, name).mapTo(mutableSetOf()) { it.name }
                for (column in columns(target, name)) {
                    if (column.name in stagedColumns) continue
                    val definition = buildString {
                        append('`').append(column.name).append("` ").append(column.type)
                        if (column.notNull) append(" NOT NULL")
                        if (column.defaultValue != null) append(" DEFAULT ").append(column.defaultValue)
                    }
                    runCatching { staged.execSQL("ALTER TABLE `$name` ADD COLUMN $definition") }
                }
            }
            val stagedIndexes = indexSql(staged).keys
            for ((name, sql) in indexSql(target)) {
                if (name !in stagedIndexes) runCatching { staged.execSQL(sql) }
            }

            // 对齐后再校验一次，避免带着缺列/缺表继续打开
            val afterTables = tableSql(staged)
            val missingTables = targetTables.keys - afterTables.keys
            val missingColumns = targetTables.keys.flatMap { table ->
                val stagedColumnNames = columns(staged, table).mapTo(mutableSetOf()) { it.name }
                columns(target, table)
                    .map { it.name }
                    .filterNot { it in stagedColumnNames }
                    .map { "$table.$it" }
            }
            check(missingTables.isEmpty() && missingColumns.isEmpty()) {
                "Backup structure mismatch after alignment: tables=$missingTables columns=$missingColumns"
            }

            staged.execSQL(
                "UPDATE room_master_table SET identity_hash = ?",
                arrayOf<Any?>(targetHash),
            )
            staged.version = targetVersion
            return true
        } finally {
            runCatching { staged.close() }
            runCatching { target.close() }
        }
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
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
        database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val type = cursor.getString(2) ?: ""
                val notNull = cursor.getInt(3) != 0
                val defaultValue = if (cursor.isNull(4)) null else cursor.getString(4)
                result += ColumnInfo(name, type, notNull, defaultValue)
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


    private fun migrateLegacyMemory(database: SQLiteDatabase) {
        val grouped = linkedMapOf<String, MutableList<String>>()
        runCatching {
            database.rawQuery("SELECT assistant_id, content FROM MemoryEntity ORDER BY id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val assistantId = cursor.getString(0) ?: continue
                    val content = cursor.getString(1) ?: continue
                    grouped.getOrPut(assistantId) { mutableListOf() }.add(content)
                }
            }
        }
        val now = System.currentTimeMillis()
        for ((assistantId, contents) in grouped) {
            val profile = legacyMemoriesToProfile(contents) ?: continue
            runCatching {
                database.execSQL(
                    "INSERT OR REPLACE INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
                    arrayOf<Any?>(assistantId, MemoryFile.PROFILE_PATH, profile, now),
                )
            }
        }
    }
}
