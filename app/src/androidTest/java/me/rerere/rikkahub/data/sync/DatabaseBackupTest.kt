package me.rerere.rikkahub.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class DatabaseBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before fun setUp() {
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "database-backup-test-").toFile()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun open(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        SQLiteConfiguration.configure(context, SQLiteDatabaseConfiguration(file.path, SQLiteDatabase.CREATE_IF_NECESSARY)),
        null,
        null,
    )

    private fun createWalDatabase(file: File): SQLiteDatabase = open(file).apply {
        enableWriteAheadLogging()
        query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
        execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, text TEXT)")
        query("PRAGMA wal_checkpoint(TRUNCATE)").use { assertTrue(it.moveToFirst()) }
        execSQL("INSERT INTO messages(text) VALUES ('committed only in WAL')")
        assertTrue(File(file.path + "-wal").length() > 0)
    }

    @Test fun legacyWalIsMergedWithoutShmAndWithoutChangingOriginalArchiveFiles() {
        val source = File(directory, "source")
        val staged = File(directory, "rikka_hub")
        createWalDatabase(source).use {
            source.copyTo(staged)
            File(source.path + "-wal").copyTo(File(staged.path + "-wal"))
        }
        DatabaseBackup.normalize(context, staged)
        assertFalse(File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())
        open(staged).use {
            assertEquals("committed only in WAL", it.stringForQuery("SELECT text FROM messages", null))
        }
    }

    @Test fun snapshotContainsWalCommitsAndFtsTablesAndNeedsNoSidecars() {
        val source = File(directory, "source")
        val snapshot = File(directory, "snapshot")
        createWalDatabase(source).use {
            it.execSQL("CREATE VIRTUAL TABLE search USING fts5(text, tokenize='simple')")
            it.execSQL("INSERT INTO search(text) VALUES ('hello backup')")
            DatabaseBackup.createSnapshot(it, snapshot)
            // Subsequent writes must not appear in the already-created snapshot.
            it.execSQL("INSERT INTO messages(text) VALUES ('after snapshot')")
        }
        assertFalse(File(snapshot.path + "-wal").exists())
        assertFalse(File(snapshot.path + "-shm").exists())
        DatabaseBackup.normalize(context, snapshot)
        open(snapshot).use {
            assertEquals(1L, it.longForQuery("SELECT count(*) FROM messages", null))
            assertEquals(1L, it.longForQuery("SELECT count(*) FROM search WHERE search MATCH 'hello'", null))
        }
    }

    @Test fun corruptDatabaseIsRejected() {
        val file = File(directory, "invalid")
        file.writeText("not a SQLite database")
        var failed = false
        try {
            DatabaseBackup.normalize(context, file)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Corrupt backup must not be published", failed)
    }

    /**
     * Same user_version, real structural drift, and an FTS index that already exists in the staged
     * file. `message_fts*` tables are never dropped by the alignment, so replaying their CREATE INDEX
     * used to abort with "index ... already exists"; only managed-table indexes may be replayed.
     */
    @Test fun sameVersionAlignReplaysOnlyManagedIndexes() {
        val target = File(directory, "target")
        createAlignFixture(target)
        val staged = File(directory, "staged")
        target.copyTo(staged)
        open(staged).use { database ->
            database.execSQL("CREATE TABLE legacy_extra (id INTEGER PRIMARY KEY)")
            database.execSQL("INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES ('a', '/x.md', 'kept', 1)")
        }

        assertTrue(DatabaseBackup.alignToTarget(context, staged, target))

        open(staged).use { database ->
            assertEquals("kept", database.stringForQuery("SELECT content FROM memory_file WHERE path = '/x.md'", null))
            assertEquals(
                0L,
                database.longForQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'legacy_extra'", null),
            )
            assertEquals(
                "the existing FTS index must survive the alignment",
                1L,
                database.longForQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_message_fts_rowid_conversation_id'",
                    null,
                ),
            )
        }
    }

    private fun createAlignFixture(file: File) = open(file).use { database ->
        database.execSQL(
            "CREATE TABLE memory_file (assistant_id TEXT NOT NULL, path TEXT NOT NULL, " +
                "content TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(assistant_id, path))"
        )
        database.execSQL(
            "CREATE VIRTUAL TABLE message_fts USING fts5(text, node_id UNINDEXED, message_id UNINDEXED, " +
                "conversation_id UNINDEXED, title UNINDEXED, update_at UNINDEXED, tokenize = 'simple')"
        )
        database.execSQL("CREATE TABLE message_fts_rowid(fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT NOT NULL)")
        database.execSQL("CREATE INDEX index_message_fts_rowid_conversation_id ON message_fts_rowid(conversation_id)")
        database.execSQL("INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES ('a', '/seed.md', 'seed', 1)")
        database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        database.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, 'align-hash')")
        database.version = 34
    }
}
