package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.alignPageStart
import me.rerere.rikkahub.data.ai.tools.pageEndFor
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

enum class SubagentStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT,
}

data class SubagentTask(
    val id: String,
    val prompt: String,
    val modelId: Uuid?,
    val conversationId: Uuid?,
    val status: SubagentStatus = SubagentStatus.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val result: String? = null,
    val error: String? = null,
    val usage: TokenUsage? = null,
    val steps: Int = 0,
    val resultPath: String? = null,
    val logPath: String? = null,
    /**
     * 实际尝试过的模型（按尝试顺序，含成功的那个）。[modelId] 是最终/当前使用的模型，
     * 这里保留完整轨迹，便于 UI 和模型看到"换过几次渠道、换到了谁"。
     */
    val triedModelIds: List<Uuid> = emptyList(),
    /** 已经发起的模型尝试次数（>=1 表示至少发出过一次请求）。 */
    val attempts: Int = 0,
    /** 换渠道说明，例如 "A -> B (empty output)"；没换过时为 null。 */
    val switchNote: String? = null,
) {
    val isFinished: Boolean
        get() = status in setOf(
            SubagentStatus.COMPLETED,
            SubagentStatus.FAILED,
            SubagentStatus.CANCELLED,
            SubagentStatus.TIMEOUT,
        )
}

/** subagent_result / subagent_wait 每页默认字符数。 */
const val SUBAGENT_RESULT_DEFAULT_LIMIT = 6_000

/** 单页上限：一次调用不会把整份长结果倒进上下文。 */
const val SUBAGENT_RESULT_MAX_LIMIT = 12_000

/** 一次 subagent_wait 最多同时等待的任务数。 */
const val SUBAGENT_MAX_WAIT_TASKS = 8

/** 完成通知里带的正文预览上限；通知只做指针，正文靠 subagent_result 分页读。 */
const val SUBAGENT_NOTICE_PREVIEW_CHARS = 200

/** 一次 subagent_wait 里所有任务首屏正文的总预算（字符）。单条仍可到 [SUBAGENT_RESULT_MAX_LIMIT]。 */
const val SUBAGENT_WAIT_PAGE_BUDGET = 8_000

/** 多任务 wait 时每条任务的最低首屏额度：太小的页模型读不出结论，还得再来一次。 */
const val SUBAGENT_WAIT_MIN_PAGE = 500

/**
 * 一次结果读取的切片。
 *
 * 分页信息是给模型判断"还要不要再读"的唯一依据：
 * [nextOffset] 是下一页的起点（等于本页结束位置 [endOffset]），[eof] 表示已经读到末尾。
 * [truncated] 只表示"这不是全文，后面还有"，不表示内容被丢弃——任何状态、任何 offset 都可以重新读。
 *
 * 注意 nextOffset 取 [endOffset] 而不是 `offset + text.length`：页首可能为对齐代理对回退一位、
 * 页尾可能后让一位，`offset + text.length` 会因此跳过或重复字符。
 */
data class SubagentResultPage(
    val taskId: String,
    val status: SubagentStatus,
    val steps: Int,
    val offset: Int,
    val limit: Int,
    val totalChars: Int,
    val text: String,
    /** 本页结束位置（字符，UTF-16 code unit），下一页从这里开始。 */
    val endOffset: Int = offset + text.length,
) {
    val eof: Boolean get() = nextOffset >= totalChars

    val nextOffset: Int get() = endOffset

    val truncated: Boolean get() = !eof

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("taskId", taskId)
        put("status", status.name)
        put("steps", steps)
        put("offset", offset)
        put("limit", limit)
        put("totalChars", totalChars)
        put("returnedChars", text.length)
        put("nextOffset", nextOffset)
        put("eof", eof)
        put("truncated", truncated)
        put("result", text)
        if (!eof) {
            put(
                "readHint",
                "Read the rest with subagent_result(task_id=\"$taskId\", offset=$nextOffset).",
            )
        }
    }
}

