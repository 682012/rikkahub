package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.agent.createToolBatch
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.background.BackgroundToolManager
import me.rerere.rikkahub.data.ai.tools.background.BackgroundToolOwner
import me.rerere.rikkahub.data.ai.tools.background.BACKGROUND_TOOLS_DIR
import me.rerere.rikkahub.data.ai.tools.background.createBackgroundControlTools
import me.rerere.rikkahub.data.ai.tools.background.wrapToolWithBackground
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.BackgroundToolSettings
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.effectiveExecutionBackend
import me.rerere.rikkahub.data.repository.isToolAvailableOnBackend
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.repository.workspaceToolsReady
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.service.BackgroundShellManager
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"

/** 自己分页、自己控制输出量的工具: 不再经 ToolOutputStore 外置成文件 */
internal val SELF_PAGED_TOOL_NAMES: Set<String> = setOf("workspace_read_file", "workspace_grep", "workspace_glob")

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

// fork: 后台 shell 任务归属父会话，子代理不拿这两个工具
private val BACKGROUND_SHELL_TOOLS = setOf("workspace_shell_output", "workspace_shell_kill")

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val subagentRunner: me.rerere.rikkahub.data.ai.subagent.SubagentRunner,
    private val backgroundShellManager: BackgroundShellManager,
    // 通用工具转后台：Koin 里应传同一个实例（见 BACKGROUND-WIRING.md），
    // 默认自建一份保证未接线时也能编译/运行（只是不共享完成提醒的收件箱）。
    private val backgroundToolManager: BackgroundToolManager = BackgroundToolManager(
        File(context.filesDir, BACKGROUND_TOOLS_DIR),
        lease = me.rerere.rikkahub.data.ai.tools.background.AndroidBackgroundForegroundLease(context),
    ),
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
    ): List<Tool> {
        // fork: 工作区实体只取一次，工具可见性判断共用同一份 workspace
        val workspace = assistant.workspaceId?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { workspaceRepository.getById(it) }
        val workspaceTools = createWorkspaceToolsIfReady(
            workspace = workspace,
            cwd = workspaceCwd,
            runtime = settings.workspaceRuntime,
            conversationId = conversationId,
            backgroundShellManager = backgroundShellManager,
        )
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(workspaceTools)
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        // MCP 的 annotations 由服务器自报、不可信：readOnlyHint 只换来并发执行（每次调用照样执行），
                        // 不据 idempotentHint 合并相同调用，免得自报不实时把真实执行吞掉
                        executionMode = if (tool.readOnlyHint == true) ToolExecutionMode.PARALLEL else ToolExecutionMode.SERIAL,
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // External schemas are discovered lazily only when their catalog becomes large.
        // Native tools have explicit local scheduling metadata; unknown MCP tools remain serial.
        val outputStore = ToolOutputStore(
            File(context.filesDir, FileFolders.TOOL_OUTPUTS),
            // fork: owner 只按会话，不含 assistant.id——聊天页中途换助手不该让旧 output_id 失效
            toolOutputOwnerOf(conversationId),
        )
        val baseTools = tools.map(::withBuiltinExecutionMode)
        val childBaseTools = if (settings.subagent.enabled && workspaceTools.any { it.name in BACKGROUND_SHELL_TOOLS }) {
            val plain = createWorkspaceToolsIfReady(
                workspace = workspace,
                cwd = workspaceCwd,
                runtime = settings.workspaceRuntime,
            ).associateBy { it.name }
            baseTools.filter { it.name !in BACKGROUND_SHELL_TOOLS }.map { withBuiltinExecutionMode(plain[it.name] ?: it) }
        } else baseTools
        // Apply child visibility BEFORE proxying: no generic gateway can bypass includeMcp=false.
        val childVisible = (if (settings.subagent.includeChatHistory) {
            (childBaseTools +
                createConversationReadTool(conversationRepository, assistant.id) +
                createConversationTools(conversationRepository, assistant.id))
                .distinctBy { it.name }
        } else childBaseTools).filter { tool -> isSubagentToolAllowed(tool.name, settings.subagent) }
        val childTools = prepareEfficientTools(childVisible.map(::withBuiltinExecutionMode), outputStore)
        val parentTools = if (settings.subagent.enabled) {
            baseTools + createSubagentTools(
                runner = subagentRunner,
                childTools = childTools,
                settings = settings.subagent,
                parentModelId = model.id,
                conversationId = conversationId,
            )
        } else baseTools
        // fork r11: 通用工具转后台。只在**父**工具集上生效——子代理的工具集不递归后台，
        // 否则子代理自己的 write 调用会把父会话的后台任务表当自己家。
        // 资格按耗时判断（SERIAL/PARALLEL/PARALLEL_DEDUPLICATED 都可能），"超时会转后台"这条提示
        // 只在 background_tool 的 systemPrompt 里出现一次，不往每个工具上追加。
        val background = BackgroundRuntime(
            backgroundToolManager, BackgroundToolOwner.of(conversationId), settings.backgroundToolSettings.normalized(),
        )
        return prepareEfficientTools(parentTools, outputStore, assistant.localTools, background)
    }

    /** 一次生成里的后台配置：conversation 归属 + 前台等待上限。 */
    private class BackgroundRuntime(
        val manager: BackgroundToolManager,
        val owner: BackgroundToolOwner,
        val settings: BackgroundToolSettings,
    ) {
        fun wrap(tool: Tool): Tool = wrapToolWithBackground(tool, manager, owner, settings)

        fun controlTools(catalog: List<Tool>): List<Tool> =
            createBackgroundControlTools(manager, owner, settings, catalog)
    }

    private fun prepareEfficientTools(
        tools: List<Tool>,
        outputStore: ToolOutputStore,
        localToolOptions: List<LocalToolOption> = emptyList(),
        background: BackgroundRuntime? = null,
    ): List<Tool> {
        val compact = compactMcpTools(tools)
        val readerName = uniqueToolName(TOOL_OUTPUT_READ_TOOL_NAME, compact.map { it.name }.toSet())
        val readerTool = outputStore.createReaderTool(readerName)
        // Large output is offloaded once before background retention; task pages keep its durable output_id.
        val outputAware = compact.map { tool ->
            if (tool.name in SELF_PAGED_TOOL_NAMES) tool else outputStore.wrap(tool)
        }
        val scheduled = if (background == null) outputAware else outputAware.map { background.wrap(it) }
        val readable = scheduled + readerTool
        // Code Mode 的工具必须最后组装：它要拿到已经拼好的完整清单（工作区工具加入得晚），
        // 且只注入只读白名单。传进去的是**未包装**的 compact，嵌套调用自己调 execute，
        // 免得回执套回执；它自己则照常被 outputStore 包一层。
        // 注意 createToolBatch 的 catalog 只给 readable，脚本工具不会出现在 tool_batch 里。
        val scriptTools = localTools.scriptTool(
            options = localToolOptions,
            catalog = compact + readerTool,
            outputStore = outputStore,
        )?.let { listOf(outputStore.wrap(it)) } ?: emptyList<Tool>()
        // 显式后台入口的 catalog 用本轮**未包装**的 compact：start 由 manager 自己 detach，
        // 不会再套一层；这个工具自己也不参与自动转后台（见 BackgroundToolWrapping.kt）。
        val backgroundTools = background?.controlTools(outputAware) ?: emptyList()
        // A single structured call can execute an already planned set without extra model round trips.
        return (readable + outputStore.wrap(createToolBatch(readable)) + scriptTools + backgroundTools)
            .sortedBy { it.name }
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspace: WorkspaceEntity?,
        cwd: String?,
        runtime: me.rerere.rikkahub.data.datastore.WorkspaceRuntimeSettings,
        conversationId: Uuid? = null,
        backgroundShellManager: BackgroundShellManager? = null,
    ): List<Tool> {
        if (workspace == null) return emptyList()
        // 后端先定下来，再按它判断“就绪”和“这个工具在该后端下有没有意义”：
        // SSH 不再因为全局 HTTP 开关而被点亮 job 工具，也不会被整体绕过就绪检查。
        val backend = effectiveExecutionBackend(workspace.isSsh(), runtime)
        val androidTools = if (workspaceRepository.androidShellAuthorized(workspace.id)) {
            listOf(createWorkspaceAndroidShellTool(workspace, workspaceRepository, runtime))
        } else emptyList()
        if (!workspaceToolsReady(backend, workspace.shellStatus)) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=${workspace.id}, " +
                    "status=${workspace.shellStatus}, backend=$backend"
            )
            return androidTools
        }
        return createWorkspaceTools(
            workspace.id, workspaceRepository, cwd, runtime,
            conversationId = conversationId,
            backgroundShellManager = backgroundShellManager,
        ).filter {
            isToolAvailableOnBackend(it.name, backend)
        } + androidTools
    }
}
