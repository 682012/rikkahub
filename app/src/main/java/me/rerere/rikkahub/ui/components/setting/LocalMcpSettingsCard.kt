package me.rerere.rikkahub.ui.components.setting

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.mcpserver.LocalMcpServerManager
import me.rerere.rikkahub.data.mcpserver.LocalMcpServerStatus
import me.rerere.rikkahub.data.mcpserver.LocalMcpService
import me.rerere.rikkahub.data.mcpserver.localMcpClientConfigJson
import me.rerere.rikkahub.data.mcpserver.validateLocalMcpPort
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import org.koin.compose.koinInject

/**
 * 本机 MCP 设置卡片。主代理把它挂到设置页即可（接线见 `mcp-server/WIRING.md`）。
 *
 * 三条口径：
 * - 开关 = 用户意图，运行状态 = 真实结果，两者分开显示，失败原因如实写出来；
 * - 令牌不在页面上、通知里、日志里显示完整值，复制配置必须用户主动点；
 * - 助手必须用户在 UI 里明确选，`null` 一律当"不暴露内置会话"，不当"全部助手"。
 */
@Composable
fun LocalMcpSettingsCard(
    modifier: Modifier = Modifier,
    manager: LocalMcpServerManager = koinInject(),
    appSettingsStore: SettingsStore = koinInject(),
) {
    val settings by manager.settings.collectAsStateWithLifecycle()
    val runtime by manager.runtime.collectAsStateWithLifecycle()
    val appSettings by appSettingsStore.settingsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val assistants = appSettings.assistants
    val selectedAssistant = assistants.firstOrNull { it.id.toString() == settings.assistantId }
    var assistantMenuOpen by remember { mutableStateOf(false) }
    var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var portError by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val statusText = when (runtime.status) {
        LocalMcpServerStatus.STOPPED -> stringResource(R.string.r22_mcp_status_stopped)
        LocalMcpServerStatus.STARTING -> stringResource(R.string.r22_mcp_status_starting)
        LocalMcpServerStatus.RUNNING -> stringResource(R.string.r22_mcp_status_running)
        LocalMcpServerStatus.STOPPING -> stringResource(R.string.r22_mcp_status_stopping)
        LocalMcpServerStatus.ERROR -> stringResource(R.string.r22_mcp_status_error)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.r22_mcp_risk),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        CardGroup(title = { Text(stringResource(R.string.r22_mcp_status_title)) }) {
            item(
                headlineContent = { Text(stringResource(R.string.r22_mcp_status_label)) },
                supportingContent = {
                    val error = runtime.error
                    Text(
                        when {
                            error != null -> stringResource(R.string.r22_mcp_status_error_fmt, error)
                            runtime.isRunning -> stringResource(
                                R.string.r22_mcp_status_running_fmt,
                                runtime.endpoint ?: settings.endpoint,
                            )

                            settings.enabled -> stringResource(R.string.r22_mcp_enable_note)
                            else -> stringResource(R.string.r22_mcp_endpoint_empty)
                        }
                    )
                },
                trailingContent = { CardGroupValue(statusText) },
            )
            item(
                headlineContent = { Text(stringResource(R.string.r22_mcp_endpoint)) },
                supportingContent = {
                    Text(
                        runtime.endpoint
                            ?: if (runtime.error != null) runtime.error!! else stringResource(R.string.r22_mcp_endpoint_empty)
                    )
                },
            )
            switchItem(
                checked = settings.enabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        LocalMcpService.requestStart(context)
                    } else {
                        scope.launch {
                            manager.applySettings { it.copy(enabled = false) }
                            LocalMcpService.requestStop(context)
                        }
                    }
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.r22_mcp_enable_note))
                        // 开关是"开"但服务没在跑（启动失败 / 被系统收掉）时给一条一键重试的路：
                        // 之前只能先关再开，或者干脆以为这个功能废了。
                        if (settings.enabled && !runtime.isRunning) {
                            TextButton(onClick = { LocalMcpService.requestStart(context) }) {
                                Text(stringResource(R.string.r22_mcp_retry_start))
                            }
                        }
                    }
                },
                headlineContent = { Text(stringResource(R.string.r22_mcp_enable)) },
            )
        }

        CardGroup(title = { Text(stringResource(R.string.r22_mcp_perm_title)) }) {
            formItem(
                headlineContent = { Text(stringResource(R.string.r22_mcp_port)) },
                supportingContent = {
                    Text(
                        if (portError) stringResource(R.string.r22_mcp_port_invalid)
                        else stringResource(R.string.r22_mcp_port_hint)
                    )
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { value ->
                            if (value.all { it.isDigit() } && value.length <= 5) {
                                portText = value
                                portError = false
                            }
                        },
                        singleLine = true,
                        isError = portError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val parsed = portText.trim().toIntOrNull()
                            if (parsed == null || validateLocalMcpPort(parsed) != null) {
                                portError = true
                            } else {
                                portError = false
                                scope.launch {
                                    manager.applySettings { it.copy(port = parsed) }
                                    message = null
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.r22_mcp_port_apply)) }
                }
            }

            formItem(
                headlineContent = { Text(stringResource(R.string.r22_mcp_assistant)) },
                supportingContent = { Text(stringResource(R.string.r22_mcp_assistant_note)) },
            ) {
                if (!settings.hasAssistant) {
                    Text(
                        text = stringResource(R.string.r22_mcp_assistant_required),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Box {
                    OutlinedButton(onClick = { assistantMenuOpen = true }) {
                        Text(
                            selectedAssistant?.name?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.r22_mcp_select_assistant)
                        )
                    }
                    DropdownMenu(
                        expanded = assistantMenuOpen,
                        onDismissRequest = { assistantMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.r22_mcp_assistant_none)) },
                            onClick = {
                                assistantMenuOpen = false
                                scope.launch { manager.applySettings { it.copy(assistantId = null) } }
                            },
                        )
                        assistants.forEach { assistant ->
                            DropdownMenuItem(
                                text = { Text(assistant.name.ifBlank { assistant.id.toString() }) },
                                onClick = {
                                    assistantMenuOpen = false
                                    scope.launch {
                                        manager.applySettings { it.copy(assistantId = assistant.id.toString()) }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            switchItem(
                checked = settings.allowRead,
                onCheckedChange = { checked ->
                    scope.launch { manager.applySettings { it.copy(allowRead = checked) } }
                },
                supportingContent = { Text(stringResource(R.string.r22_mcp_allow_read_note)) },
                headlineContent = { Text(stringResource(R.string.r22_mcp_allow_read)) },
            )
            switchItem(
                checked = settings.allowMutations,
                onCheckedChange = { checked ->
                    scope.launch { manager.applySettings { it.copy(allowMutations = checked) } }
                },
                supportingContent = { Text(stringResource(R.string.r22_mcp_allow_mutations_note)) },
                headlineContent = { Text(stringResource(R.string.r22_mcp_allow_mutations)) },
            )
            switchItem(
                checked = settings.allowModelRequests,
                onCheckedChange = { checked ->
                    scope.launch { manager.applySettings { it.copy(allowModelRequests = checked) } }
                },
                supportingContent = { Text(stringResource(R.string.r22_mcp_allow_model_note)) },
                headlineContent = { Text(stringResource(R.string.r22_mcp_allow_model)) },
            )
            switchItem(
                checked = settings.allowDevice,
                onCheckedChange = { checked ->
                    scope.launch { manager.applySettings { it.copy(allowDevice = checked) } }
                },
                supportingContent = { Text(stringResource(R.string.r22_mcp_allow_device_note)) },
                headlineContent = { Text(stringResource(R.string.r22_mcp_allow_device)) },
            )
            switchItem(
                checked = settings.allowAutomation,
                onCheckedChange = { checked ->
                    scope.launch { manager.applySettings { it.copy(allowAutomation = checked) } }
                },
                supportingContent = { Text(stringResource(R.string.r22_mcp_allow_automation_note)) },
                headlineContent = { Text(stringResource(R.string.r22_mcp_allow_automation)) },
            )
        }

        CardGroup(title = { Text(stringResource(R.string.r22_mcp_token_title)) }) {
            formItem(
                headlineContent = { Text(stringResource(R.string.r22_mcp_token_masked)) },
                supportingContent = { Text(message ?: stringResource(R.string.r22_mcp_token_note)) },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            val config = localMcpClientConfigJson(settings)
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("MCP", config))
                                )
                                message = context.getString(R.string.r22_mcp_copy_done)
                            }
                        }
                    ) { Text(stringResource(R.string.r22_mcp_copy_config)) }
                    TextButton(
                        onClick = {
                            manager.settingsStore.rotateToken()
                            message = context.getString(R.string.r22_mcp_rotate_done)
                        }
                    ) { Text(stringResource(R.string.r22_mcp_rotate_token)) }
                }
            }
        }
    }
}
