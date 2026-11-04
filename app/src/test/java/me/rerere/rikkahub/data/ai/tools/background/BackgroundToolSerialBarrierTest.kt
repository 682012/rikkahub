package me.rerere.rikkahub.data.ai.tools.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * 真串行屏障。
 *
 * 这是「转后台」最容易出事的地方：写工具被 detach 之后还在跑，模型下一步的写操作如果直接开跑，
 * 两条写命令就会同时踩同一批文件——而且是悄悄踩。`SERIAL` 必须排队，只读并行工具不受影响。
 */
class BackgroundToolSerialBarrierTest {
    private lateinit var scope: CoroutineScope
    private lateinit var root: File
    private lateinit var manager: BackgroundToolManager
    private lateinit var owner: BackgroundToolOwner
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val args: JsonElement = JsonObject(emptyMap())

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "bg-barrier-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager = BackgroundToolManager(root, scope)
        owner = BackgroundToolOwner.of(Uuid.random())
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun tool(
        name: String,
        mode: ToolExecutionMode,
        block: suspend (JsonElement) -> List<UIMessagePart>,
    ) = Tool(name = name, description = "fake $name", executionMode = mode, execute = block)

    @Test
    fun detachedWriteBlocksTheNextSerialWrite() {
        val aStarted = CompletableDeferred<Unit>()
        val aRelease = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val writeA = tool("workspace_shell", ToolExecutionMode.SERIAL) {
            events += "a:start"
            aStarted.complete(Unit)
            aRelease.await()
            events += "a:end"
            listOf(UIMessagePart.Text("a"))
        }
        val writeB = tool("workspace_shell", ToolExecutionMode.SERIAL) {
            events += "b:start"
            bStarted.complete(Unit)
            listOf(UIMessagePart.Text("b"))
        }

        runBlocking {
            // A 转后台还在跑
            manager.execute(writeA, args, owner, waitMillis = 30)
            aStarted.await()
            // B 的前台等待也只有 30ms，它会把自己也转后台——但绝不能开始写
            manager.execute(writeB, args, owner, waitMillis = 30)
            delay(150)
            assertTrue(events.none { it == "b:start" })
            aRelease.complete(Unit)
            withTimeout(5_000) { bStarted.await() }
        }

        assertEquals(listOf("a:start", "a:end", "b:start"), events.toList())
    }

    @Test
    fun cancellingAQueuedWriteDoesNotOpenTheGateForTheNextWrite() {
        val aStarted = CompletableDeferred<Unit>()
        val aRelease = CompletableDeferred<Unit>()
        val cStarted = CompletableDeferred<Unit>()
        val writeA = tool("workspace_shell", ToolExecutionMode.SERIAL) {
            events += "a:start"
            aStarted.complete(Unit)
            aRelease.await()
            events += "a:end"
            listOf(UIMessagePart.Text("a"))
        }
        val writeB = tool("workspace_shell", ToolExecutionMode.SERIAL) {
            events += "b:start"
            listOf(UIMessagePart.Text("b"))
        }
        val writeC = tool("workspace_shell", ToolExecutionMode.SERIAL) {
            events += "c:start"
            cStarted.complete(Unit)
            listOf(UIMessagePart.Text("c"))
        }

        runBlocking {
            // A 已经转后台还在写
            manager.execute(writeA, args, owner, waitMillis = 30)
            aStarted.await()

            // B 排在 A 后面，还没开始就被停掉（用户停止生成）
            val bCall = launch { manager.execute(writeB, args, owner, waitMillis = 10_000) }
            delay(100)
            bCall.cancelAndJoin()
            delay(80)
            assertTrue("B 不能开始写", events.none { it == "b:start" })

            // C 排在 B 后面：B 被取消绝不能顺手把闸门打开让 C 追上 A
            val cCall = launch { manager.execute(writeC, args, owner, waitMillis = 10_000) }
            delay(200)
            assertTrue("C 不能在 A 还没写完时开始：$events", events.none { it == "c:start" })

            aRelease.complete(Unit)
            withTimeout(5_000) { cStarted.await() }
            cCall.join()
        }

        assertEquals(listOf("a:start", "a:end", "c:start"), events.toList())
        // 被取消的 B 记成 CANCELLED，不会变成"跑完了"
        assertEquals(BackgroundToolStatus.CANCELLED, manager.list(owner).single { it.status == BackgroundToolStatus.CANCELLED }.status)
    }

    @Test
    fun readOnlyParallelCallDoesNotWaitForTheBarrier() {
        val aStarted = CompletableDeferred<Unit>()
        val aRelease = CompletableDeferred<Unit>()
        val write = tool("workspace_shell", ToolExecutionMode.SERIAL) {
            aStarted.complete(Unit)
            aRelease.await()
            listOf(UIMessagePart.Text("write done"))
        }
        val read = tool("memory_read", ToolExecutionMode.PARALLEL_DEDUPLICATED) {
            listOf(UIMessagePart.Text("read done"))
        }

        runBlocking {
            manager.execute(write, args, owner, waitMillis = 30)
            aStarted.await()
            // 只读工具照常在前台等到真结果（它不需要排队）
            val output = manager.execute(read, args, owner, waitMillis = 2_000)
            assertEquals("read done", (output.single() as UIMessagePart.Text).text)
            aRelease.complete(Unit)
        }
    }
}
