package me.rerere.rikkahub.ui.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.companion.CompanionAiPhase
import me.rerere.rikkahub.data.companion.CompanionAiState
import me.rerere.rikkahub.data.companion.CompanionCancelReason
import me.rerere.rikkahub.data.companion.CompanionEventKind
import me.rerere.rikkahub.data.companion.CompanionFailure
import me.rerere.rikkahub.data.companion.CompanionInteraction
import me.rerere.rikkahub.data.companion.CompanionLimits
import me.rerere.rikkahub.data.companion.CompanionSkipReason
import me.rerere.rikkahub.data.companion.CompanionTrigger
import me.rerere.rikkahub.data.companion.CompanionTriggerKind
import me.rerere.rikkahub.data.datastore.CompanionModelMode

/** 状态卡的测试标签，仪器测试按这些定位。 */
object CompanionStatusTestTags {
    const val CARD = "companion-status"
    const val TOGGLE = "companion-status-toggle"
    const val MODEL = "companion-status-model"
    const val PHASE = "companion-status-phase"
    const val ERROR = "companion-status-error"
    const val SKIP = "companion-status-skip"
    const val REFRESH = "companion-status-refresh"
    const val RETRY = "companion-status-retry"
    const val STOP = "companion-status-stop"
    const val OPEN_SETTINGS = "companion-status-settings"
    const val RECORDS = "companion-status-records"
    const val INPUT = "companion-status-input"
    const val SEND = "companion-status-send"
    const val ENABLE = "companion-status-enable"

    /** 发送被拒（正忙/冷却/窗口不可见）时那句"没发出去，原话还在"。 */
    const val SEND_KEPT = "companion-status-send-kept"

    /** 收起状态下那 1~2 行真实回复预览（展开后同一句在详情里，不再重复）。 */
    const val REPLY = "companion-status-reply"
}

/**
 * 小助手的真实状态卡：模型、阶段、最近触发、错误、最近互动记录、刷新和停止入口。
 *
 * 所有字段都直接读 [CompanionAiState] / [interactions]（控制器写进去的真实状态），
 * 卡里没有任何定时器或动画推出来的"假进度"：模型没配就写没配，失败了写失败原因。
 * 收起时是标题那一行加上这一轮真实回复的最后 1~2 行，展开后能看到全部细节
 * （同一句话不再重复），不影响面板里的审批和回复栏。
 */
