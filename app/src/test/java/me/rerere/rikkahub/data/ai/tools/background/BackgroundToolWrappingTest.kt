package me.rerere.rikkahub.data.ai.tools.background

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.BackgroundToolSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * 包装边界。
 *
 * 四条底线：包装不改变工具对外声明的任何东西（名字/描述/审批/调度模式/repeatable/systemPrompt）；
 * 不该转后台的工具**原样返回同一个实例**；资格按「耗时」而不是「读写」判断；
 * 嵌套调用（tool_batch 成员）不再叠一层任务。
 */
class BackgroundToolWrappingTest {
    private lateinit var scope: CoroutineScope
    private lateinit var root: File
    private lateinit var manager: BackgroundToolManager
    private val owner = BackgroundToolOwner.of(Uuid.random())
    private val testModel = Model()

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "bg-wrapping-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager = BackgroundToolManager(root, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun tool(
        name: String,
        mode: ToolExecutionMode = ToolExecutionMode.SERIAL,
        needsApproval: Boolean = false,
        repeatable: Boolean = false,
        prompt: String = "orig prompt for $name",
    ) = Tool(
        name = name,
        description = "fake $name",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { put("cmd", buildJsonObject { put("type", "string") }) })
        },
        systemPrompt = { _, _ -> prompt },
        needsApproval = { needsApproval },
        executionMode = mode,
        repeatable = repeatable,
    ) { listOf(UIMessagePart.Text("$name ok")) }

    @Test
    fun wrappingKeepsEveryDeclarationIncludingSystemPrompt() {
        val original = tool("workspace_shell", needsApproval = true)
        val wrapped = wrapToolWithBackground(original, manager, owner, BackgroundToolSettings())

        assertEquals(original.name, wrapped.name)
        assertEquals(original.description, wrapped.description)
        assertEquals(original.executionMode, wrapped.executionMode)
        assertEquals(original.repeatable, wrapped.repeatable)
        assertTrue(wrapped.needsApproval(JsonObject(emptyMap())))
        assertNotNull(wrapped.parameters())
        assertEquals(original.parameters(), wrapped.parameters())
        // systemPrompt 一个字都不能变：每个工具都追加一段英文会把 system prompt 拖长几十段
        assertEquals(original.systemPrompt(testModel, emptyList()), wrapped.systemPrompt(testModel, emptyList()))
        assertEquals("orig prompt for workspace_shell", wrapped.systemPrompt(testModel, emptyList()))
    }

    @Test
    fun parallelToolsKeepTheirSchedulingModeWhenWrapped() {
        val read = tool("workspace_grep", ToolExecutionMode.PARALLEL_DEDUPLICATED, needsApproval = true)
        val wrapped = wrapToolWithBackground(read, manager, owner, BackgroundToolSettings())

        // 只读慢工具也按时间阈值转后台，但调度模式（可并行/去重）原样保留
        assertEquals(ToolExecutionMode.PARALLEL_DEDUPLICATED, wrapped.executionMode)
        assertTrue(wrapped.needsApproval(JsonObject(emptyMap())))
        assertEquals(read.name, wrapped.name)
    }

    @Test
    fun eligibilityFollowsTimeNotWriteMode() {
        val settings = BackgroundToolSettings()
        // 慢的只读工具同样是转后台的目标：用户在意的是「等太久」，不是「有没有副作用」
        listOf(
            tool("workspace_shell"),
            tool("workspace_write_file"),
            tool("workspace_grep", ToolExecutionMode.PARALLEL_DEDUPLICATED),
            tool("workspace_read_file", ToolExecutionMode.PARALLEL_DEDUPLICATED),
            tool("mcp__remote__slow_query", ToolExecutionMode.PARALLEL),
        ).forEach { eligible ->
            assertTrue("${eligible.name} 应该能自动转后台", isAutoBackgroundEligible(eligible))
            assertFalse(
                "${eligible.name} 应该被包装",
                wrapToolWithBackground(eligible, manager, owner, settings) === eligible,
            )
        }

        // 交互式 / 批量入口 / 脚本 / 子代理家族 / 控制入口 / 自带后台作业控制 / 轮询类
        listOf(
            tool("ask_user"),
            tool("tool_batch"),
            tool("run_script"),
            tool("subagent_run", ToolExecutionMode.PARALLEL),
            tool("background_tool", repeatable = true),
            tool("workspace_shell_output", repeatable = true),
            tool("workspace_shell_kill", repeatable = true),
            tool("workspace_job_status", ToolExecutionMode.PARALLEL, repeatable = true),
            tool("memory_read", ToolExecutionMode.PARALLEL_DEDUPLICATED, repeatable = true),
        ).forEach { ineligible ->
            assertFalse("${ineligible.name} 不该自动转后台", isAutoBackgroundEligible(ineligible))
            assertSame(
                "${ineligible.name} 必须原样返回同一个实例",
                ineligible,
                wrapToolWithBackground(ineligible, manager, owner, settings),
            )
        }
        // 关掉功能时连包装都不做
        val shell = tool("workspace_shell")
        assertSame(shell, wrapToolWithBackground(shell, manager, owner, BackgroundToolSettings(enabled = false)))
    }

    @Test
    fun onlyTheControlToolCarriesTheBackgroundHint() {
        val control = createBackgroundControlTools(
            manager = manager,
            owner = owner,
            settings = BackgroundToolSettings(),
            catalog = listOf(tool("workspace_shell")),
        ).single()

        val hint = control.systemPrompt(testModel, emptyList())
        assertTrue(hint.contains("background_tool"))
        assertTrue(hint.contains("task_id"))
        // 只有这一段：整轮说一次，而不是几十个工具各来一段
        assertTrue(hint.length < 600)
        assertEquals(BACKGROUND_CONTROL_HINT, hint)
    }

    @Test
    fun nestedCallDoesNotStackASecondTask() {
        val wrapped = wrapToolWithBackground(tool("workspace_shell"), manager, owner, BackgroundToolSettings())
        val output = runBlocking {
            // tool_batch 的成员就是在这个上下文里被调用的：外层已经持屏障，内层不该再起任务
            withContext(BackgroundBarrierHeld()) { wrapped.execute(JsonObject(emptyMap())) }
        }
        assertEquals("workspace_shell ok", (output.single() as UIMessagePart.Text).text)
        assertTrue(manager.list(owner).isEmpty())
    }
}
