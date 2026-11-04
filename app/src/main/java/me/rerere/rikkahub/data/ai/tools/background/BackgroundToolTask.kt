package me.rerere.rikkahub.data.ai.tools.background

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** 后台任务记录目录（`filesDir` 下）。 */
internal const val BACKGROUND_TOOLS_DIR = "background_tools"

/** 没有会话归属（理论上不该出现）时用的 owner 目录名。 */
internal const val BACKGROUND_OWNER_UNSCOPED = "unscoped"

/** 后台任务元数据文件名；正文在同一个目录的 `result.txt`。 */
internal const val BACKGROUND_TASK_FILE = "task.json"
internal const val BACKGROUND_RESULT_FILE = "result.txt"

/** 单个会话保留多少个任务记录（内存与磁盘都按这个截断）。 */
internal const val BACKGROUND_MAX_TASKS_PER_OWNER = 32

/** 进程内最多保留多少个任务记录（跨会话的总上限）；超出时只从内存里淘汰最老的终态任务。 */
internal const val BACKGROUND_MAX_TASKS_TOTAL = 256

/** 每个会话最多为多少条终态任务保留正文（内存里的完整 parts / 附件）；更老的正文只留在磁盘上。 */
internal const val BACKGROUND_MAX_RETAINED_BODIES = 8

/** 参数预览长度：任务列表里够模型认出是哪一次调用就行。 */
internal const val BACKGROUND_MAX_ARGUMENT_CHARS = 2_000

/**
 * 单条后台结果正文的落盘上限（字符）。
 *
 * 超过这个长度**不静默截断**：任务记录里 `truncated = true`、`storedChars < resultChars`，
 * 分页头会带 `truncated` / `source_chars` / `dropped_chars`，模型能看出尾部缺失。
 */
internal const val BACKGROUND_MAX_RESULT_CHARS = 2_000_000

/** 进程内保留的完整 parts（含附件）的正文上限；超过只留磁盘，分页照常能读。 */
internal const val BACKGROUND_MAX_MEMORY_RESULT_CHARS = 2_000_000

/** 单页默认/上限字符数，与工具输出外置、子代理结果分页同一套单位（UTF-16 code unit）。 */
internal const val BACKGROUND_RESULT_DEFAULT_LIMIT = 4_000
internal const val BACKGROUND_RESULT_MAX_LIMIT = 12_000

/** 完成提醒里的正文预览上限：通知只做指针，正文靠分页读。 */
internal const val BACKGROUND_NOTICE_PREVIEW_CHARS = 200

/** `background_tool action=wait` 的默认/上限等待秒数。 */
internal const val BACKGROUND_WAIT_DEFAULT_SECONDS = 30
internal const val BACKGROUND_WAIT_MAX_SECONDS = 120

/** wait 轮询间隔：任务完成是内存状态变化，不需要更细。 */
internal const val BACKGROUND_WAIT_POLL_MS = 100L

/** 空输出占位：和 AgentLoop 保持一致，空输出会被当成"没执行过"再跑一次。 */
internal const val BACKGROUND_EMPTY_OUTPUT = "(no output)"

@Serializable
enum class BackgroundToolStatus {
    /** 正在跑（可能还在等串行屏障） */
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,

    /**
     * 进程重启时这条记录还是 RUNNING: 效果未知，**绝不重放**。
     * 读到它只能去核对真实世界状态再决定要不要重新发一次调用。
     */
    INTERRUPTED,
}

/**
 * 一次后台工具调用。
 *
 * 归属只按 `conversationId`（与 `ToolOutputStore` / `SubagentRunner` 一致）：聊天页中途换助手不该让
 * 同一个会话里的旧任务查不到。
 */
@Serializable
data class BackgroundToolTask(
    val id: String,
    val toolName: String,
    val conversationId: String? = null,
    /** 参数预览（截断），用于列表里辨认；完整参数不进磁盘。 */
    val arguments: String = "",
    val status: BackgroundToolStatus = BackgroundToolStatus.RUNNING,
    /** 由 `background_tool action=start` 显式起在后台 */
    val explicit: Boolean = false,
    /** 曾经在前台等待、超时后转入后台 */
    val backgrounded: Boolean = false,
    val createdAt: Long = 0L,
    val finishedAt: Long? = null,
    /** 真实全文长度（字符）；正文可能比它短（见 [storedChars] / [truncated]）。 */
    val resultChars: Int = 0,
    /** 仍然留存、可读的正文长度（磁盘或内存）；前台已经交付完的调用是 0。 */
    val storedChars: Int = 0,
    /**
     * 正文被截断：[storedChars] 小于 [resultChars]。
     * 尾部确实读不到——分页头会明说，不会假装 [resultChars] 全部可读。
     */
    val truncated: Boolean = false,
    /**
     * 正文是否还留着（磁盘或同进程内存）。
     *
     * 在前台等到结果的普通调用不保留正文：调用方已经拿到输出，留着只是白占内存和磁盘。
     */
    val bodyRetained: Boolean = true,
    /** 失败/取消原因；INTERRUPTED 时说明效果未知 */
    val error: String? = null,
    /** 落盘失败的原因（内存里照样能读，但重启后会丢）。绝不影响本次前台输出。 */
    val persistenceError: String? = null,
    /** 结果里是否带非文本附件（图片/文档…）。附件只在同一进程内保留。 */
    val hasAttachments: Boolean = false,
) {
    val isFinished: Boolean get() = status != BackgroundToolStatus.RUNNING

    /** 是否已经脱离前台调用者（后台/显式），只有这种任务结束时才发完成提醒。 */
    val isDetached: Boolean get() = backgrounded || explicit
}

/** 一次分页读取。 */
data class BackgroundToolResultPage(
    val task: BackgroundToolTask,
    val offset: Int,
    val limit: Int,
    val totalChars: Int,
    val text: String,
    val endOffset: Int,
    val eof: Boolean,
    /** 同进程内保留的非文本附件；只有第一页带，重启后为空。 */
    val attachments: List<UIMessagePart> = emptyList(),
)

/**
 * 完成提醒事件。和子代理一样只表达"放一条通知进收件箱"：
 * 不发起新请求、不跨会话发送。
 */
data class BackgroundToolNotice(
    val conversationId: Uuid,
    val key: String,
    val notice: UIMessagePart.Notice,
)

/** 任务归属：只按会话。 */
data class BackgroundToolOwner(val conversationId: Uuid?) {
    val key: String = conversationId?.toString() ?: BACKGROUND_OWNER_UNSCOPED

    companion object {
        fun of(conversationId: Uuid?): BackgroundToolOwner = BackgroundToolOwner(conversationId)
    }
}
