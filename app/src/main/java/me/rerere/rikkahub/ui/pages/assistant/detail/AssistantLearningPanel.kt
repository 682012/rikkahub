package me.rerere.rikkahub.ui.pages.assistant.detail

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.learning.AssistantLearningRepository
import me.rerere.rikkahub.data.ai.learning.LearningGuidance
import me.rerere.rikkahub.data.ai.learning.LearningLimits
import me.rerere.rikkahub.data.ai.learning.LearningState
import me.rerere.rikkahub.data.ai.learning.LearningStats
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.appearanceSurface
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.compose.koinInject

/** 正在编辑的人工指导；[id] 为空表示新增，[enabled] 是编辑前的开关状态，保存时不能顺手改掉。 */
private data class GuidanceDraft(
    val id: String?,
    val text: String,
    val enabled: Boolean = true,
)

/**
 * 记忆页里的「经验学习」页：开关、实测指标、按指标得出的经验、人工指导。
 *
 * 数据全部来自 [AssistantLearningRepository]（每个助手一个文件），页面只读当前助手的快照；
 * 任何一次写操作失败都会显示成错误卡片，不会只留一个不动的开关。
 */
@Composable
internal fun AssistantLearningTab(
    assistantId: String,
    modifier: Modifier = Modifier,
) {
    val repository = koinInject<AssistantLearningRepository>()
    val experienceService = koinInject<me.rerere.rikkahub.data.ai.learning.ExperienceService>()
    val lastExperience by experienceService.lastReport.collectAsState()
    val scope = rememberCoroutineScope()
    var state by remember(assistantId) { mutableStateOf<LearningState?>(null) }
    var loadError by remember(assistantId) { mutableStateOf<String?>(null) }
    var actionError by remember(assistantId) { mutableStateOf<String?>(null) }
    var busy by remember(assistantId) { mutableStateOf(false) }
    var reloadToken by remember(assistantId) { mutableIntStateOf(0) }

    var draft by remember(assistantId) { mutableStateOf<GuidanceDraft?>(null) }
    var pendingDelete by remember(assistantId) { mutableStateOf<LearningGuidance?>(null) }
    var showClearConfirm by remember(assistantId) { mutableStateOf(false) }

    // observe() 先推一份当前快照，之后每次落盘成功再推新的，所以这里不用单独读一次
    LaunchedEffect(assistantId, reloadToken) {
        loadError = null
        repository.observe(assistantId)
            .catch { error -> loadError = error.displayMessage() }
            .collect { snapshot -> state = snapshot }
    }

    // 所有写操作都走这里：失败一定落到 actionError，成功则直接用返回的新快照刷新
    fun perform(block: suspend () -> LearningState) {
        if (busy) return
        busy = true
        actionError = null
        scope.launch {
            try {
                state = block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = error.displayMessage()
            } finally {
                busy = false
            }
        }
    }

    val current = state

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        loadError?.let { message ->
            LearningErrorCard(
                title = stringResource(R.string.r21_learning_load_error),
                message = message,
                actionText = stringResource(R.string.r21_learning_retry),
                onAction = { reloadToken++ },
            )
        }

        actionError?.let { message ->
            LearningErrorCard(
                title = stringResource(R.string.r21_learning_action_error),
                message = message,
                actionText = stringResource(R.string.r21_learning_close),
                onAction = { actionError = null },
            )
        }

        if (current == null) {
            if (loadError == null) {
                LearningPlaceholderCard(stringResource(R.string.r21_learning_loading))
            }
            return@Column
        }

        // 开关：关掉只是停止收集和注入，磁盘上的东西一条都不动
        CardGroup {
            switchItem(
                checked = current.enabled,
                onCheckedChange = { enabled ->
                    perform { repository.setEnabled(assistantId, enabled) }
                },
                enabled = !busy,
                supportingContent = {
                    Text(
                        stringResource(
                            if (current.enabled) {
                                R.string.r21_learning_switch_desc
                            } else {
                                R.string.r21_learning_switch_off_hint
                            }
                        )
                    )
                },
                headlineContent = { Text(stringResource(R.string.r21_learning_switch)) },
            )
            // 每轮经验总结：额外一次模型请求（会计费），所以单独一个开关，默认关闭
            switchItem(
                checked = current.experienceReflection,
                onCheckedChange = { enabled ->
                    perform { repository.setExperienceReflection(assistantId, enabled) }
                },
                enabled = !busy,
                supportingContent = {
                    Text(
                        stringResource(
                            if (current.experienceReflection) {
                                R.string.r21_learning_experience_switch_desc
                            } else {
                                R.string.r21_learning_experience_switch_off_hint
                            }
                        )
                    )
                },
                headlineContent = { Text(stringResource(R.string.r21_learning_experience_switch)) },
            )
        }

        lastExperience?.takeIf { it.assistantId == assistantId }?.let { report ->
            val details = listOfNotNull(report.error, report.skipped, report.write?.memoryRejected,
                report.write?.knowledgeRejected).joinToString("；")
            Text(if (details.isNotBlank()) "最近一次经验总结：$details"
                else "最近一次经验总结：${if (report.write?.wroteAnything == true) "已写入" else "无新增可复用经验"}",
                style = MaterialTheme.typography.bodySmall)
        }

        LearningMetricsSection(stats = current.stats)

        LearningAutoGuidanceSection(
            guidances = current.autoGuidances,
            enabled = current.enabled,
            onToggle = { guidance, enabled ->
                perform { repository.setGuidanceEnabled(assistantId, guidance.id, enabled) }
            },
        )

        LearningUserGuidanceSection(
            guidances = current.userGuidances,
            enabledSwitch = !busy,
            onToggle = { guidance, enabled ->
                perform { repository.setGuidanceEnabled(assistantId, guidance.id, enabled) }
            },
            onEdit = { guidance -> draft = GuidanceDraft(guidance.id, guidance.text, guidance.enabled) },
            onDelete = { guidance -> pendingDelete = guidance },
            onAdd = { draft = GuidanceDraft(id = null, text = "") },
        )

        LearningStorageSection(state = current)

        if (current.stats.turns > 0 || current.userGuidances.isNotEmpty()) {
            CardGroup {
                item(
                    onClick = { if (!busy) showClearConfirm = true },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Clean,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.r21_learning_clear)) },
                    supportingContent = { Text(stringResource(R.string.r21_learning_clear_desc)) },
                )
            }
        }

        Text(
            text = stringResource(
                R.string.r21_learning_inject_hint,
                LearningLimits.MAX_PROMPT_RULES,
                LearningLimits.MAX_PROMPT_CHARS,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    draft?.let { editing ->
        GuidanceEditorDialog(
            draft = editing,
            onDismiss = { draft = null },
            onSave = { text ->
                draft = null
                // 编辑一条已停用的经验只改文案，不会把它重新打开
                perform { repository.upsertGuidance(assistantId, text, id = editing.id, enabled = editing.enabled) }
            },
        )
    }

    RikkaConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.r21_learning_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            val target = pendingDelete
            pendingDelete = null
            target?.let { guidance ->
                perform { repository.deleteGuidance(assistantId, guidance.id) }
            }
        },
        onDismiss = { pendingDelete = null },
        text = {
            Text(
                text = pendingDelete?.text.orEmpty(),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )

    RikkaConfirmDialog(
        show = showClearConfirm,
        title = stringResource(R.string.r21_learning_clear_confirm_title),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showClearConfirm = false
            perform { repository.clear(assistantId) }
        },
        onDismiss = { showClearConfirm = false },
        text = { Text(stringResource(R.string.r21_learning_clear_confirm_text)) },
    )
}

