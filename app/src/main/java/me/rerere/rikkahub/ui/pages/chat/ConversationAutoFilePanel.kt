package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.organization.autofile.AutoFileBatchDetail
import me.rerere.rikkahub.data.organization.autofile.AutoFileBatchStatus
import me.rerere.rikkahub.data.organization.autofile.AutoFileManualPolicy
import me.rerere.rikkahub.data.organization.autofile.AutoFileMode
import me.rerere.rikkahub.data.organization.autofile.AutoFileMoveStatus
import me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileController
import me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileSettings
import me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/** 闲置时长滑杆的步长（小时）：24..168 之间每 6 小时一档，不会停在 37 这种奇怪值上。 */
internal const val IDLE_STEP_HOURS = 6

/** 滑杆一共有几档（含两端）。 */
internal fun idleStepCount(): Int =
    (ConversationAutoFileSettings.MAX_IDLE_HOURS - ConversationAutoFileSettings.MIN_IDLE_HOURS) / IDLE_STEP_HOURS

/** 第 index 档对应的小时数，index 超界按端点夹住。 */
internal fun idleHoursAt(index: Int): Int =
    ConversationAutoFileSettings.MIN_IDLE_HOURS + index.coerceIn(0, idleStepCount()) * IDLE_STEP_HOURS

/** 把已存的小时数换算成最近的档位；旧数据落在两档之间时向上取，读数不会比实际更激进。 */
internal fun idleIndexFor(hours: Int): Int {
    val span = ConversationAutoFileSettings.MAX_IDLE_HOURS - ConversationAutoFileSettings.MIN_IDLE_HOURS
    val offset = (hours - ConversationAutoFileSettings.MIN_IDLE_HOURS).coerceIn(0, span)
    return ((offset + IDLE_STEP_HOURS / 2) / IDLE_STEP_HOURS).coerceIn(0, idleStepCount())
}

/**
 * 自动归库没开、且是 **AI 模式**时，「立即整理」要先确认一次会把摘要发出去、花额度；
 * 本地规则模式完全不发网、不花额度，不需要这层确认（开关默认关闭这一点仍然保留）。
 *
 * 真正的判断在 [AutoFileManualPolicy.needsRunConfirmation]，面板和 MCP 用同一份口径。
 */
internal fun needsRunConfirmation(settings: ConversationAutoFileSettings): Boolean =
    AutoFileManualPolicy.needsRunConfirmation(settings)

/** 已开启自动归档时从本地模式切到 AI 模式：这会让之后每批都开始外发，保存前要再确认一次。 */
internal fun needsModeSwitchConfirmation(
    settings: ConversationAutoFileSettings,
    target: AutoFileMode,
): Boolean = AutoFileManualPolicy.modeSwitchNeedsConfirmation(settings, target)

/** 撤销只看有没有可撤销的批：上一批被中断（带 error）照样要能撤，不因为报错就把入口藏掉。 */
internal fun undoOffered(state: ConversationAutoFileState): Boolean = state.canUndo

/** 历史列表最多显示几批（日志本身只留 10 批）。 */
private const val HISTORY_LIMIT = 5

/**
 * 「自动归库」设置面板（底部 Sheet）。
 *
 * 只读 [ConversationAutoFileController] 的状态、只调它的接口；自己不含业务状态，
 * 也不在 Compose 主线程上做文件 IO（设置写盘/历史读取都走 [Dispatchers.IO]）。
 *
 * 硬约束：
 * - 摘要外发的告知必须排在开关**前面**，用户先看到「发什么、花额度、默认关、可撤销」再决定；
 * - 本地规则模式要和 AI 模式分开写清楚：不发网、不调模型、可解释，但**不承诺准确率**；
 * - 整理运行中不锁开关，「关掉自动归库」是用户中途叫停的唯一手段；被中断的批要能一路撤销。
 */
