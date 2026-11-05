package me.rerere.rikkahub.data.organization.autofile

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class ConversationAutoFileJournalTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val root: File get() = temporaryFolder.root
    private val assistantId = Uuid.random()
    private val folderId = Uuid.random()

    private fun move(conversationId: Uuid, folderName: String = "旅行") = AutoFileJournalMove(
        conversationId = conversationId.toString(),
        folderName = folderName,
        status = AutoFileMoveStatus.PLANNED,
        updateAtBefore = 111L,
    )

    @Test
    fun `begin persists planned batch before anything is applied`() = runTest {
        val journal = ConversationAutoFileJournal(root)
        val id = Uuid.random()

        val batch = journal.begin(assistantId, listOf(move(id)))

        assertEquals(AutoFileBatchStatus.PLANNED, batch.status)
        assertTrue(journal.peekUndoable(assistantId))
        val stored = journal.latest(assistantId)
        assertNotNull(stored)
        assertEquals(AutoFileMoveStatus.PLANNED, stored!!.moves.single().status)

        // 日志里只有 id 和时间戳，不存标题/正文
        val text = journal.fileFor(assistantId).readText()
        assertTrue(text.contains(id.toString()))
        assertFalse(text.contains("摘要"))
    }

    @Test
    fun `applied moves and finish make the batch undoable`() = runTest {
        val journal = ConversationAutoFileJournal(root)
        val id = Uuid.random()
        val batch = journal.begin(assistantId, listOf(move(id)))

        journal.createFolder(assistantId, batch.id, "旅行", folderId)
        journal.markMove(
            assistantId, batch.id, id, AutoFileMoveStatus.APPLIED,
            toFolderId = folderId, updateAtAfter = 222L,
        )
        journal.finish(assistantId, batch.id, AutoFileBatchStatus.DONE)

        val stored = journal.latest(assistantId)!!
        assertEquals(AutoFileBatchStatus.DONE, stored.status)
        assertEquals(folderId.toString(), stored.moves.single().toFolderId)
        assertEquals(222L, stored.moves.single().updateAtAfter)
        assertEquals(listOf(folderId.toString()), stored.createdFolderIds)
        assertTrue(journal.peekUndoable(assistantId))
    }

    @Test
    fun `interrupted batches stay undoable and undone batches stop being undoable`() = runTest {
        val journal = ConversationAutoFileJournal(root)
        val batch = journal.begin(assistantId, listOf(move(Uuid.random())))

        assertEquals(1, journal.markInterrupted(assistantId))
        assertEquals(0, journal.markInterrupted(assistantId))
        assertEquals(AutoFileBatchStatus.INTERRUPTED, journal.latest(assistantId)!!.status)
        assertTrue(journal.peekUndoable(assistantId))

        journal.markUndone(assistantId, batch.id)
        assertEquals(AutoFileBatchStatus.UNDONE, journal.latest(assistantId)!!.status)
        assertFalse(journal.peekUndoable(assistantId))
    }

    @Test
    fun `corrupt journal is quarantined and reports nothing to undo`() = runTest {
        val journal = ConversationAutoFileJournal(root)
        journal.begin(assistantId, listOf(move(Uuid.random())))
        val file = journal.fileFor(assistantId)
        file.writeText("{ broken")

        assertFalse(journal.peekUndoable(assistantId))
        val backups = file.parentFile.listFiles().orEmpty().filter { it.name.endsWith(".bak") }
        assertEquals(1, backups.size)
    }

    @Test
    fun `keeps only the newest batches`() = runTest {
        val journal = ConversationAutoFileJournal(root)
        repeat(ConversationAutoFileJournal.MAX_BATCHES + 3) {
            journal.begin(assistantId, listOf(move(Uuid.random())))
        }

        val stored = journal.fileFor(assistantId).readText()
        val batchCount = stored.split("\"createdAt\"").size - 1
        assertEquals(ConversationAutoFileJournal.MAX_BATCHES, batchCount)
    }

    @Test
    fun `batch ids stay unique even when the log is pruned at a fixed clock`() = runTest {
        val journal = ConversationAutoFileJournal(root) { 5L }
        val ids = (1..(ConversationAutoFileJournal.MAX_BATCHES + 4)).map {
            journal.begin(assistantId, listOf(move(Uuid.random()))).id
        }

        // 撞名的批次会被 markMove/finish 一起改到，等于把别的批次的撤销记录写坏。
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `skipping pending moves closes them without touching applied ones`() = runTest {
        val journal = ConversationAutoFileJournal(root)
        val appliedId = Uuid.random()
        val pendingId = Uuid.random()
        val batch = journal.begin(assistantId, listOf(move(appliedId), move(pendingId)))
        journal.markMove(
            assistantId, batch.id, appliedId, AutoFileMoveStatus.APPLIED,
            toFolderId = folderId, updateAtAfter = 111L,
        )

        assertEquals(1, journal.skipPending(assistantId, batch.id, "自动归库已关闭"))
        val stored = journal.latest(assistantId)!!
        assertEquals(AutoFileMoveStatus.APPLIED, stored.moves.first().status)
        assertEquals(AutoFileMoveStatus.SKIPPED, stored.moves.last().status)
        assertEquals("自动归库已关闭", stored.moves.last().error)
        // 幂等：没有待收尾项时不再写盘，也不改别的状态。
        assertEquals(0, journal.skipPending(assistantId, batch.id, "自动归库已关闭"))
    }

    @Test
    fun `batches with possibly done work stay unresolved until they are closed`() = runTest {
        val journal = ConversationAutoFileJournal(root)
        val conversationId = Uuid.random()
        val batch = journal.begin(assistantId, listOf(move(conversationId)))
        assertTrue(journal.latest(assistantId)!!.isUnresolved())

        journal.markInterrupted(assistantId)
        assertTrue(journal.latest(assistantId)!!.isUnresolved())

        journal.markMove(assistantId, batch.id, conversationId, AutoFileMoveStatus.APPLIED, toFolderId = folderId)
        journal.finish(assistantId, batch.id, AutoFileBatchStatus.DONE)
        assertFalse(journal.latest(assistantId)!!.isUnresolved())

        journal.markUndone(assistantId, batch.id)
        assertFalse(journal.latest(assistantId)!!.isUnresolved())
    }
}
