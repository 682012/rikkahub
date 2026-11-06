package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.inputassist.InputAssistNotice
import me.rerere.rikkahub.data.ai.inputassist.InputAssistReview
import me.rerere.rikkahub.data.ai.inputassist.InputAssistReviewState
import me.rerere.rikkahub.data.ai.inputassist.InputReviewVerdict

/**
 * 审查结果卡片：独立审查的结论、优化前后的对照都只说事实。
 *
 * 四条不许违反的显示规则：
 * - 优化版只在这里折叠展示，会话里那条消息永远显示用户原话；
 * - 审查是文本意见，卡片里固定写明它不执行工具、不能替代真实编译/运行；
 * - "没列出问题"不等于"验证过没问题"，空结论必须这么写；
 * - 取消只停止等待和显示，不退款；审查和优化各自计费，都要写出来。
 *
 * 文案走 `r22_input_assist.xml`（默认英文 + zh / zh-rTW），代码里不留可翻译的硬编码句子。
 */
@Composable
fun InputAssistReviewCard(
    state: InputAssistReviewState,
    modifier: Modifier = Modifier,
    /** 用户这条消息的原话（库里那份），用于对照 */
    originalText: String? = null,
    /** 这一轮真正发给模型的优化版；为空表示这轮没优化 */
    optimizedText: String? = null,
    onDismiss: (() -> Unit)? = null,
    onCopyOptimized: ((String) -> Unit)? = null,
) {
    if (state is InputAssistReviewState.Idle) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                is InputAssistReviewState.Idle -> Unit
                is InputAssistReviewState.Running -> {
                    Text(
                        stringResource(R.string.r22_input_review_running_title),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Text(
                        stringResource(R.string.r22_input_review_running_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.r22_input_review_running_cancel_cost),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is InputAssistReviewState.Failed -> {
                    Text(
                        stringResource(R.string.r22_input_review_failed_title),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Text(
                        state.reason?.takeIf { it.isNotBlank() }
                            ?.let { stringResource(R.string.r22_input_review_failed_reason, it) }
                            ?: stringResource(R.string.r22_input_review_failed_no_result),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.r22_input_review_failed_untouched),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is InputAssistReviewState.Done -> ReviewBody(
                    review = state.review,
                    originalText = originalText,
                    optimizedText = optimizedText,
                    onCopyOptimized = onCopyOptimized,
                )
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.r22_input_review_dismiss))
                }
            }
        }
    }
}

@Composable
private fun ReviewBody(
    review: InputAssistReview,
    originalText: String?,
    optimizedText: String?,
    onCopyOptimized: ((String) -> Unit)?,
) {
    var showTexts by remember(review.replyMessageId, review.createdAtMillis) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                if (review.independent) {
                    R.string.r22_input_review_title_independent
                } else {
                    R.string.r22_input_review_title_self
                },
            ),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        VerdictBadge(review.verdict)
        if (review.cached) {
            Text(
                stringResource(R.string.r22_input_review_cached),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (review.findings.isEmpty()) {
        // 审查没列出问题 ≠ 验证过没问题：这句话必须保留"没有验证"的含义
        Text(
            stringResource(R.string.r22_input_review_empty),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        review.findings.forEach { finding ->
            Text("• $finding", style = MaterialTheme.typography.bodyMedium)
        }
    }
    review.suggestion?.takeIf { it.isNotBlank() }?.let { suggestion ->
        Text(
            stringResource(R.string.r22_input_review_suggestion, suggestion),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        review.limits.ifBlank {
            if (review.independent) InputAssistNotice.INDEPENDENT_DISCLAIMER else InputAssistNotice.SELF_CHECK_DISCLAIMER
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(
            if (review.independent) {
                R.string.r22_input_review_limits_independent
            } else {
                R.string.r22_input_review_limits_self
            },
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    review.modelName?.takeIf { it.isNotBlank() }?.let { model ->
        Text(
            stringResource(R.string.r22_input_review_model, model),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        stringResource(R.string.r22_input_review_cost),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (optimizedText != null || originalText != null) {
        TextButton(onClick = { showTexts = !showTexts }) {
            Text(
                stringResource(
                    if (showTexts) {
                        R.string.r22_input_review_compare_hide
                    } else {
                        R.string.r22_input_review_compare_show
                    },
                ),
            )
        }
        AnimatedVisibility(visible = showTexts) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.r22_input_review_original_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        originalText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                if (optimizedText != null) {
                    Text(
                        stringResource(R.string.r22_input_review_optimized_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            optimizedText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    if (onCopyOptimized != null) {
                        TextButton(onClick = { onCopyOptimized(optimizedText) }) {
                            Text(stringResource(R.string.r22_input_review_copy_optimized))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerdictBadge(verdict: InputReviewVerdict) {
    val (label, color) = when (verdict) {
        InputReviewVerdict.OK -> stringResource(R.string.r22_input_review_verdict_ok) to
            MaterialTheme.colorScheme.secondary
        InputReviewVerdict.CONCERN -> stringResource(R.string.r22_input_review_verdict_concern) to
            MaterialTheme.colorScheme.tertiary
        InputReviewVerdict.RISK -> stringResource(R.string.r22_input_review_verdict_risk) to
            MaterialTheme.colorScheme.error
        InputReviewVerdict.UNKNOWN -> stringResource(R.string.r22_input_review_verdict_unknown) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
