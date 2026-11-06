package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SlidersHorizontal
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.clientprofile.ClientProfileDecision
import me.rerere.rikkahub.data.ai.inputassist.InputAssistReviewState
import me.rerere.rikkahub.data.model.ConversationClientProfile
import me.rerere.rikkahub.data.model.ConversationReviewMode
import me.rerere.rikkahub.data.model.ConversationRuntimeOptions
import me.rerere.rikkahub.data.model.RunningMessageDelivery
import me.rerere.rikkahub.ui.components.ai.InputAssistReviewCard
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import kotlin.uuid.Uuid

/**
 * 会话内运行时选项（m1）的界面部分。
 *
 * 三块：
 * - [ConversationRuntimeBar]：输入栏上方一条紧凑摘要，点开面板；
 * - [InputAssistReviewSection]：输入辅助结果的紧凑入口，卡片限高 180dp，不撑爆输入栏；
 * - [ConversationRuntimePanel]：底部面板，6 组选项 + 「本次输入优化」原文/优化版对照。
 *
 * 约束（都是真的会看的）：
 * - 开关读写的是**当前会话**自己的 config.runtime，不碰助手默认值和别的会话；
 * - 写入期间 [ConversationRuntimePanel.busy] 为 true，控件全部禁用，避免在旧值上继续叠开关；
 * - 默认（全关）不产生任何额外请求，文案也不暗示优化/审查更"准确"；
 * - 原文/优化版对照必须在审查关闭时也能看，所以它在面板里独立成组，不依赖审查卡。
 */

/** 这一轮「发给模型的优化副本」的回读结果；[text] 为 null 表示这轮没有优化版。 */
data class OptimizedInputUiState(
    val text: String? = null,
    val loading: Boolean = false,
)

/**
 * 回读一条用户消息的优化副本。读盘在 IO 上做（[load] 自己也会切 IO），
 * 页面只拿一个不可变结果，流式生成时不会因为读盘卡主线程。
 */
@Composable
fun rememberOptimizedInput(
    messageId: Uuid?,
    enabled: Boolean,
    load: suspend (Uuid) -> String?,
): OptimizedInputUiState {
    val currentLoad by rememberUpdatedState(load)
    val state = produceState(
        initialValue = OptimizedInputUiState(text = null, loading = false),
        key1 = messageId,
        key2 = enabled,
    ) {
        if (messageId == null || !enabled) {
            // 没打开入口就不读盘；每次打开都重新读，不展示可能已过期的旧结论
            value = OptimizedInputUiState(text = null, loading = false)
            return@produceState
        }
        value = OptimizedInputUiState(text = null, loading = true)
        val text = try {
            withContext(Dispatchers.IO) { currentLoad(messageId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        value = OptimizedInputUiState(text = text?.takeIf { it.isNotBlank() }, loading = false)
    }
    return state.value
}

/** 输入栏上方的紧凑条：一个「会话选项」按钮 + 当前开启项摘要。 */
@Composable
fun ConversationRuntimeBar(
    options: ConversationRuntimeOptions,
    busy: Boolean,
    clientProfileLabel: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = runtimeOptionsSummary(options, clientProfileLabel)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpen, enabled = !busy) {
            Icon(
                imageVector = HugeIcons.SlidersHorizontal,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.m1_runtime_bar_button),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (summary.isEmpty()) {
                stringResource(R.string.m1_runtime_bar_default)
            } else {
                summary.joinToString(" · ")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            Spacer(modifier = Modifier.width(6.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
            )
        }
    }
}

/**
 * 审查结果的紧凑入口。非 Idle 才出现；默认折叠成一行（状态 + 展开/取消/关闭），
 * 展开后卡片限高 180dp 独立滚动 —— 长结论不能把输入栏挤出屏幕。
 */
@Composable
fun InputAssistReviewSection(
    state: InputAssistReviewState,
    originalText: String?,
    optimizedText: String?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onCopyOptimized: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (state is InputAssistReviewState.Idle) return
    // 换一条审查（不同回复）时重新折叠，旧卡片的展开状态不能带过来
    val stateKey = when (state) {
        is InputAssistReviewState.Running -> "running"
        is InputAssistReviewState.Failed -> "failed"
        is InputAssistReviewState.Done -> state.review.replyMessageId?.toString()
            ?: state.review.createdAtMillis.toString()
        is InputAssistReviewState.Idle -> "idle"
    }
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val statusText = when (state) {
        is InputAssistReviewState.Running -> stringResource(R.string.m1_runtime_review_running)
        is InputAssistReviewState.Failed -> stringResource(R.string.m1_runtime_review_failed)
        is InputAssistReviewState.Done -> stringResource(R.string.m1_runtime_review_done)
        is InputAssistReviewState.Idle -> ""
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.m1_runtime_review_collapse)
                        } else {
                            stringResource(R.string.m1_runtime_review_expand)
                        },
                    )
                }
                if (state is InputAssistReviewState.Running) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.m1_runtime_review_cancel))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.m1_runtime_review_dismiss))
                }
            }
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    InputAssistReviewCard(
                        state = state,
                        originalText = originalText,
                        optimizedText = optimizedText,
                        onDismiss = null,
                        onCopyOptimized = onCopyOptimized,
                    )
                }
            }
        }
    }
}

