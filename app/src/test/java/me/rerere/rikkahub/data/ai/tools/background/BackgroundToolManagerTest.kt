package me.rerere.rikkahub.data.ai.tools.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * 前台→后台的核心语义。
 *
 * 每条用例都在守一个具体的坑：工具体只执行一次（超时后不重跑）、回执只发一次、
 * 前台取消只作用于"还没转后台"的调用、显式 start 立刻返回。
 */
class BackgroundToolManagerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var root: File
    private lateinit var manager: BackgroundToolManager
    private lateinit var owner: BackgroundToolOwner
    private val notices = Collections.synchronizedList(mutableListOf<BackgroundToolNotice>())

    private val args: JsonElement = JsonObject(emptyMap())

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "bg-manager-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager = BackgroundToolManager(root, scope)
        manager.noticeSink = { notices.add(it) }
        owner = BackgroundToolOwner.of(Uuid.random())
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun tool(
        name: String = "workspace_shell",
        block: suspend (JsonElement) -> List<UIMessagePart>,
    ) = Tool(name = name, description = "fake", executionMode = ToolExecutionMode.SERIAL, execute = block)

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
    }

    @Test
    fun fastCallNeverLeavesForeground() {
        val calls = AtomicInteger()
        val fast = tool { calls.incrementAndGet(); listOf(UIMessagePart.Text("hello")) }

        val output = runBlocking { manager.execute(fast, args, owner, waitMillis = 2_000) }

        assertEquals("hello", (output.single() as UIMessagePart.Text).text)
        assertEquals(1, calls.get())
        val task = manager.list(owner).single()
        assertEquals(BackgroundToolStatus.COMPLETED, task.status)
        assertFalse(task.isDetached)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun timeoutMovesToBackgroundOnceAndKeepsRunning() {
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val slow = tool {
            calls.incrementAndGet()
            release.await()
            listOf(UIMessagePart.Text("late output"), UIMessagePart.Image(url = "file://img.png"))
        }

        val output = runBlocking { manager.execute(slow, args, owner, waitMillis = 30) }
        val receipt = (output.single() as UIMessagePart.Text).text
        val task = manager.list(owner).single()
        assertEquals(BackgroundToolStatus.RUNNING, task.status)
        assertTrue(task.backgrounded)
        assertTrue(receipt.contains("moved_to_background"))
        assertTrue(receipt.contains(task.id))

        // 转后台之后前台不再等：等释放以后同一次执行自己跑完，没有被重放
        release.complete(Unit)
        runBlocking { awaitUntil { manager.get(task.id, owner)?.isFinished == true } }
        assertEquals(1, calls.get())

        val done = manager.get(task.id, owner)!!
        assertEquals(BackgroundToolStatus.COMPLETED, done.status)
        assertEquals("late output", manager.resultText(done))
        val page = manager.readResult(task.id, owner)!!
        assertEquals("late output", page.text)
        assertTrue(page.eof)
        // 非文本附件在同一进程内保留
        assertEquals(1, page.attachments.size)
        assertTrue(page.attachments.single() is UIMessagePart.Image)

        // 完成提醒恰好一条，且只在转后台之后
        runBlocking { awaitUntil { notices.size == 1 } }
        assertEquals("background_tool:${task.id}", notices.single().key)
        assertEquals(owner.conversationId, notices.single().conversationId)
        runBlocking { delay(100) }
        assertEquals(1, notices.size)
    }

    @Test
    fun foregroundCancelCancelsTheRealExecution() {
        val started = CompletableDeferred<Unit>()
        val stopped = AtomicBoolean(false)
        val slow = tool {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.set(true)
            }
        }

        runBlocking {
            val call = launch { manager.execute(slow, args, owner, waitMillis = 5_000) }
            started.await()
            // 用户停止生成：还没转后台，所以真实执行也必须停，不能留个没人等的写操作在后台
            call.cancelAndJoin()
            awaitUntil { stopped.get() && manager.list(owner).singleOrNull()?.isFinished == true }
        }
        val task = manager.list(owner).single()
        assertEquals(BackgroundToolStatus.CANCELLED, task.status)
        assertFalse(task.isDetached)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun explicitStartReturnsImmediately() {
        val release = CompletableDeferred<Unit>()
        val slow = tool { release.await(); listOf(UIMessagePart.Text("done")) }

        val task = manager.startDetached(slow, args, owner)

        assertEquals(BackgroundToolStatus.RUNNING, task.status)
        assertTrue(task.explicit)
        assertTrue(task.isDetached)
        release.complete(Unit)
        runBlocking { awaitUntil { manager.get(task.id, owner)?.isFinished == true } }
        assertEquals(BackgroundToolStatus.COMPLETED, manager.get(task.id, owner)!!.status)
        runBlocking { awaitUntil { notices.size == 1 } }
    }

    private fun resultFileOf(task: BackgroundToolTask) =
        File(File(File(root, task.conversationId!!), task.id), BACKGROUND_RESULT_FILE)

    @Test
    fun foregroundDeliveryDoesNotRetainABody() {
        val fast = tool {
            listOf(UIMessagePart.Text("hello"), UIMessagePart.Image(url = "file://img.png"))
        }

        val output = runBlocking { manager.execute(fast, args, owner, waitMillis = 2_000) }
        assertEquals(2, output.size)

        val task = manager.list(owner).single()
        assertEquals(BackgroundToolStatus.COMPLETED, task.status)
        // 前台已经拿到输出：manager 不再留 parts（含图片）、也不写正文文件
        assertFalse(task.bodyRetained)
        assertEquals(0, task.storedChars)
        assertEquals(5, task.resultChars)
        assertTrue("前台已交付的调用不留附件", manager.attachmentsOf(task).isEmpty())
        assertFalse("前台已交付的调用不写正文文件", resultFileOf(task).exists())
        // 再读只会拿到空正文（分页头会解释），不会假装有内容
        val page = manager.readResult(task.id, owner)!!
        assertTrue(page.text.isEmpty())
        assertEquals(0, page.totalChars)
        assertTrue(page.eof)
    }

    @Test
    fun oversizedBodyIsMarkedTruncatedAndPagingStaysConsistent() {
        val small = BackgroundToolManager(root, scope, maxPersistedChars = 32)
        val long = "0123456789".repeat(10)
        val task = small.startDetached(tool { listOf(UIMessagePart.Text(long)) }, args, owner)
        runBlocking { awaitUntil { small.get(task.id, owner)?.isFinished == true } }

        val done = small.get(task.id, owner)!!
        assertEquals(100, done.resultChars)
        assertEquals(32, done.storedChars)
        assertTrue("超过上限必须显式标 truncated，不能静默截断", done.truncated)
        assertEquals(long.take(32), small.resultText(done))

        // 分页坐标就是留存正文：翻完刚好 32 个字符，不重不漏
        val rebuilt = StringBuilder()
        var offset = 0
        while (true) {
            val page = small.readResult(task.id, owner, offset = offset, limit = 10)!!
            assertEquals(32, page.totalChars)
            rebuilt.append(page.text)
            if (page.eof) break
            offset = page.endOffset
        }
        assertEquals(long.take(32), rebuilt.toString())
    }

    @Test
    fun persistenceFailureIsRecordedAndNeverBreaksTheForegroundResult() {
        // rootDir 的父路径是个普通文件：mkdirs 必然失败
        val blocker = File(root, "not-a-directory").apply { writeText("x") }
        val broken = BackgroundToolManager(File(blocker, "tasks"), scope)

        val output = runBlocking {
            broken.execute(tool { listOf(UIMessagePart.Text("still the real output")) }, args, owner, waitMillis = 2_000)
        }

        assertEquals("still the real output", (output.single() as UIMessagePart.Text).text)
        val task = broken.list(owner).single()
        // 执行确实成功了 → 状态照实写 COMPLETED；落盘失败另记一笔，不是假完成也不是失败
        assertEquals(BackgroundToolStatus.COMPLETED, task.status)
        assertNotNull(task.persistenceError)
        assertTrue(task.persistenceError!!.contains("persistence failed"))
    }
}
