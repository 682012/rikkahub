package me.rerere.rikkahub.ui.components.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.automation.AutomationRecipe
import me.rerere.rikkahub.automation.AutomationRecipeRules
import me.rerere.rikkahub.automation.AutomationPendingBlock
import me.rerere.rikkahub.automation.AutomationRun
import me.rerere.rikkahub.automation.AutomationRunStatus
import me.rerere.rikkahub.automation.AutomationSaveResult
import me.rerere.rikkahub.automation.AutomationController
import me.rerere.rikkahub.data.devicecontrol.DeviceControlBridge
import me.rerere.rikkahub.data.devicecontrol.DeviceControlState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import org.koin.compose.koinInject

/**
 * 设备桥 + 本地自动化的设置页组件。主代理把它挂到设置导航里即可（见 device/WIRING.md）。
 *
 * 原则：
 * - 开关默认全关；打开只写本地授权，不代用户去系统设置里勾选无障碍服务；
 * - 状态显示真实来源（系统设置是否勾选、服务是否连接），不做"看起来已开启"的假状态；
 * - 配方运行/取消都在这里能看到真实结果，包括"副作用未知"这种不好看但必须说的情况。
 */
@Composable
fun LocalAutomationSettings(
    modifier: Modifier = Modifier,
    bridge: DeviceControlBridge = koinInject(),
    controller: AutomationController = koinInject(),
) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val recipes by controller.recipes.collectAsStateWithLifecycle()
    val runs by controller.runs.collectAsStateWithLifecycle()
    val activeRun by controller.activeRun.collectAsStateWithLifecycle()
    val pendingBlocks by controller.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var packageInput by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var stepsText by remember { mutableStateOf("") }
    var intervalText by remember { mutableStateOf("0") }
    var message by remember { mutableStateOf<String?>(null) }
    var toolsCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        bridge.refresh()
        controller.initialize()
        toolsCount = controller.tools().size
    }

    fun grantsUpdate(block: (me.rerere.rikkahub.data.devicecontrol.DeviceControlGrants) -> me.rerere.rikkahub.data.devicecontrol.DeviceControlGrants) {
        scope.launch { bridge.preferences.update(block) }
    }

    fun loadRecipe(recipe: AutomationRecipe) {
        editingId = recipe.id
        name = recipe.name
        stepsText = AutomationRecipeRules.exportJson(recipe)
        intervalText = recipe.intervalMinutes.toString()
        message = null
    }

    fun saveRecipe() {
        val id = editingId ?: controller.newRecipe().id
        val interval = intervalText.trim().toIntOrNull() ?: 0
        when (val parsed = AutomationRecipeRules.parse(stepsText, id, fallbackName = name, intervalMinutes = interval)) {
            is AutomationRecipeRules.ParseResult.Invalid -> message = parsed.message
            is AutomationRecipeRules.ParseResult.Ok -> scope.launch {
                val candidate = parsed.recipe.copy(name = name)
                when (val result = controller.save(candidate)) {
                    is AutomationSaveResult.Invalid -> message = result.message
                    is AutomationSaveResult.Saved -> {
                        message = null
                        editingId = result.recipe.id
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.r22_device_risk),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        // 服务状态
        CardGroup(title = { Text(stringResource(R.string.r22_device_status_title)) }) {
            item(
                headlineContent = { Text(stringResource(R.string.r22_device_status_enabled_system)) },
                supportingContent = { Text(stringResource(if (state.serviceEnabledInSystem) R.string.r22_device_note_ok else R.string.r22_device_note_service_not_enabled)) },
                trailingContent = { CardGroupValue(if (state.serviceEnabledInSystem) "✓" else "—") },
            )
            item(
                headlineContent = { Text(stringResource(R.string.r22_device_status_connected)) },
                supportingContent = { Text(stringResource(if (state.serviceConnected) R.string.r22_device_note_ok else R.string.r22_device_note_service_disconnected)) },
                trailingContent = { CardGroupValue(if (state.serviceConnected) "✓" else "—") },
            )
            item(
                headlineContent = { Text(stringResource(R.string.r22_device_status_current_app)) },
                supportingContent = { Text(state.currentPackage ?: "—") },
            )
            formItem {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(noteRes(state)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            bridge.openAccessibilitySettings()
                        },
                    ) { Text(stringResource(R.string.r22_device_open_settings)) }
                }
            }
        }

        // 授权
        CardGroup(title = { Text(stringResource(R.string.r22_device_title)) }) {
            switchItem(
                checked = state.grants.enabled,
                onCheckedChange = { enabled -> grantsUpdate { it.copy(enabled = enabled) } },
                headlineContent = { Text(stringResource(R.string.r22_device_master)) },
                supportingContent = { Text(stringResource(R.string.r22_device_master_desc)) },
            )
            switchItem(
                checked = state.grants.allowRead,
                onCheckedChange = { enabled -> grantsUpdate { it.copy(allowRead = enabled) } },
                enabled = state.grants.enabled,
                headlineContent = { Text(stringResource(R.string.r22_device_allow_read)) },
                supportingContent = { Text(stringResource(R.string.r22_device_allow_read_desc)) },
            )
            switchItem(
                checked = state.grants.allowAct,
                onCheckedChange = { enabled -> grantsUpdate { it.copy(allowAct = enabled) } },
                enabled = state.grants.enabled,
                headlineContent = { Text(stringResource(R.string.r22_device_allow_act)) },
                supportingContent = { Text(stringResource(R.string.r22_device_allow_act_desc)) },
            )
            switchItem(
                checked = state.grants.allowGlobalActions,
                onCheckedChange = { enabled -> grantsUpdate { it.copy(allowGlobalActions = enabled) } },
                enabled = state.grants.enabled,
                headlineContent = { Text(stringResource(R.string.r22_device_allow_global)) },
                supportingContent = { Text(stringResource(R.string.r22_device_allow_global_desc)) },
            )
            formItem(
                supportingContent = {
                    Text(
                        text = stringResource(R.string.r22_device_global_needs_whitelist),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) { }
        }

        // 白名单
        CardGroup(title = { Text(stringResource(R.string.r22_device_whitelist_title)) }) {
            formItem(supportingContent = { Text(stringResource(R.string.r22_device_whitelist_desc)) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = packageInput,
                        onValueChange = { packageInput = it },
                        label = { Text(stringResource(R.string.r22_device_whitelist_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val pkg = packageInput.trim()
                                if (pkg.isNotEmpty()) {
                                    grantsUpdate { it.copy(allowedPackages = it.allowedPackages + pkg) }
                                    packageInput = ""
                                }
                            },
                        ) { Text(stringResource(R.string.r22_device_add)) }
                        TextButton(
                            enabled = !state.currentPackage.isNullOrBlank(),
                            onClick = {
                                val pkg = state.currentPackage ?: return@TextButton
                                grantsUpdate { it.copy(allowedPackages = it.allowedPackages + pkg) }
                            },
                        ) { Text(stringResource(R.string.r22_device_add_current)) }
                    }
                    if (state.grants.allowedPackages.isEmpty()) {
                        Text(
                            text = stringResource(R.string.r22_device_whitelist_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.grants.allowedPackages.sorted().forEach { pkg ->
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    InputChip(
                                        selected = false,
                                        onClick = { grantsUpdate { it.copy(allowedPackages = it.allowedPackages - pkg) } },
                                        label = { Text(pkg) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = { grantsUpdate { it.copy(allowedPackages = it.allowedPackages - pkg) } },
                                    ) { Text(stringResource(R.string.r22_device_remove)) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 配方编辑
        CardGroup(title = { Text(stringResource(R.string.r22_device_automation_title)) }) {
            formItem(supportingContent = {
                Column {
                    Text(stringResource(R.string.r22_device_automation_desc))
                    Text(stringResource(R.string.r22_device_tools_available, toolsCount))
                }
            }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.r22_device_recipe_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = { stepsText = it },
                        label = { Text(stringResource(R.string.r22_device_recipe_steps)) },
                        placeholder = { Text(stringResource(R.string.r22_device_recipe_steps_hint)) },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.r22_device_interval)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveRecipe() }) { Text(stringResource(R.string.r22_device_save)) }
                        if (editingId != null) {
                            TextButton(
                                onClick = {
                                    editingId = null
                                    name = ""
                                    stepsText = ""
                                    intervalText = "0"
                                    message = null
                                },
                            ) { Text(stringResource(R.string.r22_device_cancel)) }
                        }
                    }
                    message?.let {
                        Text(
                            text = stringResource(R.string.r22_device_invalid_recipe, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = stringResource(R.string.r22_device_min_interval),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.r22_device_variables_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.r22_device_no_auto_approve),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 配方列表
        CardGroup(title = { Text(stringResource(R.string.r22_device_automation_title)) }) {
            if (recipes.isEmpty()) {
                item(headlineContent = { Text(stringResource(R.string.r22_device_no_recipes)) })
            }
            recipes.forEach { recipe ->
                formItem(
                    headlineContent = { Text(recipe.name) },
                    supportingContent = {
                        Text(stringResource(R.string.r22_device_steps_count, recipe.steps.size))
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { scope.launch { controller.runNow(recipe.id) } }) {
                                Text(stringResource(R.string.r22_device_run))
                            }
                            OutlinedButton(
                                enabled = activeRun?.recipeId == recipe.id,
                                onClick = { controller.cancelNow() },
                            ) { Text(stringResource(R.string.r22_device_cancel)) }
                            TextButton(
                                onClick = {
                                    scope.launch { controller.delete(recipe.id) }
                                    if (editingId == recipe.id) editingId = null
                                },
                            ) { Text(stringResource(R.string.r22_device_delete)) }
                            TextButton(onClick = { loadRecipe(recipe) }) {
                                Text(stringResource(R.string.r22_device_save))
                            }
                        }
                        if (recipe.intervalMinutes >= AutomationRecipeRules.MIN_INTERVAL_MINUTES) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.r22_device_scheduled_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                androidx.compose.material3.Switch(
                                    checked = recipe.enabled,
                                    onCheckedChange = { enabled -> scope.launch { controller.setScheduled(recipe.id, enabled) } },
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.r22_device_min_interval),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // 待确认的未知副作用：这是唯一能解除阻塞的入口，清历史不会清掉它
        if (pendingBlocks.isNotEmpty()) {
            CardGroup(title = { Text(stringResource(R.string.r22_device_pending_title)) }) {
                pendingBlocks.forEach { block ->
                    item(
                        headlineContent = { Text(block.recipeName.ifBlank { block.recipeId }) },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.r22_device_pending_desc) + " " + block.message,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            // 显式确认：只有用户点了这个按钮才会放行下一次运行
                            TextButton(onClick = {
                                scope.launch {
                                    controller.acknowledge(block.runId)
                                    message = null
                                }
                            }) {
                                Text(stringResource(R.string.r22_device_pending_confirm))
                            }
                        },
                    )
                }
            }
        }

        // 运行状态与历史
        CardGroup(title = { Text(stringResource(R.string.r22_device_history)) }) {
            val latest = activeRun ?: runs.firstOrNull()
            if (latest == null) {
                item(headlineContent = { Text(stringResource(R.string.r22_device_no_runs)) })
            } else {
                item(
                    headlineContent = { Text(latest.recipeName + " · " + runStatusText(latest)) },
                    supportingContent = { Text(latest.message.ifBlank { latest.steps.joinToString(" → ") { it.tool } }) },
                    trailingContent = {
                        if (latest.status == AutomationRunStatus.UNKNOWN_SIDE_EFFECT ||
                            latest.status == AutomationRunStatus.BLOCKED_UNKNOWN_SIDE_EFFECT
                        ) {
                            TextButton(onClick = { scope.launch { controller.acknowledge(latest.id) } }) {
                                Text(stringResource(R.string.r22_device_ack_unknown))
                            }
                        }
                    },
                )
            }
            runs.drop(1).take(4).forEach { run ->
                item(
                    headlineContent = { Text(run.recipeName) },
                    supportingContent = { Text(runStatusText(run) + " · " + run.steps.size.toString()) },
                )
            }
            formItem(supportingContent = {
                Text(
                    text = stringResource(R.string.r22_device_clear_history_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }) {
                TextButton(onClick = { scope.launch { controller.clearHistory() } }) {
                    Text(stringResource(R.string.r22_device_clear_history))
                }
            }
        }
    }
}

@Composable
private fun runStatusText(run: AutomationRun): String = stringResource(
    when (run.status) {
        AutomationRunStatus.RUNNING -> R.string.r22_device_status_running
        AutomationRunStatus.SUCCEEDED -> R.string.r22_device_status_succeeded
        AutomationRunStatus.FAILED -> R.string.r22_device_status_failed
        AutomationRunStatus.CANCELLED -> R.string.r22_device_status_cancelled
        AutomationRunStatus.BLOCKED_NEEDS_APPROVAL -> R.string.r22_device_status_blocked_approval
        AutomationRunStatus.BLOCKED_UNKNOWN_SIDE_EFFECT -> R.string.r22_device_status_blocked_unknown
        AutomationRunStatus.UNKNOWN_SIDE_EFFECT -> R.string.r22_device_status_unknown
        AutomationRunStatus.INVALID -> R.string.r22_device_status_invalid
        AutomationRunStatus.SKIPPED_BUSY -> R.string.r22_device_status_skipped_busy
    },
)

private fun noteRes(state: DeviceControlState): Int = when (state.note) {
    "service_not_enabled" -> R.string.r22_device_note_service_not_enabled
    "service_disconnected" -> R.string.r22_device_note_service_disconnected
    "no_whitelisted_package" -> R.string.r22_device_note_no_whitelisted_package
    "ok" -> R.string.r22_device_note_ok
    else -> R.string.r22_device_note_disabled_by_user
}