@Composable
fun CompanionAiStatusCard(
    state: CompanionAiState,
    interactions: List<CompanionInteraction>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    /**
     * 面板里说一句。返回 true 表示控制器真的收下了这次输入（草稿可以清空），
     * 返回 false 表示被策略挡下（正忙/冷却/窗口不可见/AI 没开），**必须保留用户原话**。
     */
    onSend: (String) -> Boolean,
    onOpenSettings: () -> Unit,
    onEnableAi: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 卡里的输入框获得/失去焦点：面板据此把高度收一档，键盘不会盖住输入栏和停止键。 */
    onEditingChange: ((Boolean) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().testTag(CompanionStatusTestTags.CARD),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onExpandedChange(!expanded) }
                    .heightIn(min = 48.dp)
                    .testTag(CompanionStatusTestTags.TOGGLE),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (state.phase == CompanionAiPhase.FAILED) colors.errorContainer else colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = state.phaseIcon(),
                        contentDescription = null,
                        tint = if (state.phase == CompanionAiPhase.FAILED) colors.onErrorContainer else colors.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.companion_status_title) + " · " + phaseLabel(state.phase),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(CompanionStatusTestTags.PHASE),
                    )
                    Text(
                        text = modelLabel(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(CompanionStatusTestTags.MODEL),
                    )
                }
                if (state.busy) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier.size(40.dp).testTag(CompanionStatusTestTags.STOP),
                    ) {
                        Icon(HugeIcons.Stop, contentDescription = stringResource(R.string.companion_status_stop))
                    }
                }
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = stringResource(
                        if (expanded) R.string.companion_status_collapse else R.string.companion_status_expand
                    ),
                    modifier = Modifier.size(20.dp),
                )
            }

            state.failure?.let { failure ->
                Text(
                    text = companionFailureLabel(failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag(CompanionStatusTestTags.ERROR),
                )
            }
            state.skipped?.let { skip ->
                Text(
                    text = stringResource(R.string.companion_status_skipped, companionSkipLabel(skip.reason)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).testTag(CompanionStatusTestTags.SKIP),
                )
            }
            // 点击即可授权：不用绕去设置页，点一下就把"跟随当前会话"打开
            if (state.model.failure == CompanionFailure.AiDisabled && onEnableAi != null) {
                TextButton(
                    onClick = onEnableAi,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(CompanionStatusTestTags.ENABLE),
                ) {
                    Text(stringResource(R.string.companion_status_enable))
                }
            }

            // 收起时也要看得见"它到底说了什么"：只放最后这句真实回复（最多 2 行）。
            // 展开后同一句话在下面的详情里，这里就不再重复一遍。
            if (!expanded && state.text.isNotBlank()) {
                Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .testTag(CompanionStatusTestTags.REPLY),
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DetailRow(
                        label = stringResource(R.string.companion_status_model_title),
                        value = modelLabel(state) + " · " + sourceLabel(state.model.source),
                    )
                    DetailRow(
                        label = stringResource(R.string.companion_status_last_trigger),
                        value = state.trigger?.let { triggerLabel(it) + " · " + timeLabel(state.startedAtMillis ?: 0L) }
                            ?: stringResource(R.string.companion_status_none),
                    )
                    if (state.thinking.isNotBlank() && state.text.isBlank()) {
                        Text(
                            text = state.thinking.take(240),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (state.text.isNotBlank()) {
                        Text(
                            text = state.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.model.source == CompanionModelMode.FOLLOW_CONVERSATION) {
                        Text(
                            text = stringResource(R.string.companion_status_privacy),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    state.cancelReason?.let { reason ->
                        Text(
                            text = stringResource(R.string.companion_status_cancel_reason, cancelLabel(reason)),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onRefresh,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(CompanionStatusTestTags.REFRESH),
                        ) {
                            Icon(HugeIcons.Refresh01, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.companion_status_refresh),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(CompanionStatusTestTags.RETRY),
                        ) {
                            Icon(HugeIcons.ArrowUp02, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.companion_status_retry),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        if (!state.model.available) {
                            TextButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.heightIn(min = 48.dp).testTag(CompanionStatusTestTags.OPEN_SETTINGS),
                            ) {
                                Text(stringResource(R.string.companion_status_open_settings))
                            }
                        }
                    }

                    CompanionComposer(
                        generating = state.busy,
                        onSend = onSend,
                        onEditingChange = onEditingChange,
                    )

                    Text(
                        text = stringResource(R.string.companion_status_recent),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (interactions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.companion_status_no_records),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            modifier = Modifier.testTag(CompanionStatusTestTags.RECORDS),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            interactions.take(8).forEach { interaction ->
                                InteractionRow(interaction)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionComposer(
    generating: Boolean,
    onSend: (String) -> Boolean,
    onEditingChange: ((Boolean) -> Unit)?,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    // 上一次发送被挡下的提示：草稿还在，用户能看到"为什么没发出去"，而不是以为话丢了
    var rejected by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    fun send() {
        // 生成中按键盘发送键是常见操作：不发、也不动草稿
        if (generating) return
        val text = draft.trim()
        if (text.isEmpty()) return
        if (onSend(text)) {
            draft = ""
            rejected = false
        } else {
            rejected = true
        }
    }
    val focusModifier = if (onEditingChange != null) {
        Modifier.onFocusChanged { onEditingChange(it.isFocused) }
    } else {
        Modifier
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it.take(CompanionLimits.MAX_INPUT_CHARS)
                    if (it.isNotBlank()) rejected = false
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(CompanionStatusTestTags.INPUT)
                    .then(focusModifier),
                placeholder = { Text(stringResource(R.string.companion_status_input_hint)) },
                maxLines = 2,
                shape = RoundedCornerShape(20.dp),
                // 键盘上的发送键和按钮走同一个判断：正忙时什么都不做，草稿留着
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            FilledIconButton(
                onClick = ::send,
                enabled = draft.isNotBlank() && !generating,
                modifier = Modifier.size(44.dp).testTag(CompanionStatusTestTags.SEND),
            ) {
                Icon(HugeIcons.ArrowUp02, contentDescription = stringResource(R.string.companion_status_send))
            }
        }
        if (rejected) {
            Text(
                text = stringResource(R.string.companion_status_send_kept),
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag(CompanionStatusTestTags.SEND_KEPT),
            )
        }
    }
}

@Composable
private fun InteractionRow(interaction: CompanionInteraction) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = timeLabel(interaction.atMillis) + " · " + triggerLabel(interaction.trigger) + " · " +
                phaseLabel(interaction.phase),
            style = MaterialTheme.typography.labelSmall,
            color = when (interaction.phase) {
                CompanionAiPhase.FAILED -> colors.error
                CompanionAiPhase.CANCELLED -> colors.onSurfaceVariant
                else -> colors.primary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val body = interaction.failure?.let { companionFailureLabel(it) } ?: interaction.text
        if (body.isNotBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun CompanionAiState.phaseIcon(): ImageVector = when (phase) {
    CompanionAiPhase.FAILED -> HugeIcons.Alert01
    CompanionAiPhase.CANCELLED -> HugeIcons.Cancel01
    CompanionAiPhase.COMPLETED -> HugeIcons.Tick01
    else -> HugeIcons.Sparkles
}

@Composable
private fun phaseLabel(phase: CompanionAiPhase): String = stringResource(
    when (phase) {
        CompanionAiPhase.IDLE -> R.string.companion_status_phase_idle
        CompanionAiPhase.THINKING -> R.string.companion_status_phase_thinking
        CompanionAiPhase.GENERATING -> R.string.companion_status_phase_generating
        CompanionAiPhase.COMPLETED -> R.string.companion_status_phase_completed
        CompanionAiPhase.FAILED -> R.string.companion_status_phase_failed
        CompanionAiPhase.CANCELLED -> R.string.companion_status_phase_cancelled
    }
)

@Composable
private fun sourceLabel(source: CompanionModelMode?): String = stringResource(
    when (source) {
        CompanionModelMode.FOLLOW_CONVERSATION -> R.string.companion_status_source_follow
        CompanionModelMode.FIXED -> R.string.companion_status_source_fixed
        CompanionModelMode.OFF, null -> R.string.companion_status_source_off
    }
)

@Composable
private fun modelLabel(state: CompanionAiState): String =
    state.model.label ?: when (state.model.failure) {
        is CompanionFailure.AiDisabled -> stringResource(R.string.companion_status_ai_off)
        is CompanionFailure.ModelMissing -> stringResource(R.string.companion_status_model_missing)
        else -> stringResource(R.string.companion_status_model_unknown)
    }

@Composable
internal fun companionFailureLabel(failure: CompanionFailure): String = when (failure) {
    is CompanionFailure.AiDisabled -> stringResource(R.string.companion_status_ai_off)
    is CompanionFailure.ModelMissing -> stringResource(R.string.companion_status_model_missing)
    is CompanionFailure.ProviderMissing -> stringResource(
        R.string.companion_status_provider_missing,
        failure.modelLabel,
    )

    // provider 的原文常常带着响应体、URL 或上游调试串：这段文字浮在别的应用之上，
    // 也会进互动记录，所以先脱敏截断再显示，不整段照抄
    is CompanionFailure.Request -> sanitizeCompanionFailure(failure.message)
}

/** 失败原因给界面看之前的脱敏：去掉常见密钥/授权串、超长 base64，再截断。 */
internal fun sanitizeCompanionFailure(message: String): String {
    val cleaned = message
        .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer ***")
        .replace(Regex("(?i)([?&](?:key|api[_-]?key|access_token|token)=)[^&\\s]+")) { it.groupValues[1] + "***" }
        .replace(Regex("(?i)authorization\\s*:\\s*\\S+"), "Authorization: ***")
        .replace(Regex("(?i)(x-api-key|api-key)\\s*:\\s*\\S+")) { it.groupValues[1] + ": ***" }
        .replace(Regex("[A-Za-z0-9+/=_-]{80,}"), "[redacted]")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.length <= MAX_FAILURE_CHARS) return cleaned.ifBlank { "request failed" }
    return cleaned.take(MAX_FAILURE_CHARS).trimEnd() + "\u2026"
}

/** 一行错误最多显示这么多字符：够说清是什么错，又不至于把响应体整段铺在覆盖层上。 */
private const val MAX_FAILURE_CHARS = 160

@Composable
private fun triggerLabel(trigger: CompanionTrigger): String = when (trigger.kind) {
    CompanionTriggerKind.TAP -> stringResource(R.string.companion_status_trigger_tap)
    CompanionTriggerKind.INPUT -> stringResource(R.string.companion_status_trigger_input)
    CompanionTriggerKind.EVENT -> stringResource(
        when (trigger.event) {
            CompanionEventKind.GENERATION_STARTED -> R.string.companion_status_trigger_started
            CompanionEventKind.GENERATION_FINISHED -> R.string.companion_status_trigger_finished
            CompanionEventKind.GENERATION_FAILED -> R.string.companion_status_trigger_failed
            CompanionEventKind.APPROVAL_PENDING -> R.string.companion_status_trigger_approval
            null -> R.string.companion_status_trigger_event
        }
    )
}

@Composable
internal fun companionSkipLabel(reason: CompanionSkipReason): String = stringResource(
    when (reason) {
        CompanionSkipReason.NOT_VISIBLE -> R.string.companion_status_skip_not_visible
        CompanionSkipReason.BUSY -> R.string.companion_status_skip_busy
        CompanionSkipReason.AI_OFF -> R.string.companion_status_skip_ai_off
        CompanionSkipReason.AUTO_EVENTS_OFF -> R.string.companion_status_skip_auto_off
        CompanionSkipReason.COOLDOWN -> R.string.companion_status_skip_cooldown
        CompanionSkipReason.DUPLICATE_EVENT -> R.string.companion_status_skip_duplicate
        CompanionSkipReason.BUDGET -> R.string.companion_status_skip_budget
    }
)

@Composable
private fun cancelLabel(reason: CompanionCancelReason): String = stringResource(
    when (reason) {
        CompanionCancelReason.USER -> R.string.companion_status_cancel_user
        CompanionCancelReason.TIMEOUT -> R.string.companion_status_cancel_timeout
        CompanionCancelReason.LEFT_WINDOW -> R.string.companion_status_cancel_window
    }
)

private fun timeLabel(millis: Long): String =
    if (millis <= 0L) "--:--" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