/**
 * 按 offset/limit 切一段结果。
 *
 * 纯函数，便于单测：offset 越界时夹到末尾返回空页（eof=true），limit 非正数用默认值，
 * 大于上限按上限截断。
 *
 * 页首/页尾都走 [alignPageStart] / [pageEndFor]，和工具输出外置的读回完全同一套逻辑——
 * 否则 `offset` 落在 emoji 中间时会切出半个代理项（JSON 里是孤立代理项，部分 provider 直接拒收）。
 *
 * 边界按 UTF-16 代理对对齐（与 [ToolOutputStore] 的读回共用同一套实现）：
 * - 起点落在代理对中间时向前挪一个 code unit（宁可重复 1 个 code unit 也不把 emoji 劈开）；
 * - 终点劈开代理对时向后补一个 code unit，所以 limit=1 命中 emoji 会返回 2 个 code unit，
 *   [SubagentResultPage.nextOffset] 仍然前进，按 nextOffset 循环读不会死循环。
 *
 * 因为分页总是按 nextOffset 串起来读，拼回去的结果与全文逐 code unit 相同，不重复也不遗漏。
 */
fun subagentResultPage(
    task: SubagentTask,
    full: String,
    offset: Int,
    limit: Int,
): SubagentResultPage {
    val safeLimit = if (limit <= 0) SUBAGENT_RESULT_DEFAULT_LIMIT else limit.coerceAtMost(SUBAGENT_RESULT_MAX_LIMIT)
    val requested = offset.coerceIn(0, full.length)
    val start = alignPageStart(full, requested)
    val end = pageEndFor(full, start, safeLimit)
    return SubagentResultPage(
        taskId = task.id,
        status = task.status,
        steps = task.steps,
        offset = start,
        limit = safeLimit,
        totalChars = full.length,
        text = full.substring(start, end),
        endOffset = end,
    )
}

/** [index] 是否落在代理对中间（即 index-1 是高代理、index 是低代理）。 */
private fun isMidSurrogatePair(text: String, index: Int): Boolean =
    index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()

/**
 * 按 UTF-16 数量截断，但不切断代理对：末尾若是孤立高代理就少拿一个 code unit。
 * 通知/preview 用它，避免把 emoji 截成半个字符。
 */
fun subagentTruncateUtf16(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (text.length <= limit) return text
    var end = limit
    if (isMidSurrogatePair(text, end)) end -= 1
    return text.substring(0, end)
}

/**
 * 一次 wait 里单个任务的首屏额度。
 *
 * 单任务（或只有 1 个 id）允许用满 [SUBAGENT_RESULT_MAX_LIMIT]；多任务按
 * [SUBAGENT_WAIT_PAGE_BUDGET] 均分，且不低于 [SUBAGENT_WAIT_MIN_PAGE]，
 * 这样 8 个任务一起 wait 也不会把 48K 级别的正文一次倒进上下文。
 */
fun subagentWaitPageLimit(userLimit: Int, taskCount: Int): Int {
    val requested = if (userLimit <= 0) {
        SUBAGENT_RESULT_DEFAULT_LIMIT
    } else {
        userLimit.coerceAtMost(SUBAGENT_RESULT_MAX_LIMIT)
    }
    if (taskCount <= 1) return requested
    val budgeted = (SUBAGENT_WAIT_PAGE_BUDGET / taskCount).coerceAtLeast(SUBAGENT_WAIT_MIN_PAGE)
    return minOf(requested, budgeted)
}


/**
 * 一次等待里解析出的任务列表：`task_ids`（最多 [SUBAGENT_MAX_WAIT_TASKS] 个）与旧的 `task_id` 都支持。
 *
 * 去重但保序，返回顺序就是结果顺序；空输入和超量都在发请求前直接报错。
 */
