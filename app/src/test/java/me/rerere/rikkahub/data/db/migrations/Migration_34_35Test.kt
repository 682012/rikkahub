package me.rerere.rikkahub.data.db.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * schema 34 -> 35 是纯加列，不能碰任何用户数据。
 *
 * 单元测试跑不了 Android 的 SQLite（没有 sqlite-jdbc），所以这里守两件事：
 * 1. 迁移要执行的 DDL 原文（列名、类型、NOT NULL、默认值）——默认值写错的话 Room 打开库时会因为
 *    schema 校验失败直接把库判成不可用；
 * 2. 源码里不存在任何会动已有数据的语句，且版本号/注册确实接上了。
 * DDL 在真实 SQLite 上的执行结果（旧行/索引/内容是否原样、幂等）由
 * autofile/a-db-checks/check_db_r21_a.py 用实际数据库验证。
 */
class Migration_34_35Test {

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("找不到 $relative（cwd=${File(".").absolutePath}）")

    @Test
    fun `columns match the entity declaration`() {
        assertEquals(
            listOf(
                "lineage_id" to "TEXT NOT NULL DEFAULT ''",
                "folder_assignment_locked" to "INTEGER NOT NULL DEFAULT 0",
            ),
            conversationLineageColumns.map { it.name to it.definition },
        )
    }

    @Test
    fun `add column sql keeps table and column names quoted`() {
        assertEquals(
            "ALTER TABLE `conversationentity` ADD COLUMN `lineage_id` TEXT NOT NULL DEFAULT ''",
            conversationLineageColumns.first().addSql,
        )
        assertEquals(
            "ALTER TABLE `conversationentity` ADD COLUMN `folder_assignment_locked` INTEGER NOT NULL DEFAULT 0",
            conversationLineageColumns.last().addSql,
        )
    }

    @Test
    fun `existing columns are skipped so a re-run is a no-op`() {
        val v34Columns = setOf("id", "assistant_id", "title", "nodes", "create_at", "update_at", "folder_id")

        assertEquals(conversationLineageColumns, pendingColumns(v34Columns))
        assertEquals(
            listOf(conversationLineageColumns.last()),
            pendingColumns(v34Columns + "lineage_id"),
        )
        assertEquals(
            emptyList<SchemaColumn>(),
            pendingColumns(v34Columns + conversationLineageColumns.map { it.name }),
        )
    }

    @Test
    fun `migration source never rewrites existing rows`() {
        val source = repoFile("src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_34_35.kt")
            .readText()
            .uppercase()

        assertTrue(source.contains("ALTER TABLE `CONVERSATIONENTITY` ADD COLUMN"))
        for (destructive in listOf("DROP TABLE", "DROP COLUMN", "DELETE FROM", "UPDATE `CONVERSATIONENTITY`", "INSERT INTO")) {
            assertFalse("迁移不能出现 $destructive", source.contains(destructive))
        }
    }

    @Test
    fun `database version and factory are wired to 35`() {
        val database = repoFile("src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt").readText()
        val factory = repoFile("src/main/java/me/rerere/rikkahub/data/db/AppDatabaseFactory.kt").readText()

        assertTrue(Regex("""\n\s*version = 35,""").containsMatchIn(database))
        assertFalse(
            "34 -> 35 是显式迁移，不要伪造 AutoMigration",
            database.contains("AutoMigration(from = 34, to = 35"),
        )
        assertTrue(factory.contains("Migration_34_35,"))
    }

    @Test
    fun `entity columns carry the same defaults as the migration`() {
        val entity = repoFile("src/main/java/me/rerere/rikkahub/data/db/entity/ConversationEntity.kt").readText()

        assertTrue(entity.contains("""@ColumnInfo("lineage_id", defaultValue = "")"""))
        assertTrue(entity.contains("""@ColumnInfo("folder_assignment_locked", defaultValue = "0")"""))
        assertTrue(entity.contains("val lineageId: String = \"\""))
        assertTrue(entity.contains("val folderAssignmentLocked: Boolean = false"))
    }
}
