package me.rerere.rikkahub.data.ai.learning

import kotlinx.serialization.Serializable

/**
 * 一轮生成的运行指标。
 *
 * 这里**只有计数和 id**：不存对话正文、工具参数、工具返回值、模型输出，也不存任何凭据。
 * 写入时会做截断和清洗（见 [LearningAnalyzer.sanitizeTurn]），所以即使调用方误传了正文，
 * 落盘的也只是一小段被截断的字符串，不会成为一份聊天记录副本。
 *
 * [at] 为 0 表示「写入时刻」。
 */
@Serializable
data class LearningTurn(
    val assistantId: String = "",
    val conversationId: String? = null,
    /** 助手消息 id。同一个 id 再次记录只更新指标；重新生成由用户动作单独标记。 */
    val messageId: String? = null,
    val modelId: String = "",
    val at: Long = 0L,
    val steps: Int = 0,
    val toolCalls: Int = 0,
    val toolErrors: Int = 0,
    val retries: Int = 0,
    val completed: Boolean = false,
    val stepLimitReached: Boolean = false,
    /** 用户明确点击重新生成的累计次数（不是重连/重复写回次数）。 */
    val regenerations: Int = 0,
    val firstContentMs: Long? = null,
    val requestMs: Long? = null,
)

/** 经验来源。只允许这两种：固定模板 + 实际统计，或用户自己确认的条目。 */
enum class GuidanceKind(val label: String) {
    /** 固定模板 + 多轮重复证据生成，不能自由发挥 */
    AUTO("自动统计"),

    /** 用户在设置页新增/确认的条目 */
    USER("用户确认"),
}

/**
 * 一条可注入的经验提示。
 *
 * 自动经验（[GuidanceKind.AUTO]）由 [LearningAnalyzer] 按固定模板从指标里算出来，不落盘；
 * 用户经验（[GuidanceKind.USER]）落盘在每助手 JSON 文件里。两者都不会改动权限、模型或系统提示。
 */
data class LearningGuidance(
    val id: String,
    val text: String,
    val kind: GuidanceKind,
    val enabled: Boolean = true,
    /** 支撑这条经验的重复轮数；用户经验为 0（它靠用户确认，不靠统计）。 */
    val evidenceTurns: Int = 0,
    val updatedAt: Long = 0L,
)

/**
 * 计数型汇总。
 *
 * 只有能直接数出来的量：轮数、工具调用/失败、重试、触顶、被重新生成的轮数。
 * 没有「质量分」「提升幅度」这种字段——回答好不好没有现成真值可比，这里也不假装能测。
 */
data class LearningStats(
    val turns: Int = 0,
    val completedTurns: Int = 0,
    val toolCalls: Int = 0,
    val toolErrors: Int = 0,
    val retries: Int = 0,
    val stepLimitTurns: Int = 0,
    val regeneratedTurns: Int = 0,
) {
    /** 描述性比例（工具失败次数 / 工具调用次数），不是质量评分。 */
    val toolErrorRate: Double
        get() = if (toolCalls <= 0) 0.0 else toolErrors.toDouble() / toolCalls

    val completionRate: Double
        get() = if (turns <= 0) 0.0 else completedTurns.toDouble() / turns
}

/** 磁盘状态，给设置页显示「存到哪、有没有坏过」。 */
data class LearningStorageInfo(
    val path: String = "",
    val present: Boolean = false,
    /** JSON 损坏、已备份原文件时，备份文件的绝对路径；正常情况下为 null。 */
    val corruptBackup: String? = null,
)

/**
 * 某个助手经验库的一次快照。
 *
 * 快照是**一次性**的：UI 应持有当前这一份，不要按助手缓存一堆快照，
 * 否则等于把所有助手的完整历史常驻内存（[turns] 最多 [LearningLimits.MAX_TURNS] 轮）。
 */
data class LearningState(
    val assistantId: String,
    val enabled: Boolean,
    val revision: Long,
    val updatedAt: Long,
    val turns: List<LearningTurn>,
    /** 按助手整体指标算出来的自动经验（未按模型过滤），用于设置页展示 */
    val autoGuidances: List<LearningGuidance>,
    val userGuidances: List<LearningGuidance>,
    val stats: LearningStats,
    val storage: LearningStorageInfo,
    val disabledAutoGuidanceIds: Set<String> = emptySet(),
) {
    /** 全部经验（用户 + 自动），总数不超过 [LearningLimits.MAX_GUIDANCE] 条 */
    val guidances: List<LearningGuidance>
        get() = (userGuidances + autoGuidances).take(LearningLimits.MAX_GUIDANCE)

    /** 这次注入实际会用的条目（≤ [LearningLimits.MAX_PROMPT_RULES]，关闭时为空） */
    fun injectable(modelId: String? = null): List<LearningGuidance> =
        LearningPromptBuilder.selectGuidances(this, modelId)

    /** 等价于 [LearningPromptBuilder.buildPrompt]；没有可用经验时返回空串。 */
    fun prompt(modelId: String? = null): String = LearningPromptBuilder.buildPrompt(this, modelId)
}

/** [AssistantLearningRepository.clear] 的范围。 */
enum class LearningClearScope {
    /** 只清指标 */
    TURNS,

    /** 只清用户经验 */
    GUIDANCE,

    /** 指标 + 用户经验都清（开关状态保留） */
    ALL,
}

/** 写盘失败。文件不会被半截覆盖，内存状态保持落盘前的样子。 */
class LearningStorageException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** 用户经验条数达到上限。调用方应提示用户先删一条。 */
class LearningLimitException(message: String) : IllegalStateException(message)