/** 实测指标：只有计数，没有分数。 */
@Composable
private fun LearningMetricsSection(stats: LearningStats) {
    CardGroup(title = { Text(stringResource(R.string.r21_learning_metrics_title)) }) {
        if (stats.turns <= 0) {
            item(
                headlineContent = { Text(stringResource(R.string.r21_learning_metric_none)) },
                supportingContent = {
                    Text(stringResource(R.string.r21_learning_metric_window, LearningLimits.MAX_TURNS))
                },
            )
        } else {
            formItem {
                val metrics = listOf(
                    LearningMetric(HugeIcons.ChartColumn, R.string.r21_learning_metric_turns, stats.turns),
                    LearningMetric(HugeIcons.Idea01, R.string.r21_learning_metric_completed, stats.completedTurns),
                    LearningMetric(HugeIcons.PencilEdit01, R.string.r21_learning_metric_tool_calls, stats.toolCalls),
                    LearningMetric(HugeIcons.AlertCircle, R.string.r21_learning_metric_tool_errors, stats.toolErrors),
                    LearningMetric(HugeIcons.Refresh01, R.string.r21_learning_metric_retries, stats.retries),
                    LearningMetric(HugeIcons.StopCircle, R.string.r21_learning_metric_step_limit, stats.stepLimitTurns),
                    LearningMetric(HugeIcons.Repeat, R.string.r21_learning_metric_regenerated, stats.regeneratedTurns),
                )
                metrics.chunked(2).fastForEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.fastForEach { metric ->
                            LearningMetricTile(metric = metric, modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.r21_learning_metric_tool_error_rate,
                            (stats.toolErrorRate * 100).toInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.r21_learning_metric_window, LearningLimits.MAX_TURNS),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class LearningMetric(
    val icon: ImageVector,
    val labelRes: Int,
    val value: Int,
)

@Composable
private fun LearningMetricTile(
    metric: LearningMetric,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = appearanceSurface(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = metric.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(metric.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = metric.value.toString(),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

/** 按指标算出来的经验：只读，来源是计数。 */
@Composable
private fun LearningAutoGuidanceSection(
    guidances: List<LearningGuidance>,
    enabled: Boolean,
    onToggle: (LearningGuidance, Boolean) -> Unit,
) {
    CardGroup(title = { Text(stringResource(R.string.r21_learning_auto_title)) }) {
        if (guidances.isEmpty()) {
            item(
                headlineContent = {
                    Text(
                        text = stringResource(
                            if (enabled) {
                                R.string.r21_learning_auto_empty
                            } else {
                                R.string.r21_learning_switch_off_hint
                            },
                            LearningLimits.MIN_EVIDENCE_TURNS,
                        )
                    )
                },
            )
        } else {
            guidances.fastForEach { guidance ->
                item(
                    headlineContent = {
                        Text(text = guidance.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(stringResource(R.string.r21_learning_auto_evidence, guidance.evidenceTurns))
                    },
                    trailingContent = {
                        Switch(checked = guidance.enabled, enabled = enabled,
                            onCheckedChange = { onToggle(guidance, it) })
                    },
                )
            }
        }
    }
}

/** 人工指导：可加、可改、可停用、可删。 */
@Composable
private fun LearningUserGuidanceSection(
    guidances: List<LearningGuidance>,
    enabledSwitch: Boolean,
    onToggle: (LearningGuidance, Boolean) -> Unit,
    onEdit: (LearningGuidance) -> Unit,
    onDelete: (LearningGuidance) -> Unit,
    onAdd: () -> Unit,
) {
    CardGroup(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.r21_learning_user_title),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        R.string.r21_learning_user_count,
                        guidances.size,
                        LearningLimits.MAX_USER_GUIDANCE,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    ) {
        item(
            onClick = onAdd,
            leadingContent = { CardGroupIcon(HugeIcons.Add01) },
            headlineContent = { Text(stringResource(R.string.r21_learning_add)) },
        )

        if (guidances.isEmpty()) {
            item(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.r21_learning_user_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        } else {
            guidances.fastForEach { guidance ->
                item(
                    onClick = { onEdit(guidance) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (guidance.enabled) {
                                    R.string.r21_learning_user_source
                                } else {
                                    R.string.r21_learning_user_disabled
                                }
                            )
                        )
                    },
                    trailingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = guidance.enabled,
                                onCheckedChange = { onToggle(guidance, it) },
                                enabled = enabledSwitch,
                            )
                            ItemActionMenu(
                                actions = listOf(
                                    ItemAction(
                                        text = stringResource(R.string.edit),
                                        icon = HugeIcons.PencilEdit01,
                                        onClick = { onEdit(guidance) },
                                    ),
                                    ItemAction(
                                        text = stringResource(R.string.delete),
                                        icon = HugeIcons.Delete01,
                                        destructive = true,
                                        onClick = { onDelete(guidance) },
                                    ),
                                )
                            )
                        }
                    },
                    headlineContent = {
                        Text(
                            text = guidance.text,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

/** 存哪、坏没坏过：坏了就把备份路径直接写出来。 */
@Composable
private fun LearningStorageSection(state: LearningState) {
    val errorColors = ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        headlineColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    val storage = state.storage
    val backup = storage.corruptBackup
    CardGroup(title = { Text(stringResource(R.string.r21_learning_storage_title)) }) {
        item(
            headlineContent = {
                Text(
                    text = if (storage.present) {
                        stringResource(R.string.r21_learning_storage, storage.path)
                    } else {
                        stringResource(R.string.r21_learning_storage_absent)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            supportingContent = {
                Text(
                    text = if (state.updatedAt > 0L) {
                        stringResource(
                            R.string.r21_learning_metric_last,
                            learningRelativeTimeOf(state.updatedAt),
                        )
                    } else {
                        stringResource(R.string.r21_learning_storage_never)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
        if (backup != null) {
            item(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.r21_learning_corrupt, backup),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                },
                colors = errorColors,
            )
        }
    }
}

/** 读不到文件 / 写失败时的提示：错误内容一定显示出来，不会静默。 */
@Composable
private fun LearningErrorCard(
    title: String,
    message: String,
    actionText: String,
    onAction: () -> Unit,
) {
    val errorColors = ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        headlineColor = MaterialTheme.colorScheme.onErrorContainer,
        supportingColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    CardGroup {
        item(
            leadingContent = {
                CardGroupIcon(
                    icon = HugeIcons.AlertCircle,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            },
            headlineContent = { Text(title) },
            supportingContent = { Text(message, maxLines = 6, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                TextButton(onClick = onAction) {
                    Text(actionText)
                }
            },
            colors = errorColors,
        )
    }
}

@Composable
private fun LearningPlaceholderCard(message: String) {
    CardGroup {
        item(
            headlineContent = {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

/** 新增 / 编辑一条人工指导。保存时内容为空不让提交。 */
@Composable
private fun GuidanceEditorDialog(
    draft: GuidanceDraft,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(draft) { mutableStateOf(draft.text) }
    val trimmed = text.trim()
    val limit = LearningLimits.MAX_GUIDANCE_TEXT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (draft.id == null) R.string.r21_learning_add_title else R.string.r21_learning_edit_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.take(limit) },
                    label = { Text(stringResource(R.string.r21_learning_field_label)) },
                    placeholder = { Text(stringResource(R.string.r21_learning_field_placeholder)) },
                    supportingText = {
                        Text(stringResource(R.string.r21_learning_field_counter, text.length, limit))
                    },
                    minLines = 3,
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty(),
                onClick = { onSave(trimmed) },
            ) {
                Text(stringResource(R.string.r21_learning_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** 异常文案：有 message 用 message，没有就退回类名，绝不给空串。 */
private fun Throwable.displayMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

private fun learningRelativeTimeOf(time: Long): String = DateUtils.getRelativeTimeSpanString(
    time,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_MONTH,
).toString()
