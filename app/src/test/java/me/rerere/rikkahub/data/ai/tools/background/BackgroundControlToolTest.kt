package me.rerere.rikkahub.data.ai.tools.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.BackgroundToolSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

/**
 * 显式后台入口 `background_tool`。
 *
 * 它只能跑**本轮已经授权**的工具，审批原样透传（"放到后台跑"不是一条绕过审批的路），
 * 也不能拿它去跑自己/ask_user/tool_batch。
 */
class BackgroundControlToolTest {
    private lateinit var scope: CoroutineScope
    private lateinit var root: File
    private lateinit var manager: BackgroundToolManager
    private lateinit var owner: BackgroundToolOwner
    private val args = JsonObject(emptyMap())

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "bg-control-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager = BackgroundToolManager(root, scope)
        owner = BackgroundToolOwner.of(Uuid.random())
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun fakeTool(
        name: String,
        mode: ToolExecutionMode = ToolExecutionMode.SERIAL,
        approval: Boolean = false,
        output: String = "$name done",
    ) = Tool(
        name = name,
        description = "fake $name",
        needsApproval = { approval },
        executionMode = mode,
    ) { listOf(UIMessagePart.Text(output)) }

    private fun startArgs(name: String) = buildJsonObject {
        put("action", "start")
        put("tool", name)
        put("arguments", buildJsonObject { put("cmd", "ls") })
    }

    @Test
    fun startForwardsApprovalAndRejectsForbiddenTargets() {
        val control = createBackgroundControlTools(
            manager = manager,
            owner = owner,
            settings = BackgroundToolSettings(),
            catalog = listOf(
                fakeTool("workspace_shell", approval = true),
                fakeTool("memory_read", ToolExecutionMode.PARALLEL_DEDUPLICATED),
                fakeTool("ask_user"),
            ),
        ).single()

        assertEquals("background_tool", control.name)
        // 目标工具要审批 → 入口也要审批
        assertTrue(control.needsApproval(startArgs("workspace_shell")))
        assertFalse(control.needsApproval(startArgs("memory_read")))
        assertFalse(control.needsApproval(buildJsonObject { put("action", "list") }))
        // 名字不认识时不需要审批：执行会直接失败，不会凭名字扩权
        assertFalse(control.needsApproval(startArgs("nonexistent")))
        // 控制入口自己永远不自动转后台
        assertFalse(isAutoBackgroundEligible(control))

        listOf("ask_user", "tool_batch", "background_tool", "nonexistent").forEach { name ->
            val outcome = runCatching { runBlocking { control.execute(startArgs(name)) } }
            assertTrue("'$name' 不该能通过后台入口启动", outcome.isFailure)
        }
        assertTrue(manager.list(owner).isEmpty())
    }

    @Test
    fun startThenWaitAndReadResult() {
        val release = CompletableDeferred<Unit>()
        val shell = Tool(name = "workspace_shell", description = "fake") {
            release.await()
            listOf(UIMessagePart.Text("shell output"))
        }
        val control = createBackgroundControlTools(manager, owner, BackgroundToolSettings(), listOf(shell)).single()

        val started = runBlocking { control.execute(startArgs("workspace_shell")) }
        assertTrue((started.single() as UIMessagePart.Text).text.contains("\"status\":\"started\""))
        val taskId = manager.list(owner).single().id

        val listed = runBlocking { control.execute(buildJsonObject { put("action", "list") }) }
        assertTrue((listed.single() as UIMessagePart.Text).text.contains(taskId))

        // 还没跑完：wait 只回状态，不装作有结果
        val waiting = runBlocking {
            control.execute(buildJsonObject { put("action", "wait"); put("task_id", taskId); put("timeout_seconds", 1) })
        }
        assertTrue((waiting.first() as UIMessagePart.Text).text.contains("\"status\":\"running\""))

        release.complete(Unit)
        val done = runBlocking {
            control.execute(buildJsonObject { put("action", "wait"); put("task_id", taskId); put("timeout_seconds", 5) })
        }
        assertTrue((done.first() as UIMessagePart.Text).text.contains("\"status\":\"completed\""))
        assertTrue(done.any { it is UIMessagePart.Text && it.text == "shell output" })

        val page = runBlocking { control.execute(buildJsonObject { put("action", "result"); put("task_id", taskId) }) }
        assertTrue(page.any { it is UIMessagePart.Text && it.text == "shell output" })
    }

    @Test
    fun cancelStopsTheRealExecution() {
        val started = CompletableDeferred<Unit>()
        val stopped = AtomicBoolean(false)
        val shell = Tool(name = "workspace_shell", description = "fake") {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.set(true)
            }
        }
        val control = createBackgroundControlTools(manager, owner, BackgroundToolSettings(), listOf(shell)).single()
        val task = manager.startDetached(shell, args, owner)
        runBlocking { started.await() }

        val cancelled = runBlocking {
            control.execute(buildJsonObject { put("action", "cancel"); put("task_id", task.id) })
        }
        assertTrue((cancelled.single() as UIMessagePart.Text).text.contains("\"cancelled\":true"))
        runBlocking {
            withTimeout(5_000) {
                while (!stopped.get() || manager.get(task.id, owner)?.isFinished != true) delay(5)
            }
        }
        assertEquals(BackgroundToolStatus.CANCELLED, manager.get(task.id, owner)!!.status)
    }
}
