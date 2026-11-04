package me.rerere.rikkahub.ui.components.ai

import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState

/**
 * 压缩对话历史。
 *
 * [onConfirm] 返回的是后台任务：任务跑在 ChatService 的 AppScope 里，关掉这个弹窗、切对话、切助手
 * 都不会取消它，完成后由系统通知告知结果。所以这里只负责显示进度，**不**把弹窗生命周期和任务绑在一起。
 */
@Composable
fun CompressContextDialog(
    onDismiss: () -> Unit,
    onConfirm: (additionalPrompt: String, keepRecentMessages: Int) -> Job
) {
    // Notifications are optional. Older Android versions and the OFF switch need no request.
    val notifyOnCompletion = LocalSettings.current.displaySetting.enableNotificationOnCompression
    val permissions = remember(notifyOnCompletion) {
        if (notifyOnCompletion && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(PermissionNotification.copy(required = false))
        } else emptySet<PermissionInfo>()
    }
    val notificationPermission = rememberPermissionState(permissions)
    PermissionManager(notificationPermission)
    var additionalPrompt by remember { mutableStateOf("") }
    // 默认全部压缩，请求前缀与对话一致，缓存命中最好
    var keepRecentMessages by remember { mutableIntStateOf(0) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true

    // 任务结束（成功、失败、被取消）后自动收起；本窗口关闭不会 cancel，任务照常在后台跑
    LaunchedEffect(currentJob) {
        val job = currentJob ?: return@LaunchedEffect
        job.join()
        onDismiss()
    }

    AlertDialog(
        // 关闭只是关掉进度显示，后台压缩继续
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ContainedLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.r11_compression_dialog_running))
                    }
                    Text(
                        text = stringResource(R.string.r11_compression_dialog_running_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    // Additional context input
                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_page_compress_keep_recent),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalIconButton(
                                onClick = { keepRecentMessages-- },
                                enabled = keepRecentMessages > 0,
                            ) {
                                Icon(HugeIcons.Remove01, contentDescription = "Decrease")
                            }
                            Text(
                                text = keepRecentMessages.toString(),
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 40.dp),
                            )
                            FilledTonalIconButton(onClick = { keepRecentMessages++ }) {
                                Icon(HugeIcons.Add01, contentDescription = "Increase")
                            }
                        }
                        Text(
                            text = stringResource(R.string.chat_page_compress_keep_recent_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = stringResource(R.string.r11_compression_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                // 只有用户自己发起的任务才提供取消；重复点击拿到的本来就是同一个任务
                TextButton(onClick = {
                    currentJob?.cancel()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = {
                    // Guard before invoking the callback, including two taps in the same frame.
                    if (currentJob == null) {
                        currentJob = onConfirm(additionalPrompt, keepRecentMessages)
                        if (!notificationPermission.allPermissionsGranted && notificationPermission.canRequestPermissions) {
                            // A detached notification launcher must never abort a compression task.
                            runCatching { notificationPermission.requestPermissions() }
                                .onFailure { Log.w("CompressContextDialog", "Optional notification permission unavailable", it) }
                        }
                        if (!notificationPermission.showRationaleDialog) onDismiss()
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (isLoading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.r11_compression_dialog_close))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
