package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

// 能读到 /tool_outputs 这个挂载的工具：RepositoryModule 把 filesDir/tool_outputs 挂到 /tool_outputs，
// 同一份挂载表也用于文件工具的路径解析。子代理手里没有其中之一时，截断提示里的路径它读不到。
private val TOOL_OUTPUT_READER_TOOLS = setOf("workspace_shell", "workspace_read_file")

/**
 * 子代理是否也应该把超长工具输出外置成文件。
 *
 * 旧行为：只有父对话截断，子代理拿全量输出，一次大 dump 就撑爆它自己的上下文。
 * 但无条件给子代理套截断同样有害——没有工作区、走远程工作区、或子代理设置里禁用了
 * workspace 工具时，子代理只会看到"全文已存到 /tool_outputs/xxx.txt"，却没有任何工具
 * 能读那个文件，信息从可用变成不可读。
 *
 * 因此要求同时满足两条：父对话具备本地工作区文件访问能力，且子代理实际拿到的工具里
 * 有能读 /tool_outputs 的那个。
 *
 * @param localWorkspaceFileAccess 本地工作区可用（与父侧截断判定完全同一个条件）
 * @param childTools 子代理最终会拿到的工具（已按 isSubagentToolAllowed 过滤）
 */
internal fun shouldTruncateChildToolOutput(
    localWorkspaceFileAccess: Boolean,
    childTools: List<Tool>,
): Boolean = localWorkspaceFileAccess && childTools.any { it.name in TOOL_OUTPUT_READER_TOOLS }

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
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
    ): List<Tool> {
        val workspaceTools = createWorkspaceToolsIfReady(
            assistant.workspaceId?.toString(),
            workspaceCwd,
            settings.workspaceRuntime,
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
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // fork: 子代理工具。childTools 里不含 subagent_*，避免无限嵌套。
        // 子代理可以额外拿到会话检索/读取工具，用来参考其他聊天。
        val childTools = (if (settings.subagent.includeChatHistory) {
            (tools +
                createConversationReadTool(conversationRepository, assistant.id) +
                createConversationTools(conversationRepository, assistant.id))
                .distinctBy { it.name }
        } else tools).filter { tool ->
            // 子代理的工具可见性遵循设置，避免开关是摆设
            isSubagentToolAllowed(tool.name, settings.subagent)
        }
        // 过长的输出存成文件让模型用 shell 按需读取；没有 shell 时模型读不到文件，只能原样返回。
        // 父侧判定逐字沿用原条件；子代理额外确认自己真的拿到了能读 /tool_outputs 的工具。
        val localWorkspaceFileAccess =
            workspaceTools.isNotEmpty() && settings.workspaceRuntime.remote?.enabled != true
        val childToolsForSubagent =
            if (shouldTruncateChildToolOutput(localWorkspaceFileAccess, childTools)) {
                childTools.map(::withOutputTruncation)
            } else childTools
        val withSubagent = if (settings.subagent.enabled) {
            tools + createSubagentTools(
                runner = subagentRunner,
                childTools = childToolsForSubagent,
                settings = settings.subagent,
                parentModelId = model.id,
                conversationId = conversationId,
            )
        } else tools
        return if (localWorkspaceFileAccess) withSubagent.map(::withOutputTruncation) else withSubagent
    }

    private fun withOutputTruncation(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it)) })
    }

    private fun truncateOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String?,
        runtime: me.rerere.rikkahub.data.datastore.WorkspaceRuntimeSettings,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name && runtime.remote?.enabled != true) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd, runtime).filter {
            if (runtime.remote?.enabled == true) {
                it.name == "workspace_shell" || it.name.startsWith("workspace_job_")
            } else workspace.shellStatus == WorkspaceShellStatus.READY.name
        }
    }
}
