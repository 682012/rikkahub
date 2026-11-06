package me.rerere.rikkahub.ui.components.setting

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.deviceprivilege.GrantResult
import me.rerere.rikkahub.data.deviceprivilege.GrantRoute
import me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeController
import me.rerere.rikkahub.data.deviceprivilege.HighPrivilegePermissionRow
import me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeSnapshot
import me.rerere.rikkahub.data.deviceprivilege.PermissionState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

/**
 * 高级权限与设备能力。
 *
 * 口径：
 * - 每行状态直接来自 [HighPrivilegeSnapshot]，按 [PermissionState] 渲染，不在 UI 里自己猜；
 * - 授予/撤销逐项显示 [GrantResult]：`granted` 是 PackageManager 回读，退出码 0 但没生效会照实写；
 * - Shizuku 只有 shell 级：不 root、不是系统签名应用、读不到其它 App 私有目录；
 * - 自动化默认关、写操作单独关、助手必须用户明确选，`null` 一律当"不对任何助手开放"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighPrivilegeSheet(
    refreshKey: Int,
    onDismiss: () -> Unit,
    controller: HighPrivilegeController? = null,
) {
    // 控制器需要 WorkspacePrivilegeManager，所以只能由 DI 提供；本页不自己 new。
    val realController = controller ?: koinInject<HighPrivilegeController>()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        HighPrivilegeContent(refreshKey = refreshKey, controller = realController)
    }
}

/** 单独的一屏内容：放进 bottomsheet 或独立页面都能用。 */
@Composable
fun HighPrivilegeContent(
    refreshKey: Int,
    controller: HighPrivilegeController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val assistants = LocalSettings.current.assistants

    var snapshot by remember { mutableStateOf<HighPrivilegeSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var shizukuBusy by remember { mutableStateOf(false) }
    var shizukuError by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var working by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<GrantResult>>(emptyList()) }
    var resultsRevoke by remember { mutableStateOf(false) }
    var automationBusy by remember { mutableStateOf(false) }
    var copiedId by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    // 组合里不做 IO：snapshot 走 IO 线程，结果回主线程再落状态。
    LaunchedEffect(refreshKey, reload) {
        loading = true
        loadError = null
        val outcome = runCatching { withContext(Dispatchers.IO) { controller.snapshot() } }
        snapshot = outcome.getOrNull()
        loadError = outcome.exceptionOrNull()?.message
        loading = false
    }

    val adbRows = snapshot?.permissions?.filter { it.grantRoute == GrantRoute.ADB_SHIZUKU }.orEmpty()
    val systemRows = snapshot?.permissions?.filter { it.grantRoute == GrantRoute.SYSTEM_SETTINGS }.orEmpty()
    val boundRows = snapshot?.permissions?.filter { it.grantRoute == GrantRoute.BOUND_SERVICE }.orEmpty()
    val runtimeRows = snapshot?.permissions?.filter { it.grantRoute == GrantRoute.RUNTIME }.orEmpty()
    val authorizedAssistantId = snapshot?.authorizedAssistantId
    val selectedAssistantValid = authorizedAssistantId?.let { id ->
        assistants.any { it.id.toString() == id }
    } == true
    val adbCommands = remember(snapshot) {
        adbRows.associate { row -> row.id to controller.adbCommands(setOf(row.id)) }
    }

    fun refreshSnapshot() {
        reload++
    }

    fun refreshAfterAction() {
        scope.launch {
            val fresh = runCatching { withContext(Dispatchers.IO) { controller.snapshot() } }
            fresh.getOrNull()?.let { snapshot = it }
            fresh.exceptionOrNull()?.let { actionError = it.message }
        }
    }

    fun runDevelopmentAction(revoke: Boolean) {
        if (selectedIds.isEmpty() || working) return
        val ids = selectedIds
        working = true
        actionError = null
        results = emptyList()
        resultsRevoke = revoke
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    if (revoke) controller.revokeDevelopmentPermissions(ids)
                    else controller.grantDevelopmentPermissions(ids)
                }
            }
            outcome.onSuccess { list ->
                results = list
                // 先摆出逐项结果，再用 snapshot 的真实状态覆盖勾选行。
                val fresh = runCatching { withContext(Dispatchers.IO) { controller.snapshot() } }
                fresh.getOrNull()?.let { snapshot = it }
                fresh.exceptionOrNull()?.let { actionError = it.message }
            }.onFailure { actionError = it.message }
            working = false
        }
    }

    fun saveAutomation(enabled: Boolean, allowWrites: Boolean, assistantId: String?) {
        if (automationBusy) return
        // Serialize saves so a late result cannot re-enable a switch the user just disabled.
        automationBusy = true
        if (!enabled) runCatching { controller.cancelAutomationCommands() }
        actionError = null
        scope.launch {
            try {
                snapshot = withContext(Dispatchers.IO) {
                    controller.configureAutomation(
                        enabled = enabled,
                        allowWrites = allowWrites,
                        assistantId = assistantId,
                    )
                    controller.snapshot()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                actionError = error.message
                refreshAfterAction()
            } finally {
                automationBusy = false
            }
        }
    }

    if (pickerOpen) {
        AssistantPickerDialog(
            assistants = assistants,
            currentId = authorizedAssistantId,
            onDismiss = { pickerOpen = false },
            onPicked = { picked ->
                pickerOpen = false
                saveAutomation(
                    enabled = snapshot?.automationEnabled == true,
                    allowWrites = snapshot?.writeEnabled == true,
                    assistantId = picked,
                )
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.m1_priv_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.m1_priv_shell_boundary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (loading && snapshot == null) {
            item {
                Text(
                    text = stringResource(R.string.m1_priv_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        loadError?.let { message ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.m1_priv_load_failed, message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { refreshSnapshot() }) {
                        Text(stringResource(R.string.m1_priv_retry))
                    }
                }
            }
        }

        // ① Shizuku 通道
        item {
            CardGroup(title = { Text(stringResource(R.string.m1_priv_shizuku_group)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.m1_priv_shizuku_status)) },
                    supportingContent = {
                        Text(
                            text = when {
                                snapshot == null -> stringResource(R.string.m1_priv_loading)
                                snapshot!!.shizukuStatus.isNotBlank() -> snapshot!!.shizukuStatus
                                snapshot!!.shizukuReady -> stringResource(R.string.m1_priv_shizuku_state_authorized)
                                else -> stringResource(R.string.m1_priv_shizuku_state_missing)
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            text = if (snapshot?.shizukuReady == true) {
                                stringResource(R.string.m1_priv_dev_granted)
                            } else {
                                stringResource(R.string.m1_priv_dev_denied)
                            },
                            color = if (snapshot?.shizukuReady == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { refreshSnapshot() },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.m1_priv_shizuku_authorize)) },
                    supportingContent = { Text(stringResource(R.string.m1_priv_shizuku_authorize_desc)) },
                    trailingContent = {
                        Text(
                            text = if (shizukuBusy) {
                                stringResource(R.string.m1_priv_shizuku_authorizing)
                            } else if (snapshot?.shizukuReady == true) {
                                stringResource(R.string.m1_priv_dev_granted)
                            } else {
                                stringResource(R.string.m1_priv_entry_open)
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        if (!shizukuBusy) {
                            shizukuBusy = true
                            shizukuError = null
                            scope.launch {
                                val outcome = runCatching { controller.authorizeShizuku() }
                                outcome.onSuccess { snapshot = it }
                                    .onFailure { shizukuError = it.message }
                                shizukuBusy = false
                            }
                        }
                    },
                )
                shizukuError?.let { message ->
                    item(headlineContent = { Text(stringResource(R.string.m1_priv_shizuku_failed, message)) })
                }
                item(headlineContent = { Text(stringResource(R.string.m1_priv_shizuku_no_root)) })
            }
        }

        // ①b 开发级权限：真实状态 + 勾选批量授予/撤销 + 逐项结果
        item {
            CardGroup(title = { Text(stringResource(R.string.m1_priv_dev_group)) }) {
                item(headlineContent = { Text(stringResource(R.string.m1_priv_dev_desc)) })
                if (adbRows.isEmpty()) {
                    item(headlineContent = { Text(stringResource(R.string.m1_priv_unavailable)) })
                }
                adbRows.forEach { row ->
                    val stateRes = row.state.labelRes()
                    item(
                        headlineContent = { Text(row.title.ifBlank { row.id }) },
                        supportingContent = {
                            val stateLabel = stringResource(stateRes)
                            Text(
                                text = listOfNotNull(stateLabel, row.detail.takeIf { it.isNotBlank() })
                                    .joinToString(" · ")
                            )
                        },
                        trailingContent = {
                            Checkbox(
                                checked = row.id in selectedIds,
                                enabled = row.state != PermissionState.SYSTEM_ONLY,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + row.id else selectedIds - row.id
                                },
                            )
                        },
                        onClick = {
                            if (row.state != PermissionState.SYSTEM_ONLY) {
                                selectedIds = if (row.id in selectedIds) selectedIds - row.id
                                else selectedIds + row.id
                            }
                        },
                    )
                }
                formItem(headlineContent = { Text(stringResource(R.string.m1_priv_dev_select_hint)) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { runDevelopmentAction(revoke = false) },
                            enabled = !working && selectedIds.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.m1_priv_dev_grant_selected, selectedIds.size))
                        }
                        OutlinedButton(
                            onClick = { runDevelopmentAction(revoke = true) },
                            enabled = !working && selectedIds.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.m1_priv_dev_revoke_selected, selectedIds.size))
                        }
                    }
                    if (working) {
                        Text(
                            text = stringResource(R.string.m1_priv_dev_working),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    actionError?.let { message ->
                        Text(
                            text = stringResource(R.string.m1_priv_action_failed, message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    results.forEach { result ->
                        Text(
                            text = resultLine(result, resultsRevoke),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.error == null && result.granted != resultsRevoke) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ①c ADB 命令：只有用户点击才复制，包名取实际安装包
        item {
            CardGroup(title = { Text(stringResource(R.string.m1_priv_adb_group)) }) {
                item(headlineContent = { Text(stringResource(R.string.m1_priv_adb_desc, context.packageName)) })
                if (adbRows.isEmpty()) {
                    item(headlineContent = { Text(stringResource(R.string.m1_priv_adb_empty)) })
                }
                adbRows.forEach { row ->
                    val commands = adbCommands[row.id].orEmpty()
                    item(
                        headlineContent = { Text(row.id) },
                        supportingContent = {
                            val emptyLabel = stringResource(R.string.m1_priv_adb_empty)
                            Text(
                                text = commands.joinToString("\n").ifBlank { emptyLabel },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            Text(
                                text = if (copiedId == row.id) stringResource(R.string.m1_priv_adb_copied)
                                else stringResource(R.string.m1_priv_adb_copy),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            val command = commands.joinToString("\n")
                            if (command.isNotBlank()) {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("adb", command)))
                                    copiedId = row.id
                                }
                            }
                        },
                    )
                }
            }
        }

        // ② 系统特殊访问：按 snapshot 给的 settingsAction 打开系统页，回来 ON_RESUME 重新 snapshot
        item {
            CardGroup(title = { Text(stringResource(R.string.m1_priv_special_group)) }) {
                item(headlineContent = { Text(stringResource(R.string.m1_priv_special_desc)) })
                if (systemRows.isEmpty()) {
                    item(headlineContent = { Text(stringResource(R.string.m1_priv_unavailable)) })
                }
                systemRows.forEach { row ->
                    item(
                        onClick = { openSystemPage(context, row.systemIntent()) },
                        headlineContent = { Text(row.title.ifBlank { row.id }) },
                        supportingContent = {
                            val label = stringResource(row.state.labelRes())
                            Text(text = listOfNotNull(label, row.detail.takeIf { it.isNotBlank() }).joinToString(" · "))
                        },
                        trailingContent = {
                            Text(
                                text = stringResource(row.state.labelRes()),
                                color = if (row.granted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }

        // ②b 本应用组件绑定（无障碍 / 通知监听等）：只能用户手动开，不静默打开
        if (boundRows.isNotEmpty()) {
            item {
                CardGroup(title = { Text(stringResource(R.string.m1_priv_bound_group)) }) {
                    item(headlineContent = { Text(stringResource(R.string.m1_priv_bound_desc)) })
                    boundRows.forEach { row ->
                        item(
                            onClick = { openSystemPage(context, row.systemIntent()) },
                            headlineContent = { Text(row.title.ifBlank { row.id }) },
                            supportingContent = {
                                val label = stringResource(row.state.labelRes())
                                val manual = stringResource(R.string.m1_priv_special_manual)
                                Text(
                                    text = listOfNotNull(label, row.detail.takeIf { it.isNotBlank() }, manual)
                                        .joinToString(" · ")
                                )
                            },
                            trailingContent = {
                                Text(
                                    text = stringResource(row.state.labelRes()),
                                    color = if (row.granted) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }

        // ②c 运行时权限：只显示真实状态，弹窗由系统决定
        if (runtimeRows.isNotEmpty()) {
            item {
                CardGroup(title = { Text(stringResource(R.string.m1_priv_runtime_group)) }) {
                    runtimeRows.forEach { row ->
                        item(
                            onClick = { openSystemPage(context, row.systemIntent()) },
                            headlineContent = { Text(row.title.ifBlank { row.id }) },
                            supportingContent = {
                                val label = stringResource(row.state.labelRes())
                                Text(text = listOfNotNull(label, row.detail.takeIf { it.isNotBlank() }).joinToString(" · "))
                            },
                            trailingContent = {
                                Text(
                                    text = stringResource(row.state.labelRes()),
                                    color = if (row.granted) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }

        // ③ AI 自动化控制
        item {
            CardGroup(title = { Text(stringResource(R.string.m1_priv_auto_group)) }) {
                switchItem(
                    checked = snapshot?.automationEnabled == true,
                    enabled = snapshot != null && !loading && !automationBusy,
                    onCheckedChange = { checked ->
                        if (checked && !selectedAssistantValid) {
                            actionError = context.getString(R.string.m1_priv_auto_need_assistant)
                        } else {
                            saveAutomation(
                                enabled = checked,
                                allowWrites = snapshot?.writeEnabled == true,
                                assistantId = authorizedAssistantId,
                            )
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.m1_priv_auto_master)) },
                    supportingContent = { Text(stringResource(R.string.m1_priv_auto_master_desc)) },
                )
                switchItem(
                    checked = snapshot?.writeEnabled == true,
                    enabled = snapshot?.automationEnabled == true && !loading && !automationBusy,
                    onCheckedChange = { checked ->
                        saveAutomation(
                            enabled = snapshot?.automationEnabled == true,
                            allowWrites = checked,
                            assistantId = authorizedAssistantId,
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.m1_priv_auto_writes)) },
                    supportingContent = { Text(stringResource(R.string.m1_priv_auto_writes_desc)) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.m1_priv_auto_assistant)) },
                    supportingContent = {
                        Text(
                            text = when {
                                authorizedAssistantId == null ->
                                    stringResource(R.string.m1_priv_auto_assistant_desc)
                                !selectedAssistantValid ->
                                    stringResource(R.string.m1_priv_auto_assistant_invalid)
                                else -> assistants.firstOrNull { it.id.toString() == authorizedAssistantId }?.name
                                    ?: stringResource(R.string.m1_priv_auto_assistant_invalid)
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            text = stringResource(R.string.m1_priv_auto_pick),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { if (!automationBusy && !loading) pickerOpen = true },
                )
                item(headlineContent = { Text(stringResource(R.string.m1_priv_auto_scope)) })
                actionError?.let { message ->
                    item(headlineContent = { Text(stringResource(R.string.m1_priv_action_failed, message)) })
                }
            }
        }

        // snapshot 自带的补充说明：OEM 限制、Play 约束、持久化错误
        if (!snapshot?.notes.isNullOrEmpty()) {
            item {
                CardGroup(title = { Text(stringResource(R.string.m1_priv_notes_group)) }) {
                    snapshot!!.notes.forEach { note ->
                        item(headlineContent = { Text(note) })
                    }
                }
            }
        }

        // ④ 可用功能：只读状态与入口说明（真正的执行在主代理接的 AI 工具里）
        item {
            CardGroup(title = { Text(stringResource(R.string.m1_priv_cap_group)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.m1_priv_cap_settings)) },
                    supportingContent = { Text(stringResource(R.string.m1_priv_cap_settings_desc)) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.m1_priv_cap_diag)) },
                    supportingContent = { Text(stringResource(R.string.m1_priv_cap_diag_desc)) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.m1_priv_cap_shizuku)) },
                    supportingContent = { Text(stringResource(R.string.m1_priv_cap_shizuku_desc)) },
                )
            }
        }
    }
}

private fun PermissionState.labelRes(): Int = when (this) {
    PermissionState.GRANTED -> R.string.m1_priv_dev_granted
    PermissionState.DENIED -> R.string.m1_priv_dev_denied
    PermissionState.UNAVAILABLE -> R.string.m1_priv_unavailable
    PermissionState.SYSTEM_ONLY -> R.string.m1_priv_state_system_only
}

/** 逐项结果按操作方向与真实回读判断，撤销成功时 granted 应为 false。 */
@Composable
private fun resultLine(result: GrantResult, revoke: Boolean): String {
    val status = stringResource(
        when {
            result.error == null && result.granted != revoke -> R.string.m1_priv_dev_result_ok
            result.executed -> R.string.m1_priv_dev_result_no_effect
            else -> R.string.m1_priv_dev_result_fail
        }
    )
    val name = result.permission.substringAfterLast('.').ifBlank { result.id }
    val error = result.error?.takeIf { it.isNotBlank() }
    return buildString {
        append(status).append(" · ").append(name)
        error?.let { append(" — ").append(it) }
    }
}

/** settingsAction / settingsData 都来自 snapshot，UI 只负责发出 Intent。 */
private fun HighPrivilegePermissionRow.systemIntent(): Intent {
    val action = settingsAction ?: android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    return Intent(action).apply {
        settingsData?.takeIf { it.isNotBlank() }?.let { data = Uri.parse(it) }
    }
}

/** 打开系统设置页；OEM 没有这个 Activity 时退到应用详情页，不假装已授权。 */
private fun openSystemPage(context: Context, intent: Intent) {
    val fallback = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )
    if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
        runCatching { context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
private fun AssistantPickerDialog(
    assistants: List<me.rerere.rikkahub.data.model.Assistant>,
    currentId: String?,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.m1_priv_auto_assistant)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.m1_priv_auto_assistant_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
                assistants.forEach { assistant ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = assistant.id.toString() == currentId,
                            onClick = { onPicked(assistant.id.toString()) },
                        )
                        Text(assistant.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.m1_priv_dismiss)) }
        },
    )
}
