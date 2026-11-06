package me.rerere.rikkahub.data.organization.autofile

import kotlin.uuid.Uuid

/**
 * 手动整理 / 后台任务的**身份**与**外发同意**策略。
 *
 * 全是纯函数，没有 Android 依赖：WorkManager 那边只负责把结果变成真实任务，
 * 是否要征得同意、用哪个唯一名字、能不能算「已排上」都在这里判断，方便单测。
 */
object AutoFileManualPolicy {
    /** 周期任务的名字前缀（每助手一条，见 ConversationAutoFileWork）。 */
    const val PERIODIC_PREFIX = "conversation-autofile-"

    /**
     * 手动一次性任务的名字前缀。
     *
     * 故意和周期任务**分开**：手动触发是用户显式动作，不能被「自动开关」的周期约束
     * （联网/12h 周期）挡住；但两者在同一助手的批次锁里互斥，不会同时搬库。
     */
    const val MANUAL_PREFIX = "conversation-autofile-manual-"

    fun periodicWorkName(assistantId: Uuid): String = "$PERIODIC_PREFIX$assistantId"

    fun manualWorkName(assistantId: Uuid): String = "$MANUAL_PREFIX$assistantId"

    /**
     * 手动触发前是否必须确认「会把限长摘要发给快速模型、花额度」。
     *
     * 只有 **AI 模式 + 自动开关是关的** 才需要：开关开着说明用户已经在面板上确认过一次；
     * 本地规则模式完全不发网，不需要多这一层。
     */
    fun needsRunConfirmation(settings: ConversationAutoFileSettings): Boolean =
        settings.mode == AutoFileMode.AI && !settings.enabled

    /**
     * 把模式改成 [target] 并保存之前是否要**再确认一次**。
     *
     * 自动归档已经开着（[ConversationAutoFileSettings.enabled]）时，从本地模式切到 AI 模式会让
     * 之后每一轮后台批次都开始外发摘要，这是一次新的外发决定；面板上直接保存等于绕过确认，
     * 所以必须先弹一次同意。
     */
    fun modeSwitchNeedsConfirmation(
        current: ConversationAutoFileSettings,
        target: AutoFileMode,
    ): Boolean = target == AutoFileMode.AI && current.enabled && current.mode != AutoFileMode.AI

    /**
     * 手动任务的返回口径：`enqueued=false` 表示这次**没有**新入队（同名任务还在队列/运行中，
     * KEEP 生效），调用方必须照实说「已在队列里」，不能回一个新 uuid 假装排上了。
     */
    fun manualWork(
        assistantId: Uuid,
        workId: String?,
        state: String,
        enqueued: Boolean,
    ): AutoFileManualWork = AutoFileManualWork(
        workName = manualWorkName(assistantId),
        workId = workId,
        state = state,
        enqueued = enqueued,
    )
}