fun resolveWaitTaskIds(taskIds: Collection<String>?, taskId: String?): List<String> {
    val ids = buildList {
        taskIds?.forEach { add(it) }
        if (taskId != null) add(taskId)
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    require(ids.isNotEmpty()) { "task_id or task_ids is required" }
    require(ids.size <= SUBAGENT_MAX_WAIT_TASKS) {
        "task_ids supports at most $SUBAGENT_MAX_WAIT_TASKS tasks per call"
    }
    return ids
}

/**
 * 终态通知：同一 task 只发一次。
 *
 * [key] 用作会话收件箱的 [me.rerere.rikkahub.service.QueuedNotice.key]，
 * 同一 task 的重复通知在收件箱里原地替换而不是排队。
 */
data class SubagentNoticeEvent(
    val conversationId: Uuid,
    val key: String,
    val notice: UIMessagePart.Notice,
)

fun subagentNoticeKey(taskId: String): String = "subagent:$taskId"

/** 终态才算一次交付；用于"进入终态的那一刻发一次通知"的判定。 */
fun isSubagentTerminalTransition(old: SubagentTask, new: SubagentTask): Boolean =
    !old.isFinished && new.isFinished

/**
 * 一条完成通知。只带 taskId/status/steps/结果字符数/短 preview 和读取提示,
 * 不塞正文——正文由 subagent_result 分页读, 避免同一份内容在上下文里出现两遍。
 */
fun buildSubagentNotice(task: SubagentTask, result: String): UIMessagePart.Notice {
    val seconds = (((task.finishedAt ?: System.currentTimeMillis()) - (task.startedAt ?: task.createdAt)) / 1000)
        .coerceAtLeast(0)
    val preview = result.replace(Regex("\\s+"), " ").trim()
        .let { subagentTruncateUtf16(it, SUBAGENT_NOTICE_PREVIEW_CHARS).let { p -> if (p.length < it.length) "$p…" else p } }
    val detail = when {
        task.status == SubagentStatus.COMPLETED -> "finished"
        task.status == SubagentStatus.TIMEOUT -> "timed out"
        task.status == SubagentStatus.CANCELLED -> "was cancelled"
        else -> "failed"
    }
    val text = buildString {
        appendLine("<subagent_notice>")
        appendLine(
            "Subagent task `${task.id}` $detail: status ${task.status.name} after ${seconds}s, " +
                "${task.steps} step(s), ${result.length} result chars."
        )
        task.error?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val shown = subagentTruncateUtf16(it, SUBAGENT_NOTICE_PREVIEW_CHARS)
            appendLine("Error: ${if (shown.length < it.length) "$shown…" else shown}")
        }
        if (result.isBlank()) {
            appendLine("It produced no readable output; partial output may still be empty.")
        } else {
            appendLine("Preview: $preview")
        }
        appendLine(
            "Read it with subagent_result(task_id=\"${task.id}\") — ${SUBAGENT_RESULT_DEFAULT_LIMIT} chars per page " +
                "by default, use offset/limit (max $SUBAGENT_RESULT_MAX_LIMIT) for longer results. " +
                "Partial output of failed, timed-out or cancelled tasks is readable the same way."
        )
        append("</subagent_notice>")
    }
    val promptPreview = subagentTruncateUtf16(task.prompt.replace(Regex("\\s+"), " ").trim(), 60)
    return UIMessagePart.Notice(
        title = "Subagent ${task.status.name}: $promptPreview",
        text = text,
    )
}

/**
 * 结果交付记录，按 task scope 共享（runner 持有一个实例，所有工具实例看到同一份）。
 *
 * "已交付"只影响 subagent_wait 的返回内容（第二次只给 status + 读取提示），
 * 不影响 subagent_result —— 结果永远可以重新分页读取，不存在"读一次就没了"。
 *
 * 首屏交付权用 [claimDelivery] 原子抢：底层 [ConcurrentHashMap.newKeySet] 的 add 返回 Boolean，
 * 同一 task 并发 wait 时只有一次 add 返回 true，其余拿到 false 走 status 分支。
 */
class SubagentDeliveryRegistry {
    private val delivered = ConcurrentHashMap.newKeySet<String>()

    /** 原子 claim：true 表示"本次调用抢到了首屏"，false 表示别人已经交付过。 */
    fun claimDelivery(taskId: String): Boolean = delivered.add(taskId)

    fun markDelivered(taskId: String) {
        delivered.add(taskId)
    }

    fun isDelivered(taskId: String): Boolean = delivered.contains(taskId)

    fun forget(taskId: String) {
        delivered.remove(taskId)
    }
}

/**
 * 首屏交付协议：先 read（读文件/切页，可能抛异常或返回 null），读成功之后才原子 claim。
 *
 * 顺序不能反过来——先 claim 再读，一次读失败就把"已交付"占掉了，结果再也拿不到首屏，
 * 只能靠模型自己去 subagent_result 猜。读失败不消耗交付权，下一次 wait 还能拿到。
 *
 * @return 抢到首屏时返回 read 的结果；返回 null 表示"没抢到"（已交付过或读失败），调用方回 status。
 */
