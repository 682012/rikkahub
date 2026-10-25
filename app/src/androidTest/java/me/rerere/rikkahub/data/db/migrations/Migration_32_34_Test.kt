package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for a database arriving from official 3.1.1 (schema 32) and for the fork's own
 * schema 32. Official 32 is simulated by taking the fork-32 database the helper builds and swapping
 * `usage_record` back to the official 11-column INTEGER-id table, which is what an official file has.
 */
@RunWith(AndroidJUnit4::class)
class Migration_32_34_Test {
    private val testDb = "migration-32-34-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    private val migrations: Array<Migration> = arrayOf(Migration_32_33, Migration_33_34)

    @Test
    fun officialSchema32UsageRecordBecomesForkTable() {
        helper.createDatabase(testDb, 32).apply {
            execSQL("DROP TABLE `usage_record`")
            execSQL(OFFICIAL_USAGE_RECORD_DDL)
            execSQL(
                "INSERT INTO `usage_record` (created_at, source, provider_id, model_id, model_name, " +
                    "assistant_id, conversation_id, prompt_tokens, completion_tokens, cached_tokens) " +
                    "VALUES (1000, 'CHAT', 'provider', 'model', 'Model', 'assistant', 'conv', 100, 40, 20)"
            )
            execSQL(
                "INSERT INTO `usage_record` (created_at, source, prompt_tokens, completion_tokens, cached_tokens) " +
                    "VALUES (2000, 'CHAT', 7, 3, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 34, true, *migrations)

        val rows = db.query(
            "SELECT id, created_at, prompt_tokens, completion_tokens, cached_tokens, total_tokens " +
                "FROM usage_record ORDER BY created_at"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getString(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3),
                            cursor.getLong(4), cursor.getLong(5),
                        )
                    )
                }
            }
        }
        assertEquals(
            listOf(
                listOf("1", 1000L, 100L, 40L, 20L, 140L),
                listOf("2", 2000L, 7L, 3L, 0L, 10L),
            ),
            rows,
        )
        // Official rows carry no node_id.
        db.query("SELECT node_id FROM usage_record LIMIT 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        assertEquals(
            "migrated INTEGER ids must be stored as text",
            0L,
            db.compileStatement("SELECT COUNT(*) FROM usage_record WHERE typeof(id) <> 'text'").use { it.simpleQueryForLong() },
        )
    }

    @Test
    fun forkSchema32WithDefaultDriftIsNormalizedWithoutLosingTotals() {
        helper.createDatabase(testDb, 32).apply {
            val ddl = query("SELECT sql FROM sqlite_master WHERE name = 'usage_record'").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            val drift = ddl.replace("`total_tokens` INTEGER NOT NULL", "`total_tokens` INTEGER NOT NULL DEFAULT 0")
            assertTrue("fixture must really change the default", ddl != drift)
            execSQL("DROP TABLE usage_record")
            execSQL(drift)
            execSQL(
                "INSERT INTO usage_record (id, created_at, source, prompt_tokens, completion_tokens, " +
                    "cached_tokens, total_tokens) VALUES ('kept', 10, 'CHAT', 3, 4, 0, 999)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(testDb, 34, true, *migrations)
        assertEquals(999L, db.compileStatement("SELECT total_tokens FROM usage_record WHERE id = 'kept'").use { it.simpleQueryForLong() })
        db.query("PRAGMA table_info(usage_record)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "total_tokens") assertTrue(cursor.isNull(4))
            }
        }
    }

    @Test
    fun forkSchema32UsageRecordSurvivesUnchanged() {
        helper.createDatabase(testDb, 32).apply {
            execSQL(
                "INSERT INTO usage_record (id, created_at, source, provider_id, model_id, model_name, " +
                    "assistant_id, conversation_id, node_id, prompt_tokens, completion_tokens, cached_tokens, " +
                    "total_tokens) VALUES ('u-1', 500, 'CHAT', 'p', 'm', 'M', 'a', 'c1', 'n1', 10, 5, 1, 15)"
            )
            execSQL(
                "INSERT INTO usage_record (id, created_at, source, provider_id, prompt_tokens, completion_tokens, " +
                    "cached_tokens, total_tokens) VALUES ('u-2', 600, 'CHAT', NULL, 1, 1, 0, 42)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 34, true, *migrations)

        assertEquals(
            listOf(
                listOf("u-1", 500L, "c1", "n1", 10L, 5L, 1L, 15L),
                listOf("u-2", 600L, null, null, 1L, 1L, 0L, 42L),
            ),
            db.query(
                "SELECT id, created_at, conversation_id, node_id, prompt_tokens, completion_tokens, " +
                    "cached_tokens, total_tokens FROM usage_record ORDER BY id"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            listOf(
                                cursor.getString(0), cursor.getLong(1),
                                if (cursor.isNull(2)) null else cursor.getString(2),
                                if (cursor.isNull(3)) null else cursor.getString(3),
                                cursor.getLong(4), cursor.getLong(5), cursor.getLong(6), cursor.getLong(7),
                            )
                        )
                    }
                }
            },
        )
    }

    @Test
    fun relativeMemoryPathsArePrefixedWithoutConflict() {
        helper.createDatabase(testDb, 32).apply {
            insertMemory("a1", "notes.md", "plain notes", 100)
            insertMemory("a1", "/keep.md", "canonical", 10)
            insertMemory("a2", "notes.md", "assistant two", 20)
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 34, true, *migrations)

        assertEquals(
            mapOf(
                ("a1" to "/notes.md") to ("plain notes" to 100L),
                ("a1" to "/keep.md") to ("canonical" to 10L),
                ("a2" to "/notes.md") to ("assistant two" to 20L),
            ),
            db.memoryRows(),
        )
    }

    @Test
    fun conflictingMemoryPathsKeepBothCopies() {
        helper.createDatabase(testDb, 32).apply {
            // relative is newer -> wins the canonical path, the old canonical text is archived
            insertMemory("a1", "profile.md", "new profile", 200)
            insertMemory("a1", "/profile.md", "old profile", 100)
            // equal timestamps -> the canonical path keeps its text, the relative copy is archived
            insertMemory("a1", "tie.md", "relative tie", 300)
            insertMemory("a1", "/tie.md", "canonical tie", 300)
            // identical text -> deduplicated, no archive row
            insertMemory("a1", "same.md", "same", 500)
            insertMemory("a1", "/same.md", "same", 400)
            // the deterministic archive name is already taken by other content
            insertMemory("a1", "/_legacy/path_conflicts/dup.md", "older archive", 50)
            insertMemory("a1", "dup.md", "relative dup", 60)
            insertMemory("a1", "/dup.md", "canonical dup", 10)
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 34, true, *migrations)
        val rows = db.memoryRows()

        assertEquals("new profile" to 200L, rows["a1" to "/profile.md"])
        assertEquals("old profile" to 100L, rows["a1" to "/_legacy/path_conflicts/profile.md"])
        assertEquals("canonical tie" to 300L, rows["a1" to "/tie.md"])
        assertEquals("relative tie" to 300L, rows["a1" to "/_legacy/path_conflicts/tie.md"])
        // deduplicated but the newer timestamp survives
        assertEquals("same" to 500L, rows["a1" to "/same.md"])
        assertNull(rows["a1" to "/_legacy/path_conflicts/same.md"])
        // an existing archive is never overwritten with different content
        assertEquals("older archive" to 50L, rows["a1" to "/_legacy/path_conflicts/dup.md"])
        assertEquals("relative dup" to 60L, rows["a1" to "/dup.md"])
        // the displaced canonical copy is still readable somewhere under the archive dir
        val archived = rows.filterKeys { (assistant, path) ->
            assistant == "a1" && path.startsWith("/_legacy/path_conflicts/dup") &&
                path != "/_legacy/path_conflicts/dup.md"
        }
        assertEquals(1, archived.size)
        val (key, content) = archived.entries.single()
        assertTrue("archive path must stay readable: $key", key.second.endsWith(".md"))
        assertEquals("canonical dup" to 10L, content)
        // no path is left without a leading slash
        assertEquals(
            0L,
            db.compileStatement("SELECT COUNT(*) FROM memory_file WHERE path NOT LIKE '/%'").use { it.simpleQueryForLong() },
        )
    }

    private fun SupportSQLiteDatabase.insertMemory(assistant: String, path: String, content: String, at: Long) =
        execSQL(
            "INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(assistant, path, content, at),
        )

    private fun SupportSQLiteDatabase.memoryRows(): Map<Pair<String, String>, Pair<String, Long>> =
        query("SELECT assistant_id, path, content, updated_at FROM memory_file").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0) to cursor.getString(1), cursor.getString(2) to cursor.getLong(3))
                }
            }
        }

    private companion object {
        const val OFFICIAL_USAGE_RECORD_DDL =
            "CREATE TABLE `usage_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `source` TEXT NOT NULL, `provider_id` TEXT, " +
                "`model_id` TEXT, `model_name` TEXT, `assistant_id` TEXT, `conversation_id` TEXT, " +
                "`prompt_tokens` INTEGER NOT NULL, `completion_tokens` INTEGER NOT NULL, " +
                "`cached_tokens` INTEGER NOT NULL)"
    }
}
