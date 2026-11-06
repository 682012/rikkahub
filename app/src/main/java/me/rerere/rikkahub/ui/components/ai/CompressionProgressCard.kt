package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.CompressionOutcome
import me.rerere.rikkahub.service.CompressionPhase
import me.rerere.rikkahub.service.CompressionState

/**
 * 压缩进度卡片：只显示真实值。
 *
 * 阶段、已用秒数、已生成字符数（Unicode）都来自 [CompressionState]；没有总量，所以进度条永远是不确定的，
 * 不存在按时间编出来的百分比。手动压缩可以取消，自动压缩跟着这轮生成走，不给取消按钮。
 */
@Composable
fun CompressionProgressCard(
    state: CompressionState,
    modifier: Modifier = Modifier,
    /** 对话页里的窄条形态：只留一行阶段 + 一行实时量 */
    compact: Boolean = false,
    onCancel: (() -> Unit)? = null,
) {
    val elapsedSeconds = rememberElapsedSeconds(state)
    var expandPreview by remember(state.taskId) { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = phaseLabel(state),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.r22_compression_elapsed_seconds, elapsedSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.isRunning) {
                // 没有总量：不确定进度条，绝不假装百分比
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.hasGeneratedText) {
                Text(
                    text = stringResource(
                        R.string.r22_compression_generated_chars,
                        state.generatedChars,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.isRunning && !compact) {
                Text(
                    text = stringResource(R.string.r22_compression_no_total_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.message?.takeIf { !state.isRunning }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.isRunning && state.summaryPreview.isNotBlank() && !compact) {
                TextButton(onClick = { expandPreview = !expandPreview }) {
                    Text(
                        stringResource(
                            if (expandPreview) R.string.r22_compression_preview_hide
                            else R.string.r22_compression_preview_show
                        )
                    )
                }
                AnimatedVisibility(visible = expandPreview) {
                    Text(
                        text = state.summaryPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.isRunning && state.cancellable && onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.r22_compression_cancel))
                }
            }
        }
    }
}

@Composable
private fun phaseLabel(state: CompressionState): String {
    val outcome = state.outcome
    if (outcome != null) {
        return stringResource(
            when (outcome) {
                CompressionOutcome.Succeeded -> R.string.r22_compression_done_succeeded
                CompressionOutcome.Failed -> R.string.r22_compression_done_failed
                CompressionOutcome.Discarded -> R.string.r22_compression_done_discarded
                CompressionOutcome.Cancelled -> R.string.r22_compression_done_cancelled
            }
        )
    }
    return stringResource(
        when (state.phase) {
            CompressionPhase.Preparing -> R.string.r22_compression_phase_preparing
            CompressionPhase.WaitingModel -> R.string.r22_compression_phase_waiting_model
            CompressionPhase.Generating -> R.string.r22_compression_phase_generating
            CompressionPhase.Writing -> R.string.r22_compression_phase_writing
        }
    )
}

/**
 * 已用秒数：每秒刷新一次，不刷新就不叫实时进度。
 * 结束的任务停在完成时刻，不再继续涨。首次组合时先按当前时刻算一次，避免闪 0。
 */
@Composable
private fun rememberElapsedSeconds(state: CompressionState): Long {
    val start = state.startedAt
    val end = state.finishedAt
    var now by remember(start, end) {
        mutableLongStateOf(end ?: System.currentTimeMillis())
    }
    LaunchedEffect(start, end) {
        if (end != null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (start <= 0L) return 0L
    return ((now - start).coerceAtLeast(0L)) / 1000L
}
