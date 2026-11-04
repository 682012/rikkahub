package me.rerere.rikkahub.data.ai.tools.background

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.BackgroundToolSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * 落盘、重启、归属隔离。
 *
 * 重启后还标着 RUNNING 的任务必须变成 INTERRUPTED（效果未知）并且**绝不重放**：
 * 一次写命令到底改了什么，进程死掉之后没人知道，自动再跑一次就是凭猜测改用户的数据。
 */
class BackgroundToolPersistenceTest {
    private lateinit var scope: CoroutineScope
    private lateinit var root: File
    private lateinit var manager: BackgroundToolManager
    private lateinit var conversationId: Uuid
    private lateinit var owner: BackgroundToolOwner
    private val args: JsonElement = JsonObject(emptyMap())
    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "bg-persist-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager = BackgroundToolManager(root, scope)
        conversationId = Uuid.random()
        owner = BackgroundToolOwner.of(conversationId)
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun writeRecord(task: BackgroundToolTask, result: String? = null) {
        val dir = File(File(root, task.conversationId!!), task.id).apply { mkdirs() }
        File(dir, BACKGROUND_TASK_FILE)
            .writeText(json.encodeToString(BackgroundToolTask.serializer(), task))
        result?.let { File(dir, BACKGROUND_RESULT_FILE).writeText(it) }
    }

    @Test
    fun runningTaskAfterRestartBecomesInterruptedAndIsNotReplayed() {
        val id = Uuid.random().toString()
        writeRecord(
            BackgroundToolTask(
                id = id,
                toolName = "workspace_shell",
                conversationId = conversationId.toString(),
                status = BackgroundToolStatus.RUNNING,
                createdAt = 1_000L,
            ),
        )

        // 新进程：同一个目录，新的 manager
        val restored = BackgroundToolManager(root, scope, clock = { 42_000L })
        val task = restored.list(owner).single()

        assertEquals(BackgroundToolStatus.INTERRUPTED, task.status)
        assertEquals(42_000L, task.finishedAt)
        assertNotNull(task.error)
        assertTrue(task.error!!.contains("NOT replayed"))
        // 已经终态：cancel 没有活执行可取消，不会把它变回"可以再跑"
        assertFalse(restored.cancel(id, owner))
        assertEquals(BackgroundToolStatus.INTERRUPTED, restored.get(id, owner)!!.status)
        // 重启状态也要落盘，下次冷启动别再当 RUNNING
        val onDisk = json.decodeFromString(
            BackgroundToolTask.serializer(),
            File(File(File(root, conversationId.toString()), id), BACKGROUND_TASK_FILE).readText(),
        )
        assertEquals(BackgroundToolStatus.INTERRUPTED, onDisk.status)
    }

    @Test
    fun finishedResultStaysReadableAfterRestart() {
        val id = Uuid.random().toString()
        writeRecord(
            BackgroundToolTask(
                id = id,
                toolName = "workspace_shell",
                conversationId = conversationId.toString(),
                status = BackgroundToolStatus.COMPLETED,
                createdAt = 1_000L,
                finishedAt = 2_000L,
                resultChars = 11,
            ),
            result = "hello world",
        )

        val restored = BackgroundToolManager(root, scope)
        val page = restored.readResult(id, owner)!!

        assertEquals("hello world", page.text)
        assertTrue(page.eof)
        assertEquals(11, page.totalChars)
        assertEquals(BackgroundToolStatus.COMPLETED, page.task.status)
    }

    @Test
    fun otherConversationCannotReadOrCancelAnotherTask() {
        val tool = Tool(name = "workspace_shell", description = "fake") {
            listOf(UIMessagePart.Text("done"))
        }
        val task = manager.startDetached(tool, args, owner)
        runBlocking {
            while (manager.get(task.id, owner)?.isFinished != true) delay(5)
        }

        val stranger = BackgroundToolOwner.of(Uuid.random())
        assertNull(manager.get(task.id, stranger))
        assertNull(manager.readResult(task.id, stranger))
        assertFalse(manager.cancel(task.id, stranger))
        assertTrue(manager.list(stranger).isEmpty())
        // 自己的会话照常读得到
        assertNotNull(manager.get(task.id, owner))
    }

    @Test
    fun pagedReadFromDiskAfterRestartStreamsTheWholeBody() {
        val id = Uuid.random().toString()
        val body = "0123456789".repeat(6)
        writeRecord(
            BackgroundToolTask(
                id = id,
                toolName = "workspace_shell",
                conversationId = conversationId.toString(),
                status = BackgroundToolStatus.COMPLETED,
                createdAt = 1_000L,
                finishedAt = 2_000L,
                resultChars = body.length,
                // 老版本落的记录没有 storedChars：恢复时要自己算出来
            ),
            result = body,
        )

        val restored = BackgroundToolManager(root, scope)
        val first = restored.readResult(id, owner, offset = 0, limit = 16)!!
        assertEquals(body.length, first.totalChars)
        assertEquals(body.take(16), first.text)
        assertFalse(first.eof)

        // 按 nextOffset 连续翻页能拼回全文（文件是流式读的，一页不读整份）
        val rebuilt = StringBuilder()
        var offset = 0
        while (true) {
            val page = restored.readResult(id, owner, offset = offset, limit = 16)!!
            rebuilt.append(page.text)
            if (page.eof) break
            offset = page.endOffset
        }
        assertEquals(body, rebuilt.toString())
    }

    @Test
    fun lostAttachmentsAndTruncationAreVisibleInTheResultHeader() {
        val lostId = Uuid.random().toString()
        writeRecord(
            BackgroundToolTask(
                id = lostId,
                toolName = "workspace_shell",
                conversationId = conversationId.toString(),
                status = BackgroundToolStatus.COMPLETED,
                createdAt = 1_000L,
                finishedAt = 2_000L,
                resultChars = 4,
                storedChars = 4,
                hasAttachments = true,
            ),
            result = "body",
        )
        val truncatedId = Uuid.random().toString()
        writeRecord(
            BackgroundToolTask(
                id = truncatedId,
                toolName = "workspace_shell",
                conversationId = conversationId.toString(),
                status = BackgroundToolStatus.COMPLETED,
                createdAt = 3_000L,
                finishedAt = 4_000L,
                resultChars = 100,
                storedChars = 10,
                truncated = true,
            ),
            result = "0123456789",
        )

        val restored = BackgroundToolManager(root, scope)
        val control = createBackgroundControlTools(
            manager = restored,
            owner = owner,
            settings = BackgroundToolSettings(),
            catalog = emptyList(),
        ).single()

        fun header(taskId: String): String = runBlocking {
            control.execute(buildJsonObject { put("action", "result"); put("task_id", taskId) })
                .first() as UIMessagePart.Text
        }.text

        // 附件重启后读不回来 → 头里明确说，不装作没有附件
        assertTrue(header(lostId).contains("\"attachments_lost\":true"))
        // 截断 → 头里给出真实长度和缺口，不假装 total_chars 就是全部
        val truncatedHeader = header(truncatedId)
        assertTrue(truncatedHeader.contains("\"truncated\":true"))
        assertTrue(truncatedHeader.contains("\"source_chars\":100"))
        assertTrue(truncatedHeader.contains("\"dropped_chars\":90"))
        assertTrue(truncatedHeader.contains("\"total_chars\":10"))
    }
}