/**
 * 会话选项面板（底部面板内容）。无状态：读写都通过 [onChange] 交给 ChatVM，
 * 数据永远来自当前会话的 [ConversationRuntimeOptions]，面板里不另存一份草稿值。
 */
@Composable
fun ConversationRuntimePanel(
    options: ConversationRuntimeOptions,
    busy: Boolean,
    clientProfileDecision: ClientProfileDecision?,
    originalText: String?,
    optimizedInput: OptimizedInputUiState,
    onChange: (ConversationRuntimeOptions) -> Unit,
    onOpenKnowledge: () -> Unit,
    onCopyOptimized: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val commit: (ConversationRuntimeOptions) -> Unit = { next ->
        if (!busy) onChange(next)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.m1_runtime_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.m1_runtime_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) {
                Text(
                    text = stringResource(R.string.m1_runtime_saving),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 1) 输入：优化原话 + 回复审查
        CardGroup(title = { Text(stringResource(R.string.m1_runtime_group_input)) }) {
            switchItem(
                checked = options.optimizeUserInput,
                onCheckedChange = { checked -> commit(options.copy(optimizeUserInput = checked)) },
                enabled = !busy,
                headlineContent = { Text(stringResource(R.string.m1_runtime_optimize_title)) },
                supportingContent = { Text(stringResource(R.string.m1_runtime_optimize_desc)) },
            )
            formItem(
                headlineContent = { Text(stringResource(R.string.m1_runtime_review_title)) },
                supportingContent = { Text(reviewModeDescription(options.reviewMode)) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OptionChip(
                        selected = options.reviewMode == ConversationReviewMode.OFF,
                        enabled = !busy,
                        label = stringResource(R.string.m1_runtime_review_off),
                        onClick = { commit(options.copy(reviewMode = ConversationReviewMode.OFF)) },
                    )
                    OptionChip(
                        selected = options.reviewMode == ConversationReviewMode.SELF_CHECK,
                        enabled = !busy,
                        label = stringResource(R.string.m1_runtime_review_self),
                        onClick = { commit(options.copy(reviewMode = ConversationReviewMode.SELF_CHECK)) },
                    )
                    OptionChip(
                        selected = options.reviewMode == ConversationReviewMode.INDEPENDENT,
                        enabled = !busy,
                        label = stringResource(R.string.m1_runtime_review_independent),
                        onClick = { commit(options.copy(reviewMode = ConversationReviewMode.INDEPENDENT)) },
                    )
                }
            }
        }

        // 2) 本次输入优化：审查关闭时也能看到的原文/优化版对照
        CardGroup(title = { Text(stringResource(R.string.m1_runtime_group_optimized)) }) {
            formItem {
                OptimizedInputCompare(
                    originalText = originalText,
                    optimized = optimizedInput,
                    onCopyOptimized = onCopyOptimized,
                )
            }
        }

        // 3) 生成中插话
        CardGroup(title = { Text(stringResource(R.string.m1_runtime_group_delivery)) }) {
            formItem(
                headlineContent = { Text(stringResource(R.string.m1_runtime_delivery_title)) },
                supportingContent = { Text(stringResource(R.string.m1_runtime_delivery_desc)) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OptionChip(
                        selected = options.messageDelivery == RunningMessageDelivery.QUEUE,
                        enabled = !busy,
                        label = stringResource(R.string.m1_runtime_delivery_queue),
                        onClick = { commit(options.copy(messageDelivery = RunningMessageDelivery.QUEUE)) },
                    )
                    OptionChip(
                        selected = options.messageDelivery == RunningMessageDelivery.NEXT_STEP,
                        enabled = !busy,
                        label = stringResource(R.string.m1_runtime_delivery_next),
                        onClick = { commit(options.copy(messageDelivery = RunningMessageDelivery.NEXT_STEP)) },
                    )
                }
            }
        }

        // 4) 客户端协议档
        CardGroup(title = { Text(stringResource(R.string.m1_runtime_group_profile)) }) {
            formItem {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ConversationClientProfile.entries.forEach { profile ->
                            OptionChip(
                                selected = options.clientProfile == profile,
                                enabled = !busy,
                                label = clientProfileLabel(profile),
                                onClick = { commit(options.copy(clientProfile = profile)) },
                            )
                        }
                    }
                    Text(
                        text = clientProfileDescription(clientProfileDecision),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.m1_runtime_profile_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 5) 本地知识库
        CardGroup(title = { Text(stringResource(R.string.m1_runtime_group_knowledge)) }) {
            switchItem(
                checked = options.knowledgeEnabled,
                onCheckedChange = { checked -> commit(options.copy(knowledgeEnabled = checked)) },
                enabled = !busy,
                headlineContent = { Text(stringResource(R.string.m1_runtime_knowledge_title)) },
                supportingContent = { Text(stringResource(R.string.m1_runtime_knowledge_desc)) },
            )
            item(
                onClick = { if (!busy) onOpenKnowledge() },
                headlineContent = { Text(stringResource(R.string.m1_runtime_knowledge_manage)) },
                supportingContent = { Text(stringResource(R.string.m1_runtime_knowledge_manage_desc)) },
            )
        }

        // 6) 工具执行
        CardGroup(title = { Text(stringResource(R.string.m1_runtime_group_tools)) }) {
            switchItem(
                checked = options.efficientTools,
                onCheckedChange = { checked -> commit(options.copy(efficientTools = checked)) },
                enabled = !busy,
                headlineContent = { Text(stringResource(R.string.m1_runtime_efficient_title)) },
                supportingContent = { Text(stringResource(R.string.m1_runtime_efficient_desc)) },
            )
        }
    }
}

@Composable
private fun OptionChip(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text = label, maxLines = 1) },
    )
}

