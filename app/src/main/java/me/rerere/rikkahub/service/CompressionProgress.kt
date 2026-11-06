package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.event.CompressionOutcome
import kotlin.uuid.Uuid

/**
 * 压缩的真实阶段。没有总量可算，所以这里只有阶段，没有任何百分比；UI 一律用不确定进度条。
 */
enum class CompressionPhase {
    /** 取快照、校验范围、组装请求 */
    Preparing,

    /** 请求已经发出，还没收到第一个分块（含排队、重试等待） */
    WaitingModel,

    /** 已经收到分块，摘要正在流式生成 */
    Generating,

    /** 摘要生成完了，正在校验并写入检查点 */
    Writing,
}

/**
 * 一次压缩在某个会话上的状态，供聊天页显示。任务本身在 [ChatService] 的 AppScope 里跑，
 * 这个状态只反映最近一次任务，不持有协程。手动后台压缩和自动压缩都写这里。
 */
data class CompressionState(
    val taskId: Uuid,
    /** null 表示还在压缩 */
    val outcome: CompressionOutcome?,
    /** 失败/丢弃的原因，成功时为 null */
    val message: String? = null,
    val keepRecentMessages: Int = 0,
    val startedAt: Long = 0L,
    val finishedAt: Long? = null,
    /** 运行中的真实阶段；结束后保留最后阶段，UI 配合 [outcome] 显示结果 */
    val phase: CompressionPhase = CompressionPhase.Preparing,
    /** 已经收到的摘要字符数（Unicode code point），来自模型实际返回，不是估算 */
    val generatedChars: Int = 0,
    /** 已经收到的流式分块数，同样是实际值 */
    val chunks: Int = 0,
    /** 摘要开头的一小段，只用于折叠预览；永远不超过 [COMPRESSION_PREVIEW_LIMIT] 个字符 */
    val summaryPreview: String = "",
    /** 自动压缩跟着生成任务走，没有可以单独取消的后台任务，UI 不给取消按钮 */
    val cancellable: Boolean = true,
) {
    val isRunning: Boolean get() = outcome == null

    /** 只有真的收到过分块才算有进度，否则仍是「等待模型」 */
    val hasGeneratedText: Boolean get() = generatedChars > 0
}

/** 摘要预览最多保留的字符数：够看清开头，又不会把整篇摘要复制一份到状态里 */
const val COMPRESSION_PREVIEW_LIMIT = 240

/**
 * 增量统计 Unicode 字符（code point）数。
 *
 * 代理对（emoji、部分生僻字）在 Kotlin String 里占两个 char，直接数 `length` 会虚高；
 * 这里逐次累计、只记住是否有一个高代理项悬着，不需要每次重新扫整段文本。
 */
internal class UnicodeCharCounter {
    private var count = 0
    private var pendingHighSurrogate = false

    /** 追加一段文本，返回累计字符数 */
    fun append(text: String): Int {
        for (char in text) {
            if (pendingHighSurrogate) {
                pendingHighSurrogate = false
                // 高代理项 + 低代理项 = 一个字符；落单的高代理项自己也按一个字符算
                count++
                if (char.isLowSurrogate()) continue
            }
            if (char.isHighSurrogate()) {
                pendingHighSurrogate = true
            } else {
                count++
            }
        }
        return value
    }

    val value: Int get() = if (pendingHighSurrogate) count + 1 else count
}

/**
 * 压缩进度上报器：把 provider 流式分块换成 [CompressionState] 更新。
 *
 * - 节流：分块可能几十毫秒来一个，状态不跟着每块重发；默认最多每 [minIntervalMs] 发一次。
 * - 尾刷新：被节流压下去的更新由 [flush] 补发最近一次真实值，结束时不会停在旧字数上。
 * - 状态只保留前 [COMPRESSION_PREVIEW_LIMIT] 个字符的预览，不做全文复制。
 */
internal class CompressionProgressTracker(
    initialState: CompressionState,
    private val minIntervalMs: Long = 150L,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val publish: (CompressionState) -> Unit,
) {
    private val lock = Any()
    private val counter = UnicodeCharCounter()
    private val preview = StringBuilder()
    private var current = initialState
    private var lastPublishedAt = 0L
    private var dirty = false

    /** 当前最新状态（不一定已经发出去了） */
    fun snapshot(): CompressionState = synchronized(lock) { current }

    /** 阶段切换：阶段一定要让用户看到，直接发，并顺手把攒下的字数一起带上 */
    fun phase(phase: CompressionPhase) {
        synchronized(lock) {
            if (current.phase == phase) return
            current = current.copy(phase = phase)
            publishLocked(force = true)
        }
    }

    /** 收到一个流式分块；[text] 是这块里的正文（可能为空，比如只有用量/结束事件） */
    fun onChunk(text: String) {
        synchronized(lock) {
            val chars = counter.append(text)
            if (text.isNotEmpty() && preview.length < COMPRESSION_PREVIEW_LIMIT) {
                val room = COMPRESSION_PREVIEW_LIMIT - preview.length
                var take = minOf(text.length, room)
                // 截断不能把代理对劈成两半，否则预览末尾会剩半个 emoji
                if (take in 1 until text.length && text[take - 1].isHighSurrogate()) take--
                if (take > 0) preview.append(text, 0, take)
            }
            current = current.copy(
                phase = CompressionPhase.Generating,
                generatedChars = chars,
                chunks = current.chunks + 1,
                summaryPreview = preview.toString(),
            )
            publishLocked(force = false)
        }
    }

    /** 尾刷新：把最近一次真实值发出去（结束时、换阶段时调用），没有新内容就不重复发 */
    fun flush() {
        synchronized(lock) {
            if (dirty) publishLocked(force = true)
        }
    }

    private fun publishLocked(force: Boolean) {
        val at = now()
        if (!force && at - lastPublishedAt < minIntervalMs) {
            dirty = true
            return
        }
        lastPublishedAt = at
        dirty = false
        publish(current)
    }
}

/**
 * 结束状态：只改结果字段，保留已经上报的阶段/字数/预览。
 *
 * 迟到的旧任务（状态里已经是别的 taskId）一律不覆盖：压缩任务被取消后它的 finally 会晚一步跑到，
 * 那次写入不能盖掉同一会话上刚开始的新任务。
 */
internal fun Map<Uuid, CompressionState>.finishCompression(
    conversationId: Uuid,
    taskId: Uuid,
    outcome: CompressionOutcome,
    message: String?,
    now: Long = System.currentTimeMillis(),
): Map<Uuid, CompressionState> {
    val existing = this[conversationId]
    if (existing != null && existing.taskId != taskId) return this
    val base = existing ?: CompressionState(taskId = taskId, outcome = null, startedAt = now)
    return this + (conversationId to base.copy(
        outcome = outcome,
        message = message,
        finishedAt = now,
    ))
}

