package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.ai.agent.GenerationSkipReason
import me.rerere.ai.agent.GenerationTiming
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.theme.chatPanelStrong
import java.util.Locale

/** 折叠摘要行里的速度文本标记；详细面板仍用 [ChatSpeedValueTag]，两者不会撞 tag。 */
internal const val ChatHeaderSpeedTag = "chat-header-speed"

/** 详细面板（展开态）里的速度数值标记。 */
internal const val ChatSpeedValueTag = "chat-speed-value"

/** 覆盖范围文本（已测 N/M 步）的标记，顶部详情与消息详情共用同一份数据。 */
internal const val ChatSpeedCoverageTag = "chat-speed-coverage"

/** 「为什么没有速度」文本的标记。 */
internal const val ChatSpeedReasonTag = "chat-speed-reason"

/** 没有可测量速度时的占位符，摘要行与详细面板共用，避免两处写法漂移。 */
internal const val ChatSpeedPlaceholder = "—"

/**
 * 一条回复的速度展示口径：顶部摘要、展开详情、消息底部统计全部从这份数据取值。
 *
 * [speed] 是「已测步骤的 token / 这些步骤的生成窗口」的加权值；部分步骤测不准时它依然有值，
 * 覆盖范围由 [measuredSteps] / [totalSteps] 说明，[reasonCode] 说明没测到的原因。
 */
internal data class ChatSpeedUi(
    val speed: Double?,
    val measuredSteps: Int,
    val totalSteps: Int,
    val reasonCode: String? = null,
    /** 没测到的步骤按原因分组，按原因码排序，UI 详情直接用。 */
    val skippedReasons: List<Pair<String, Int>> = emptyList(),
) {
    /** 主数值文本；速度缺失时是占位符，不会伪造数值。 */
    val valueText: String
        get() = speedText(speed)

    /** 有速度但覆盖不全：数值可信范围有限，界面上要标出步数。 */
    val partial: Boolean
        get() = measuredSteps > 0 && totalSteps > measuredSteps

    /** 只要不是全部步骤都测到就要显示覆盖范围（包括一步都没测到的情形）。 */
    val hasCoverage: Boolean
        get() = totalSteps > 0 && measuredSteps < totalSteps
}

/** 速度数值的唯一格式化入口：摘要行与详细面板都走这里，不会出现 “52.0 tok/s” 与 “52.0 t/s” 两种写法。 */
internal fun speedText(speed: Double?): String =
    if (speed == null) ChatSpeedPlaceholder else String.format(Locale.US, "%.1f tok/s", speed)

/** 历史入口，保持与旧调用点/测试兼容。 */
internal fun conversationSpeedText(speed: Double?): String = speedText(speed)

/**
 * 把 [GenerationTiming] 翻成展示口径。
 *
 * - [finished] 为 false 时正在流式生成，数值先藏起来（占位符），也不给原因，避免“生成中”被说成“测不到”。
 * - 老记录没有 `measuredOutputTokens` 字段时由 [GenerationTiming.measuredTokens] 决定能不能回退，不猜数。
 */
internal fun GenerationTiming?.chatSpeedUi(finished: Boolean = true): ChatSpeedUi {
    if (!finished) {
        return ChatSpeedUi(speed = null, measuredSteps = 0, totalSteps = 0, reasonCode = null)
    }
    if (this == null) {
        return ChatSpeedUi(speed = null, measuredSteps = 0, totalSteps = 0, reasonCode = GenerationSkipReason.LEGACY_UNKNOWN)
    }
    val speed = speedTokensPerSecond
    val skipped = skippedReasons.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key to it.value }
    return ChatSpeedUi(
        speed = speed,
        measuredSteps = measuredSteps,
        totalSteps = totalSteps,
        reasonCode = if (speed == null) skipReason() else null,
        skippedReasons = skipped,
    )
}

