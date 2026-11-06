package me.rerere.rikkahub.data.ai.inputassist

import kotlin.uuid.Uuid

/**
 * 这一轮「用户消息优化」到底发生了什么。
 *
 * 只要不是 [OPTIMIZED]/[CACHED]，发出去的就是用户自己写的那段文字——优化是可选增强，
 * 任何一步失败都退回原话继续，不能把用户的消息卡在优化这一步上。
 */
enum class InputOptimizationStatus {
    /** 用户没开这个功能：这一轮一个额外请求都没有 */
    NOT_REQUESTED,

    /** 命中私有缓存（同一会话 + 同一 messageId + 同一原文 + 同一助手），没有再花一次钱 */
    CACHED,

    /** 这次真的请求了快速模型 */
    OPTIMIZED,

    /** 模型认为原话已经足够明确，保持原话 */
    UNCHANGED,

    /** 原文不适合优化（太短、太长、只有代码/附件、模型不可用等） */
    NOT_ELIGIBLE,

    /** 超时、报错或结果没过守卫：一律用原话继续 */
    FAILED,
}

/** 一次优化的结果。[optimizedText] 只有在真的可以替换请求时才非空。 */
data class InputOptimizationOutcome(
    val status: InputOptimizationStatus,
    val optimizedText: String? = null,
    val modelName: String? = null,
    val failureReason: String? = null,
    /**
     * 这一次结论来自缓存（同一会话 + 同一 messageId + 同一原文 + 同一助手），**没有再发请求**。
     *
     * 它的作用是别把"上次就失败过"说成"这次又失败了一次"：缓存命中的失败/无改动仍然是
     * [InputOptimizationStatus.FAILED]/[InputOptimizationStatus.UNCHANGED]，不会被说成成功。
     */
    val cached: Boolean = false,
)

/**
 * 一轮对话的输入辅助状态：钉在「这一条用户消息」上。
 *
 * [messageId] 是会话里那条**原话**消息的 id（落库 id，不是请求里的临时对象）；
 * 请求改写只按它定位，绝不按"最后一条用户消息"猜，避免重连/重新生成时改错消息。
 */
data class InputAssistTurnState(
    val conversationId: Uuid,
    val messageId: Uuid,
    val assistantId: Uuid,
    val optimization: InputOptimizationOutcome = InputOptimizationOutcome(InputOptimizationStatus.NOT_REQUESTED),
    /** 本轮是否在请求尾部追加同模型自检提示（SELF_CHECK，不额外调用） */
    val selfCheck: Boolean = false,
    /**
     * 定这一轮时那条原话的指纹。请求改写前会再算一次：消息在期间被改过（编辑、撤销、并发写入）
     * 就不再替换，避免把旧优化版套到新文本上。null 表示不做这层校验（老调用方/测试）。
     */
    val originalHash: String? = null,
) {
    /** 真正要替换进请求的优化版文本；不含缓存命中以外的任何东西 */
    val optimizedText: String?
        get() = optimization.optimizedText?.takeIf {
            it.isNotBlank() &&
                (optimization.status == InputOptimizationStatus.OPTIMIZED ||
                    optimization.status == InputOptimizationStatus.CACHED)
        }

    /** 这一轮发出去的请求和库里存的原话是否不同 */
    val changesRequest: Boolean
        get() = optimizedText != null || selfCheck
}

/** 审查对「助手是否真的满足了用户这条要求」的结论。 */
enum class InputReviewVerdict { OK, CONCERN, RISK, UNKNOWN }

/**
 * 独立审查结果。
 *
 * 它只是**文本意见**：审查模型不执行工具、不重放任务、不改写助手回答，也不代表真的编译/运行过。
 */
data class InputAssistReview(
    val conversationId: Uuid,
    val userMessageId: Uuid?,
    val replyMessageId: Uuid?,
    val verdict: InputReviewVerdict,
    val findings: List<String>,
    val suggestion: String?,
    val limits: String,
    val modelName: String?,
    /** 命中本地审查缓存时为 true（没有再扣一次费） */
    val cached: Boolean = false,
    /** false 表示这是同模型自查的结果，不能当成独立验证 */
    val independent: Boolean = true,
    val createdAtMillis: Long = 0L,
)

/** 审查在界面上的生命周期；toggle 关掉时 [Running] 必须能被取消回 [Idle]。 */
sealed interface InputAssistReviewState {
    data object Idle : InputAssistReviewState
    data class Running(val replyMessageId: Uuid?) : InputAssistReviewState
    data class Done(val review: InputAssistReview) : InputAssistReviewState
    data class Failed(val reason: String?) : InputAssistReviewState
}

/** 界面上必须明说的事实，不允许换成更漂亮的说法。 */
object InputAssistNotice {
    const val INDEPENDENT_DISCLAIMER =
        "独立审查只读取这条对话的文本，不执行工具、不重放任务；无法替代真实编译或运行测试。"
    const val SELF_CHECK_DISCLAIMER =
        "自查由当轮同一个模型顺带完成，没有额外请求，也不是独立验证。"
    const val EXTRA_COST =
        "优化和独立审查都是额外的模型请求，用量记录里分别记为 INPUT_OPTIMIZATION / REVIEW，单独计费。"

    /**
     * 取消不是退款。
     *
     * 取消只停止"等结果"和界面上的转圈；请求一旦发出去就已经产生费用，撤回不了。
     */
    const val CANCEL_COST =
        "取消只停止等待和显示；已经发出的请求无法撤回，这次请求的费用也不会退回。"
}