fun <T : Any> claimFirstScreenAfterRead(
    registry: SubagentDeliveryRegistry,
    taskId: String,
    read: () -> T?,
): T? {
    val value = read() ?: return null
    return if (registry.claimDelivery(taskId)) value else null
}

/**
 * `task.json` 的磁盘模型。
 *
 * 不直接把 [SubagentTask] 标成 `@Serializable`：它带 [Uuid] 和 [TokenUsage]，两者的序列化器跟着
 * 上游模块走，磁盘格式会随上游改动漂移；这里只存"重启后恢复所需的元数据 + 状态 + 归属"，
 * 正文永远留在 `result.md`（已有），用量/usage 之类的运行时数据不落盘。
 *
 * 归属（owner）就是 [conversationId]：重启后会话仍能凭它判断"这个任务是不是我的"。
 */
@Serializable
data class SubagentTaskRecord(
    val version: Int = 1,
    val id: String,
    val prompt: String = "",
    val conversationId: String? = null,
    val modelId: String? = null,
    val status: String = SubagentStatus.QUEUED.name,
    val createdAt: Long = 0L,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
    val steps: Int = 0,
    /** r22：尝试过的模型与换渠道轨迹；旧记录缺省为空，读取时按"一次尝试"理解。 */
    val attempts: Int = 0,
    val triedModelIds: List<String> = emptyList(),
    val switchNote: String? = null,
)

/** 重启遗留的未完成任务统一改成 CANCELLED，并留下可解释的 error。 */
const val SUBAGENT_RESTART_ERROR = "App restarted; not replayed"

private val subagentTaskRecordJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** task id 必须是普通 UUID（带连字符的小写/大写都行）；其它形式一律拒绝，避免拼出路径穿越。 */
private val subagentTaskIdPattern =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

fun isSafeSubagentTaskId(id: String): Boolean =
    id.length in 36..36 && subagentTaskIdPattern.matches(id)

fun encodeSubagentTaskRecord(record: SubagentTaskRecord): String =
    subagentTaskRecordJson.encodeToString(SubagentTaskRecord.serializer(), record)

fun decodeSubagentTaskRecord(text: String): SubagentTaskRecord? =
    runCatching { subagentTaskRecordJson.decodeFromString(SubagentTaskRecord.serializer(), text) }.getOrNull()

fun SubagentTask.toRecord(): SubagentTaskRecord = SubagentTaskRecord(
    id = id,
    prompt = prompt,
    conversationId = conversationId?.toString(),
    modelId = modelId?.toString(),
    status = status.name,
    createdAt = createdAt,
    startedAt = startedAt,
    finishedAt = finishedAt,
    error = error,
    steps = steps,
    attempts = attempts,
    triedModelIds = triedModelIds.map { it.toString() },
    switchNote = switchNote,
)

/**
 * 磁盘记录 → 内存任务。
 *
 * QUEUED/RUNNING 只可能来自上一次进程：启动时不会重放，所以标 CANCELLED 并附
 * [SUBAGENT_RESTART_ERROR]，partial 结果（result.md）照样能读。
 * 正文不在这里读，[result] 保持 null，由 runner 按需懒读单个文件。
 */
fun SubagentTaskRecord.toTask(
    resultPath: String? = null,
    logPath: String? = null,
    now: Long = System.currentTimeMillis(),
): SubagentTask {
    val parsedStatus = runCatching { SubagentStatus.valueOf(status) }.getOrDefault(SubagentStatus.FAILED)
    val interrupted = parsedStatus == SubagentStatus.QUEUED || parsedStatus == SubagentStatus.RUNNING
    return SubagentTask(
        id = id,
        prompt = prompt,
        modelId = modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        conversationId = conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        status = if (interrupted) SubagentStatus.CANCELLED else parsedStatus,
        createdAt = if (createdAt > 0L) createdAt else now,
        startedAt = startedAt,
        finishedAt = if (interrupted) now else finishedAt,
        result = null,
        error = when {
            interrupted && this.error.isNullOrBlank() -> SUBAGENT_RESTART_ERROR
            interrupted -> "${this.error} ($SUBAGENT_RESTART_ERROR)"
            else -> this.error
        },
        steps = steps,
        resultPath = resultPath,
        logPath = logPath,
        triedModelIds = triedModelIds.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() },
        attempts = attempts,
        switchNote = switchNote,
    )
}