/** 一条消息的速度都没测到时，说清最主要的原因。 */
private fun GenerationTiming.skipReason(): String {
    skippedReasons.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()
        ?.let { return it.key }
    return when {
        totalSteps <= 0 -> GenerationSkipReason.LEGACY_UNKNOWN
        measuredOutputTokens == 0L && measuredSteps in 1 until totalSteps -> GenerationSkipReason.LEGACY_UNKNOWN
        measuredTokens <= 0L -> GenerationSkipReason.NO_OUTPUT_TOKENS
        // 有分子但没有可信窗口：窗口总量不足 200ms 的短突发
        else -> GenerationSkipReason.SHORT_WINDOW
    }
}

/** 原因码 → 文案资源；未知码有兜底文案，不显示裸码。 */
internal fun chatSpeedReasonRes(code: String?): Int = when (code) {
    GenerationSkipReason.NON_STREAM -> R.string.r10_speed_reason_non_stream
    GenerationSkipReason.NO_OUTPUT_TOKENS -> R.string.r10_speed_reason_no_tokens
    GenerationSkipReason.SINGLE_PACKET -> R.string.r10_speed_reason_single_packet
    GenerationSkipReason.SHORT_WINDOW -> R.string.r10_speed_reason_short_window
    GenerationSkipReason.SERVER_WAIT -> R.string.r10_speed_reason_server_wait
    GenerationSkipReason.LEGACY_UNKNOWN -> R.string.r10_speed_reason_legacy
    else -> R.string.r10_speed_reason_unknown
}

/** 当前会话最后一条助手消息的速度口径。 */
internal fun conversationSpeedUi(conversation: Conversation): ChatSpeedUi {
    val message = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
    // In-flight timing is a preview, not a completed measurement; match the message footer.
    return message?.generationTiming.chatSpeedUi(finished = message?.finishedAt != null)
}

internal fun conversationSpeed(conversation: Conversation): Double? = conversationSpeedUi(conversation).speed

/** 覆盖范围文案，例如「已测 65/72 步」；全部测到或一步都没测到时为 null。 */
@Composable
internal fun chatSpeedCoverageText(ui: ChatSpeedUi): String? =
    if (ui.hasCoverage) stringResource(R.string.r10_speed_coverage, ui.measuredSteps, ui.totalSteps) else null

/** 行内用的短覆盖文案，例如「65/72 步」。 */
@Composable
internal fun chatSpeedCoverageShortText(ui: ChatSpeedUi): String? =
    if (ui.hasCoverage) stringResource(R.string.r10_speed_coverage_short, ui.measuredSteps, ui.totalSteps) else null

/** 没有速度时的可读原因；有速度时为 null。 */
@Composable
internal fun chatSpeedReasonText(ui: ChatSpeedUi): String? =
    if (ui.speed == null && ui.reasonCode != null) stringResource(chatSpeedReasonRes(ui.reasonCode)) else null

/**
 * Visible in every layout, independent from the context dashboard switch.
 *
 * 与消息底部统计同一份 [ChatSpeedUi]：数值、覆盖步数、原因都来自同一处计算。
 */
@Composable
internal fun ChatSpeedStatus(conversation: Conversation) {
    val ui = conversationSpeedUi(conversation)
    val unavailable = stringResource(R.string.chat_speed_unavailable)
    val coverage = chatSpeedCoverageText(ui)
    val reason = chatSpeedReasonText(ui)
    Surface(
        color = chatPanelStrong(MaterialTheme.colorScheme.surfaceContainerLow),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.chat_speed_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    ui.valueText,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .testTag(ChatSpeedValueTag)
                        .semantics { if (ui.speed == null) contentDescription = reason ?: unavailable },
                )
            }
            if (coverage != null) {
                Text(
                    coverage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ChatSpeedCoverageTag),
                )
            }
            if (reason != null) {
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ChatSpeedReasonTag),
                )
            }
        }
    }
}
