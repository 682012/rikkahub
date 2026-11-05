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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
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

/** 自动归库没开时，「立即整理」要先确认一次会把摘要发出去；开着就已经默认同意了。 */
internal fun needsRunConfirmation(settings: ConversationAutoFileSettings): Boolean = !settings.enabled

/** 撤销只看有没有可撤销的批：上一批被中断（带 error）照样要能撤，不因为报错就把入口藏掉。 */
internal fun undoOffered(state: ConversationAutoFileState): Boolean = state.canUndo

/**
 * 「自动归库」设置面板（底部 Sheet）。
 *
 * 只读 [ConversationAutoFileController] 的状态、只调它的三个 suspend 接口；自己不含业务状态，
 * 也不在 Compose 主线程上做文件 IO（设置写盘走 [Dispatchers.IO]，结果由后端状态回写）。
 *
 * 两条硬约束：
 * - 摘要外发的告知必须排在开关**前面**，用户先看到「发什么、花额度、默认关、可撤销」再决定。
 * - 整理运行中不锁开关，「关掉自动归库」是用户中途叫停的唯一手段；被中断的批要能一路撤销。
 */
@Composable
fun ConversationAutoFilePanel(assistantId: Uuid, onDismiss: () -> Unit) {
    val controller: ConversationAutoFileController = koinInject()
    val scope = rememberCoroutineScope()
    val state by remember(assistantId, controller) { controller.observe(assistantId) }.collectAsState()
    val settings = state.settings
    var confirmRun by remember(assistantId) { mutableStateOf(false) }
    var localError by remember(assistantId) { mutableStateOf<String?>(null) }
    val busy = state.running
    // 拖动时只改本地草稿，松手才落盘，避免一路拖一路写设置
    var idleDraft by remember(settings.idleHours) { mutableFloatStateOf(idleIndexFor(settings.idleHours).toFloat()) }

    // 所有动作统一走这里：IO 线程执行、不吞取消、异常落到面板状态而不是崩掉界面。
    // （不叫 run：避免和标准库的 run 撞名，读起来也少一层歧义）
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
    fun organize() = perform { controller.organizeNow(assistantId) }
    fun undo() = perform { controller.undoLast(assistantId) }

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
                // 报错（含「上一批被中断」）就地给出撤销，用户不用自己找入口
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

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.r21_autofile_close))
            }
        }
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