@Composable
private fun reviewModeDescription(mode: ConversationReviewMode): String = when (mode) {
    ConversationReviewMode.OFF -> stringResource(R.string.m1_runtime_review_off_desc)
    ConversationReviewMode.SELF_CHECK -> stringResource(R.string.m1_runtime_review_self_desc)
    ConversationReviewMode.INDEPENDENT -> stringResource(R.string.m1_runtime_review_independent_desc)
}

@Composable
private fun clientProfileLabel(profile: ConversationClientProfile): String = when (profile) {
    ConversationClientProfile.NATIVE -> stringResource(R.string.m1_runtime_profile_native)
    ConversationClientProfile.AUTO -> stringResource(R.string.m1_runtime_profile_auto)
    ConversationClientProfile.CODEX -> stringResource(R.string.m1_runtime_profile_codex)
    ConversationClientProfile.CLAUDE_CODE -> stringResource(R.string.m1_runtime_profile_claude)
    ConversationClientProfile.PI -> stringResource(R.string.m1_runtime_profile_pi)
}

/**
 * 兼容档当前的真实判定。[decision] 为 null 表示还没有可用模型，
 * 这时不显示"已生效"之类的结论。
 */
@Composable
private fun clientProfileDescription(decision: ClientProfileDecision?): String {
    if (decision == null) return stringResource(R.string.m1_runtime_profile_unknown)
    val headline = if (decision.fellBack) {
        stringResource(R.string.m1_runtime_profile_fallback)
    } else {
        stringResource(R.string.m1_runtime_profile_current, decision.label)
    }
    return if (decision.reason.isBlank()) headline else "$headline ${decision.reason}"
}

/** 输入栏摘要：只说开启了的项，全部默认时返回空列表。 */
@Composable
private fun runtimeOptionsSummary(
    options: ConversationRuntimeOptions,
    clientProfileLabel: String?,
): List<String> {
    val reviewSelf = stringResource(R.string.m1_runtime_summary_self_check)
    val reviewIndependent = stringResource(R.string.m1_runtime_summary_review)
    val optimize = stringResource(R.string.m1_runtime_summary_optimize)
    val nextStep = stringResource(R.string.m1_runtime_summary_next_step)
    val knowledge = stringResource(R.string.m1_runtime_summary_knowledge)
    val toolsOff = stringResource(R.string.m1_runtime_summary_tools_off)
    val profileTemplate = stringResource(R.string.m1_runtime_summary_profile)
    return buildList {
        if (options.optimizeUserInput) add(optimize)
        when (options.reviewMode) {
            ConversationReviewMode.OFF -> Unit
            ConversationReviewMode.SELF_CHECK -> add(reviewSelf)
            ConversationReviewMode.INDEPENDENT -> add(reviewIndependent)
        }
        if (options.messageDelivery == RunningMessageDelivery.NEXT_STEP) add(nextStep)
        if (options.clientProfile != ConversationClientProfile.NATIVE) {
            add(profileTemplate.format(clientProfileLabel ?: options.clientProfile.name))
        }
        if (options.knowledgeEnabled) add(knowledge)
        // 高效工具默认开，只有被关掉时才是值得提示的状态
        if (!options.efficientTools) add(toolsOff)
    }
}

/**
 * 原文 / 优化版对照。审查关闭、优化已经不再生效时也照样能看这一轮的副本；
 * 文本可选中复制（[SelectionContainer]），文案不主张"优化版更好"。
 */
@Composable
private fun OptimizedInputCompare(
    originalText: String?,
    optimized: OptimizedInputUiState,
    onCopyOptimized: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (originalText.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.m1_runtime_optimized_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            text = stringResource(R.string.m1_runtime_optimized_original_label),
            style = MaterialTheme.typography.labelMedium,
        )
        SelectionContainer {
            Text(
                text = originalText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            optimized.loading -> Text(
                text = stringResource(R.string.m1_runtime_optimized_loading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            optimized.text == null -> Text(
                text = stringResource(R.string.m1_runtime_optimized_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                val sent = optimized.text.orEmpty()
                Text(
                    text = stringResource(R.string.m1_runtime_optimized_sent_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                SelectionContainer {
                    Text(
                        text = sent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = stringResource(R.string.m1_runtime_optimized_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onCopyOptimized(sent) }) {
                    Text(stringResource(R.string.m1_runtime_optimized_copy))
                }
            }
        }
    }
}
