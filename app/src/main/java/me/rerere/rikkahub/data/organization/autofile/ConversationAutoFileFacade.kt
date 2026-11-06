package me.rerere.rikkahub.data.organization.autofile

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 自动归档对外的**稳定小门面**（给本机 MCP / 自动化用）。
 *
 * 六个动作：状态 [status]、历史 [history]、详情 [details]、触发 [trigger]、取消 [cancel]、撤销 [undo]，
 * 外加一个显式配置入口 [configure]。界面用的 Controller 仍然是同一份状态源，两边不会读到不同结果。
 *
 * 数据边界（默认最小导出）：
 * - 每个方法都必须显式带 `assistantId`，**没有**「一次导出所有助手」的接口；
 * - [enabledAssistantIds] 只列**已开启自动归档**的助手 id，不泄露助手列表本身；
 * - 导出内容只有会话 id、标题、目标库名、状态和可解释理由；**不含消息正文**，也不含其他助手的数据。
 */
class ConversationAutoFileFacade(
    private val controller: ConversationAutoFileController,
) {
    /** 当前状态：开关、模式、是否在跑、最近一批移动数、能否撤销、错误。 */
    fun status(assistantId: Uuid): AutoFileFacadeStatus {
        val state = controller.status(assistantId)
        return AutoFileFacadeStatus(
            assistantId = assistantId.toString(),
            enabled = state.settings.enabled,
            mode = state.settings.mode.name.lowercase(),
            idleHours = state.settings.idleHours,
            running = state.running,
            moved = state.moved,
            canUndo = state.canUndo,
            error = state.error,
        )
    }

    /** 历史批次，最新的在最前；[limit] 夹在 1..日志保留上限之间。 */
    suspend fun history(assistantId: Uuid, limit: Int = DEFAULT_HISTORY): List<AutoFileFacadeBatch> =
        controller.history(assistantId, limit).map { it.toFacade() }

    /** 一批的逐条明细（会话 id/标题、目标库、状态、理由）。批不存在时返回 null。 */
    suspend fun details(assistantId: Uuid, batchId: String): AutoFileFacadeDetail? {
        val detail = controller.details(assistantId, batchId) ?: return null
        return AutoFileFacadeDetail(
            batch = detail.batch.toFacade(),
            moves = detail.batch.moves.map { move ->
                AutoFileFacadeMove(
                    conversationId = move.conversationId,
                    title = detail.titles[runCatching { Uuid.parse(move.conversationId) }.getOrNull()],
                    folderName = move.folderName,
                    folderId = move.toFolderId,
                    status = move.status,
                    reason = move.reason,
                    error = move.error,
                )
            },
        )
    }

    /**
     * 触发一次整理（等同界面上的「立即整理」）。
     * 仍然守 24 小时以上闲置门槛、置顶/手动归类/加锁不动、整批可撤销。
     */
    suspend fun trigger(assistantId: Uuid): AutoFileFacadeStatus {
        controller.organizeNow(assistantId)
        return status(assistantId)
    }

    /** 叫停正在跑的一批（不取消后续周期调度，那由 [configure] 管）。 */
    suspend fun cancel(assistantId: Uuid): AutoFileFacadeStatus {
        controller.cancelRun(assistantId)
        return status(assistantId)
    }

    /** 撤销最近一批（用户后来聊过/手动移动/置顶/加锁的项会被跳过）。 */
    suspend fun undo(assistantId: Uuid): AutoFileFacadeStatus {
        controller.undoLast(assistantId)
        return status(assistantId)
    }

    /**
     * 配置开关/模式（自动化里可持久化）。
     *
     * 打开 **AI 模式**必须显式 `confirmExternalModelUsage = true`：这条路径绕过了界面上「会外发摘要、
     * 花 API 额度」的确认，所以由调用方明确承担；本地规则模式不发网，不需要这个确认。
     */
    suspend fun configure(
        assistantId: Uuid,
        enabled: Boolean,
        mode: AutoFileMode = AutoFileMode.AI,
        idleHours: Int? = null,
        confirmExternalModelUsage: Boolean = false,
    ): AutoFileFacadeStatus {
        require(!(enabled && mode == AutoFileMode.AI) || confirmExternalModelUsage) {
            "AI 模式会把限长摘要发给快速模型并消耗额度，自动化开启时必须显式确认"
        }
        val current = controller.status(assistantId).settings
        controller.updateSettings(
            assistantId,
            current.copy(
                enabled = enabled,
                mode = mode,
                idleHours = idleHours ?: current.idleHours,
            ),
        )
        return status(assistantId)
    }

    /** 只列已开启自动归档的助手 id（不导出完整助手列表）。 */
    fun enabledAssistantIds(): List<String> = controller.enabledAssistantIds().map { it.toString() }

    private fun AutoFileJournalBatch.toFacade(): AutoFileFacadeBatch = AutoFileFacadeBatch(
        id = id,
        assistantId = assistantId,
        createdAt = createdAt,
        finishedAt = finishedAt,
        mode = modeOrDefault.name.lowercase(),
        status = status,
        total = moves.size,
        moved = movedCount,
        failed = failedCount,
        skipped = skippedCount,
        undone = undoneCount,
        canUndo = isUndoable(),
    )

    private companion object {
        const val DEFAULT_HISTORY = 5
    }
}

@Serializable
data class AutoFileFacadeStatus(
    val assistantId: String,
    val enabled: Boolean,
    val mode: String,
    val idleHours: Int,
    val running: Boolean,
    val moved: Int,
    val canUndo: Boolean,
    val error: String? = null,
)

@Serializable
data class AutoFileFacadeBatch(
    val id: String,
    val assistantId: String,
    val createdAt: Long,
    val finishedAt: Long? = null,
    val mode: String,
    val status: String,
    val total: Int,
    val moved: Int,
    val failed: Int,
    val skipped: Int,
    val undone: Int,
    val canUndo: Boolean,
)

@Serializable
data class AutoFileFacadeMove(
    val conversationId: String,
    val title: String? = null,
    val folderName: String? = null,
    val folderId: String? = null,
    val status: String,
    val reason: String? = null,
    val error: String? = null,
)

@Serializable
data class AutoFileFacadeDetail(
    val batch: AutoFileFacadeBatch,
    val moves: List<AutoFileFacadeMove> = emptyList(),
)