@Composable
fun ConversationAutoFilePanel(
    assistantId: Uuid,
    onDismiss: () -> Unit,
    /** 打开某条已归档对话。抽屉里会接到导航；没接线时按钮不出现。 */
    onOpenConversation: ((Uuid) -> Unit)? = null,
) {
    val controller: ConversationAutoFileController = koinInject()
    val scope = rememberCoroutineScope()
    val state by remember(assistantId, controller) { controller.observe(assistantId) }.collectAsState()
    val settings = state.settings
    var confirmRun by remember(assistantId) { mutableStateOf(false) }
    // 已开启自动归档时切到 AI 模式：先确认「之后每批都会外发摘要」，再落盘。
    var confirmModeSwitch by remember(assistantId) { mutableStateOf(false) }
    var localError by remember(assistantId) { mutableStateOf<String?>(null) }
    val busy = state.running
    // 拖动时只改本地草稿，松手才落盘，避免一路拖一路写设置
    var idleDraft by remember(settings.idleHours) { mutableFloatStateOf(idleIndexFor(settings.idleHours).toFloat()) }

    var history by remember(assistantId) { mutableStateOf<List<me.rerere.rikkahub.data.organization.autofile.AutoFileJournalBatch>>(emptyList()) }
    var historyLoaded by remember(assistantId) { mutableStateOf(false) }
    var detail by remember(assistantId) { mutableStateOf<AutoFileBatchDetail?>(null) }

    // 所有动作统一走这里：IO 线程执行、不吞取消、异常落到面板状态而不是崩掉界面。
    fun perform(block: suspend () -> Unit) {
        localError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                localError = error.message?.takeIf { it.isNotBlank() } ?: error.toString()
            }
        }
    }

    fun saveSettings(next: ConversationAutoFileSettings) = perform { controller.updateSettings(assistantId, next) }

    // 「立即整理」排成后台一次性任务：面板关掉、Compose 作用域被取消，整理都照样跑完；
    // 同一个助手已有任务在排队/运行时不会重复入队（Controller 会回真实的 work 回执）。
    fun organize() = perform { controller.enqueueManualRun(assistantId) }
    fun undo() = perform { controller.undoLast(assistantId) }

    // 打开面板、或一批跑完/撤销完时刷新历史：后台批次（含其它进程）留下的记录也能看到。
    LaunchedEffect(assistantId, busy, state.moved, state.canUndo) {
        withContext(Dispatchers.IO) {
            history = controller.history(assistantId, HISTORY_LIMIT)
            historyLoaded = true
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.r21_autofile_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.r21_autofile_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 开开关之前就要看到的东西：发什么、花额度、默认关、可撤销
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.r21_autofile_privacy_title),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    listOf(
                        R.string.r21_autofile_privacy,
                        R.string.r21_autofile_privacy_quota,
                        R.string.r21_autofile_privacy_default_off,
                        R.string.r21_autofile_privacy_reversible,
                    ).forEach { line ->
                        Text(
                            text = stringResource(line),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // 整理方式：AI 分类 / 完全本地规则。默认 AI，保持 r21 行为不变。
            CardGroup(title = { Text(stringResource(R.string.r22_autofile_mode_title)) }) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.r22_autofile_mode_ai)) },
                    supportingContent = { Text(stringResource(R.string.r22_autofile_mode_ai_desc)) },
                    trailingContent = {
                        FilterChip(
                            selected = settings.mode == AutoFileMode.AI,
                            onClick = {
                                if (settings.mode != AutoFileMode.AI) {
                                    // 已开启自动归档时切到 AI = 之后每批都外发摘要，必须先确认再保存。
                                    if (needsModeSwitchConfirmation(settings, AutoFileMode.AI)) {
                                        confirmModeSwitch = true
                                    } else {
                                        saveSettings(settings.copy(mode = AutoFileMode.AI))
                                    }
                                }
                            },
                            label = { Text(stringResource(R.string.r22_autofile_mode_select)) },
                        )
                    },
                ) {}
                formItem(
                    headlineContent = { Text(stringResource(R.string.r22_autofile_mode_local)) },
                    supportingContent = { Text(stringResource(R.string.r22_autofile_mode_local_desc)) },
                    trailingContent = {
                        FilterChip(
                            selected = settings.mode == AutoFileMode.LOCAL,
                            onClick = { if (settings.mode != AutoFileMode.LOCAL) saveSettings(settings.copy(mode = AutoFileMode.LOCAL)) },
                            label = { Text(stringResource(R.string.r22_autofile_mode_select)) },
                        )
                    },
                ) {}
                item(headlineContent = {
                    Text(
                        text = stringResource(if (settings.mode == AutoFileMode.AI)
                            R.string.r22_autofile_mode_ai_note else R.string.r22_autofile_mode_local_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                })
            }

            CardGroup(title = { Text(stringResource(R.string.r21_autofile_section)) }) {
                switchItem(
                    checked = settings.enabled,
                    // 运行中照样能关：中途叫停是预期动作，开关不跟着忙态锁死
                    onCheckedChange = { checked -> saveSettings(settings.copy(enabled = checked)) },
                    supportingContent = { Text(stringResource(R.string.r21_autofile_enable_desc)) },
                    headlineContent = { Text(stringResource(R.string.r21_autofile_enable)) },
                )
                switchItem(
                    checked = settings.collapseFamilies,
                    // 折叠只是显示口径，和 AI 开关互不影响
                    onCheckedChange = { checked -> saveSettings(settings.copy(collapseFamilies = checked)) },
                    supportingContent = { Text(stringResource(R.string.r21_autofile_collapse_desc)) },
                    headlineContent = { Text(stringResource(R.string.r21_autofile_collapse)) },
                )
                formItem(
                    headlineContent = { Text(stringResource(R.string.r21_autofile_idle_title)) },
                    trailingContent = {
                        CardGroupValue(
                            text = stringResource(
                                R.string.r21_autofile_hours,
                                idleHoursAt(idleDraft.roundToInt()),
                            )
                        )
                    },
                ) {
                    Slider(
                        value = idleDraft,
                        onValueChange = { idleDraft = it },
                        onValueChangeFinished = {
                            val hours = idleHoursAt(idleDraft.roundToInt())
                            if (hours != settings.idleHours) saveSettings(settings.copy(idleHours = hours))
                        },
                        valueRange = 0f..idleStepCount().toFloat(),
                        steps = (idleStepCount() - 1).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 后台调度状态：说明「谁在跑、什么时候跑」，明确不承诺精确时刻
            Text(
                text = if (settings.enabled) {
                    stringResource(R.string.r22_autofile_background_on)
                } else {
                    stringResource(R.string.r22_autofile_background_off)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    if (needsRunConfirmation(settings)) confirmRun = true else organize()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.r21_autofile_run))
            }

            if (busy) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContainedLoadingIndicator(modifier = Modifier.size(18.dp))
                    Column {
                        Text(stringResource(R.string.r21_autofile_running), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.r21_autofile_running_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val feedback = localError ?: state.error
            if (feedback != null) {
                // 报错（含「上一批被中断」「已取消」）就地给出撤销，用户不用自己找入口
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(feedback, style = MaterialTheme.typography.bodySmall)
                        if (undoOffered(state)) {
                            TextButton(onClick = { undo() }) {
                                Text(stringResource(R.string.r21_autofile_undo))
                            }
                        }
                    }
                }
            }

            if (feedback == null && undoOffered(state)) {
                OutlinedButton(
                    onClick = { undo() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.r21_autofile_undo))
                }
            }

            if (!busy && state.moved > 0) {
                Text(
                    text = stringResource(R.string.r21_autofile_done, state.moved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // ---- 历史批次 ----
            Text(
                text = stringResource(R.string.r22_autofile_history_title),
                style = MaterialTheme.typography.titleMedium,
            )
            when {
                !historyLoaded -> Text(
                    text = stringResource(R.string.r22_autofile_history_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                history.isEmpty() -> Text(
                    text = stringResource(R.string.r22_autofile_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> history.forEach { batch ->
                    val modeLabel = if (batch.modeOrDefault == AutoFileMode.LOCAL) {
                        stringResource(R.string.r22_autofile_mode_local_short)
                    } else {
                        stringResource(R.string.r22_autofile_mode_ai_short)
                    }
                    val statusLabel = batchStatusLabel(batch.status)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            perform {
                                detail = controller.details(assistantId, batch.id)
                                if (detail == null) localError = "这批记录已经不在了"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(formatTime(batch.createdAt), style = MaterialTheme.typography.bodyMedium)
                                Text("· $modeLabel", style = MaterialTheme.typography.bodySmall)
                                Text("· $statusLabel", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                text = stringResource(
                                    R.string.r22_autofile_history_counts,
                                    batch.movedCount,
                                    batch.failedCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (batch.isUndoable()) {
                                Text(
                                    text = stringResource(R.string.r22_autofile_history_undoable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.r21_autofile_close))
            }
        }
    }

    // ---- 批次详情 ----
    val currentDetail = detail
    if (currentDetail != null) {
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(stringResource(R.string.r22_autofile_detail_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = formatTime(currentDetail.batch.createdAt) + " · " +
                            batchStatusLabel(currentDetail.batch.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (currentDetail.batch.moves.isEmpty()) {
                        Text(
                            text = stringResource(R.string.r22_autofile_detail_empty),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    currentDetail.batch.moves.forEach { move ->
                        val conversationId = runCatching { Uuid.parse(move.conversationId) }.getOrNull()
                        val title = currentDetail.titles[conversationId]
                            ?: move.conversationId.take(8)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(title, style = MaterialTheme.typography.bodyMedium)
                                if (onOpenConversation != null && conversationId != null) {
                                    TextButton(onClick = { detail = null; onOpenConversation(conversationId) }) {
                                        Text(stringResource(R.string.r22_autofile_detail_open))
                                    }
                                }
                            }
                            Text(
                                text = stringResource(
                                    R.string.r22_autofile_detail_folder,
                                    move.folderName ?: "—",
                                    moveStatusLabel(move.status),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val reason = move.reason
                            if (!reason.isNullOrBlank()) {
                                Text(
                                    text = stringResource(R.string.r22_autofile_detail_reason, reason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val moveError = move.error
                            if (!moveError.isNullOrBlank()) {
                                Text(
                                    text = moveError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detail = null }) {
                    Text(stringResource(R.string.r21_autofile_close))
                }
            },
        )
    }

    if (confirmModeSwitch) {
        AlertDialog(
            onDismissRequest = { confirmModeSwitch = false },
            title = { Text(stringResource(R.string.r21_autofile_confirm_title)) },
            text = { Text(stringResource(R.string.r21_autofile_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmModeSwitch = false
                    saveSettings(settings.copy(mode = AutoFileMode.AI))
                }) {
                    Text(stringResource(R.string.r21_autofile_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmModeSwitch = false }) {
                    Text(stringResource(R.string.r21_autofile_cancel))
                }
            },
        )
    }

    if (confirmRun) {
        AlertDialog(
            onDismissRequest = { confirmRun = false },
            title = { Text(stringResource(R.string.r21_autofile_confirm_title)) },
            text = { Text(stringResource(R.string.r21_autofile_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { confirmRun = false; organize() }) {
                    Text(stringResource(R.string.r21_autofile_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRun = false }) {
                    Text(stringResource(R.string.r21_autofile_cancel))
                }
            },
        )
    }
}

@Composable
private fun batchStatusLabel(status: String): String = stringResource(
    when (status) {
        AutoFileBatchStatus.DONE -> R.string.r22_autofile_status_done
        AutoFileBatchStatus.PARTIAL -> R.string.r22_autofile_status_partial
        AutoFileBatchStatus.INTERRUPTED -> R.string.r22_autofile_status_interrupted
        AutoFileBatchStatus.UNDONE -> R.string.r22_autofile_status_undone
        else -> R.string.r22_autofile_status_planned
    }
)

@Composable
private fun moveStatusLabel(status: String): String = stringResource(
    when (status) {
        AutoFileMoveStatus.APPLIED -> R.string.r22_autofile_move_applied
        AutoFileMoveStatus.SKIPPED -> R.string.r22_autofile_move_skipped
        AutoFileMoveStatus.FAILED -> R.string.r22_autofile_move_failed
        AutoFileMoveStatus.UNDONE -> R.string.r22_autofile_move_undone
        else -> R.string.r22_autofile_move_planned
    }
)

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
