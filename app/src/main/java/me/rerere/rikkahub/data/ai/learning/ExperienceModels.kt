package me.rerere.rikkahub.data.ai.learning

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 一轮任务的终态，由调用方（ChatService 的 runTurn）判定，学习层只接受这两种。
 *
 * 取消（CancellationException）不是终态：[ExperienceTriggers] 不会把它交进来，
 * 所以「用户点停止」永远不会被当成一次成功的经验来源。
 */
enum class TurnOutcomeKind {
    /** 生成正常跑完（AgentFinishReason.Completed） */
    SUCCESS,

    /** 有结果但没正常跑完：触到步数上限、模型侧终止等；仍值得总结「哪里没走通」 */
    TERMINATED,
}

/** 一轮任务的可观察事实。全部是计数/枚举/受控短文本，不含整段对话。 */
data class TurnFacts(
    val assistantId: Uuid,
    val conversationId: Uuid,
    val messageId: Uuid,
    val modelId: Uuid,
    val outcome: TurnOutcomeKind,
    /** 本轮工具调用名（去重、已按上限截断），只留工具标识 */
    val toolNames: List<String> = emptyList(),
    /** 本轮失败的工具有哪些（同样是名字，不含参数） */
    val failedToolNames: List<String> = emptyList(),
    /** 用户这一次消息（可选）：是总结「任务目标」的唯一依据，不外传；为空则只依据工具轨迹 */
    val userRequest: String? = null,
    /** 助手最终回复（可选）：用来判断任务是否真的完成 */
    val assistantReply: String? = null,
    /** 本轮失败/终止原因的一句话摘要（由客户端归类，不是模型猜测） */
    val stopReason: String? = null,
)

/** 模型可写的一条经验，尚未落盘。 */
data class ExperienceCandidate(
    val text: String,
    /** 只有 WORKFLOW 类经验才允许进知识库；FACT 类只回记忆库 */
    val kind: ExperienceKind = ExperienceKind.WORKFLOW,
)

enum class ExperienceKind(val label: String) {
    /** 完成任务的做法：步骤顺序、工具选择、规避某类失败 */
    WORKFLOW("做法"),

    /** 关于用户/项目的稳定事实：只在用户明确说过时才有 */
    FACT("事实"),
}

/** 一条经验写到哪里。 */
enum class ExperienceSink(val label: String) {
    /** 助手记忆库的文件（`/lessons/<topic>.md`，一行一条） */
    MEMORY("记忆文件"),

    /** 本地知识库（每助手独立 scope 的文档） */
    KNOWLEDGE("本地知识库"),
}

/** 去重结论。 */
enum class ExperienceDedupe {
    /** 没写过的内容 */
    NEW,

    /** 记忆库里已经有这一行（或措辞几乎相同） */
    ALREADY_IN_MEMORY,

    /** 同一轮重复投递/重复触发，没有产生第二次写入 */
    DUPLICATE_EVENT,
}

/** 一次经验落盘的结果。失败一律是数据，不抛给调用方。 */
data class ExperienceWriteReport(
    val dedupe: ExperienceDedupe,
    val memoryPath: String? = null,
    /** 记忆写入被拒绝或被安全拦截时的原因（可见，不吞掉） */
    val memoryRejected: String? = null,
    val knowledgeDocId: String? = null,
    val knowledgeRejected: String? = null,
    val knowledgeError: String? = null,
) {
    val wroteAnything: Boolean
        get() = dedupe == ExperienceDedupe.NEW && (memoryPath != null || knowledgeDocId != null)
}

/** 一次「本轮总结」的完整结果。 */
data class ExperienceRunReport(
    val assistantId: String,
    val conversationId: String,
    val messageId: String,
    val outcome: TurnOutcomeKind,
    /** SKIPPED 时说明为什么没做（开关、没模型、已处理过……），可见不静默 */
    val skipped: String? = null,
    val candidates: List<ExperienceCandidate> = emptyList(),
    val write: ExperienceWriteReport? = null,
    /** 模型请求本身失败（网络/额度/解析失败）时的原因 */
    val error: String? = null,
    val at: Long = 0L,
) {
    val attempted: Boolean get() = skipped == null && (candidates.isNotEmpty() || error != null)
}

/**
 * 学习相关的可配置开关。
 *
 * 默认全部关闭：多花一次模型请求、往记忆库和知识库写东西，必须由用户显式打开。
 * [reflectionEnabled] 是「每轮让模型总结经验」；
 * [writeToKnowledge] 决定经验是否同时进本地知识库（默认只进记忆库）。
 */
data class ExperienceSettings(
    val reflectionEnabled: Boolean = false,
    val writeToKnowledge: Boolean = true,
    val writeToMemory: Boolean = true,
)

/** 落盘的经验文件格式（每助手一个 JSON）。 */
@Serializable
data class ExperienceFile(
    val version: Int = LearningExperienceLimits.SCHEMA_VERSION,
    val assistantId: String = "",
    val revision: Long = 0L,
    val updatedAt: Long = 0L,
    /** 已经处理过的轮次 key（conversation|message），用于防重入/重复写 */
    val handled: List<String> = emptyList(),
    /** 已写入记忆库的经验文本指纹（去重用；只有哈希，不是原文副本） */
    val memoryFingerprints: List<String> = emptyList(),
    /** 已写入知识库的经验文本指纹 */
    val knowledgeFingerprints: List<String> = emptyList(),
    /** 最近若干次运行结果，用于设置页显示「上次总结做了什么/为什么失败」 */
    val recent: List<ExperienceRunRecord> = emptyList(),
)

/** 可读的运行记录（落盘）。只留结论与原因，不留正文。 */
@Serializable
data class ExperienceRunRecord(
    val at: Long = 0L,
    val conversationId: String = "",
    val messageId: String = "",
    val outcome: String = "",
    val skipped: String? = null,
    val error: String? = null,
    val wrote: Boolean = false,
    val memoryPath: String? = null,
    val knowledgeDocId: String? = null,
    val rejected: String? = null,
)

/** 经验学习的硬上限。 */
object LearningExperienceLimits {
    const val SCHEMA_VERSION: Int = 1

    /** 一个助手最多记住多少个已处理的轮次 key（FIFO） */
    const val MAX_HANDLED: Int = 400

    /** 去重指纹最多保存多少条（FIFO） */
    const val MAX_FINGERPRINTS: Int = 400

    /** 保留多少条运行记录给界面看 */
    const val MAX_RUN_RECORDS: Int = 20

    /** 一次总结最多产出几条经验 */
    const val MAX_CANDIDATES: Int = 2

    /** 一条经验的最大长度（进提示词与落盘的共用上限） */
    const val MAX_LINE_CHARS: Int = 200

    /** 交给模型的本轮事实最多多长（用户请求 + 回复） */
    const val MAX_FACT_CHARS: Int = 2400

    /** 最多带多少个工具名 */
    const val MAX_TOOL_NAMES: Int = 12

    /** 总结请求的超时（毫秒）；超时就算这次不总结，绝不拖着主流程 */
    const val TIMEOUT_MS: Long = 45_000L
}
