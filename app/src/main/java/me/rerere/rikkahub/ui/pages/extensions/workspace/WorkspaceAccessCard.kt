package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FingerPrint
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.workspace.WorkspaceAccessSettings
import me.rerere.rikkahub.data.workspace.WorkspaceMountSpec
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeMode
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem

// ---------------------------------------------------------------------------
// UI 可测纯函数：挂载草稿校验与权限状态推导，不依赖 Compose / Context
// ---------------------------------------------------------------------------

/** Rootfs 侧默认挂载点，与“填写路径”对话框的默认值一致。 */
const val WORKSPACE_MOUNT_DEFAULT_TARGET: String = "/mnt/shared"

/** 挂载路径输入框的错误分类，文案映射交给 Composable。 */
enum class WorkspaceMountDraftError {
    SOURCE_EMPTY,
    SOURCE_NOT_PATH,
    SOURCE_RELATIVE,
    SOURCE_TOO_BROAD,
    TARGET_RELATIVE,
    TARGET_TOO_BROAD,
    TARGET_DUPLICATE,
}

/** 组件自己推导出的 Android 执行授权状态，未授权时绝不展示已提权。 */
enum class WorkspaceAndroidAccessState { UNAUTHORIZED, AUTHORIZED }

fun workspaceAndroidAccessState(settings: WorkspaceAccessSettings): WorkspaceAndroidAccessState =
    if (settings.androidShellEnabled) {
        WorkspaceAndroidAccessState.AUTHORIZED
    } else {
        WorkspaceAndroidAccessState.UNAUTHORIZED
    }

/**
 * 当前真正生效的模式。未授权时返回 null：即便 settings 里残留了 ROOT/SHIZUKU，
 * 也不能显示成已提权（真实 UID 由 statusText 负责）。
 */
fun workspaceAndroidModeBadge(settings: WorkspaceAccessSettings): WorkspacePrivilegeMode? =
    if (settings.androidShellEnabled) settings.androidPrivilege else null

/** App / Shizuku / Root 是产品名，不随语言变化。 */
fun workspaceAccessModeLabel(mode: WorkspacePrivilegeMode): String = when (mode) {
    WorkspacePrivilegeMode.APP -> "App"
    WorkspacePrivilegeMode.SHIZUKU -> "Shizuku"
    WorkspacePrivilegeMode.ROOT -> "Root"
}

/** 只有 Shizuku / Root 需要在授权前说明影响范围。 */
fun workspacePrivilegeModeRequiresConfirm(mode: WorkspacePrivilegeMode): Boolean =
    mode != WorkspacePrivilegeMode.APP

/** 目标路径规范化：去空白、合并重复 /、去掉结尾 /；留空走默认挂载点。 */
fun workspaceMountTargetOrDefault(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return WORKSPACE_MOUNT_DEFAULT_TARGET
    if (!trimmed.startsWith("/")) return trimmed
    val collapsed = trimmed.replace(Regex("/{2,}"), "/")
    return collapsed.trimEnd('/').ifEmpty { "/" }
}

/** 校验“填写路径”对话框的两个输入；null 表示可以提交。 */
fun validateWorkspaceMountDraft(
    source: String,
    target: String,
    existingTargets: List<String> = emptyList(),
): WorkspaceMountDraftError? {
    val src = source.trim()
    if (src.isEmpty()) return WorkspaceMountDraftError.SOURCE_EMPTY
    if (src.startsWith("content://") || src.startsWith("file://")) {
        return WorkspaceMountDraftError.SOURCE_NOT_PATH
    }
    if (!src.startsWith("/")) return WorkspaceMountDraftError.SOURCE_RELATIVE
    if (src.trimEnd('/').isEmpty()) return WorkspaceMountDraftError.SOURCE_TOO_BROAD
    val tgt = workspaceMountTargetOrDefault(target)
    if (!tgt.startsWith("/")) return WorkspaceMountDraftError.TARGET_RELATIVE
    if (tgt == "/" || tgt == "/mnt") return WorkspaceMountDraftError.TARGET_TOO_BROAD
    if (existingTargets.any { workspaceMountTargetOrDefault(it) == tgt }) {
        return WorkspaceMountDraftError.TARGET_DUPLICATE
    }
    return null
}

/** 一行展示 source → target，列表项和测试共用。 */
fun workspaceMountRowText(mount: WorkspaceMountSpec): String =
    "${mount.sourcePath}  →  ${mount.targetPath}"

/** 测试用的语义 tag，集中一个地方，避免各页各写一份。 */
object WorkspaceAccessTestTags {
    const val CARD = "workspace_access_card"
    const val TRUSTED_SWITCH = "workspace_access_trusted"
    const val ANDROID_PROBE = "workspace_access_android_probe"
    const val ANDROID_REVOKE = "workspace_access_android_revoke"
    const val MODE_APP = "workspace_access_mode_app"
    const val MODE_SHIZUKU = "workspace_access_mode_shizuku"
    const val MODE_ROOT = "workspace_access_mode_root"
    const val STORAGE_SETTINGS = "workspace_access_storage_settings"
    const val PICK_DIRECTORY = "workspace_access_pick_directory"
    const val ADD_PATH = "workspace_access_add_path"
    const val PATH_SOURCE = "workspace_access_path_source"
    const val PATH_TARGET = "workspace_access_path_target"
    const val PATH_CONFIRM = "workspace_access_path_confirm"
    const val MOUNT_REMOVE_CONFIRM = "workspace_access_mount_remove_confirm"
    const val MOUNT_ROW_PREFIX = "workspace_access_mount_"

    fun mode(mode: WorkspacePrivilegeMode): String = when (mode) {
        WorkspacePrivilegeMode.APP -> MODE_APP
        WorkspacePrivilegeMode.SHIZUKU -> MODE_SHIZUKU
        WorkspacePrivilegeMode.ROOT -> MODE_ROOT
    }

    fun mountRow(id: String): String = "$MOUNT_ROW_PREFIX$id"
}

// ---------------------------------------------------------------------------
// 资源一次性取出：Dialog 内不再调用 stringResource，避开本项目 Locale 上下文丢失
// ---------------------------------------------------------------------------

private class WorkspaceAccessStrings(
    val title: String,
    val trustedTitle: String,
    val trustedDesc: String,
    val trustedConfirmTitle: String,
    val trustedConfirmBody: String,
    val androidTitle: String,
    val androidUnauthorized: String,
    val androidDesc: String,
    val probe: String,
    val revoke: String,
    val confirm: String,
    val cancel: String,
    val modeConfirmTitle: Map<WorkspacePrivilegeMode, String>,
    val modeConfirmBody: Map<WorkspacePrivilegeMode, String>,
    val storageTitle: String,
    val storageGranted: String,
    val storageDenied: String,
    val storageDesc: String,
    val storageOpen: String,
    val mountsTitle: String,
    val mountsDesc: String,
    val mountsEmpty: String,
    val mountPick: String,
    val mountPickDesc: String,
    val mountAddPath: String,
    val mountAddPathDesc: String,
    val mountRemove: String,
    val mountRemoveTitle: String,
    val pathDialogTitle: String,
    val pathSource: String,
    val pathSourceHint: String,
    val pathTarget: String,
    val pathTargetHint: String,
    val pathConfirm: String,
    val errSourceEmpty: String,
    val errSourceNotPath: String,
    val errSourceRelative: String,
    val errSourceBroad: String,
    val errTargetRelative: String,
    val errTargetBroad: String,
    val errTargetDuplicate: String,
) {
    fun draftErrorText(error: WorkspaceMountDraftError): String = when (error) {
        WorkspaceMountDraftError.SOURCE_EMPTY -> errSourceEmpty
        WorkspaceMountDraftError.SOURCE_NOT_PATH -> errSourceNotPath
        WorkspaceMountDraftError.SOURCE_RELATIVE -> errSourceRelative
        WorkspaceMountDraftError.SOURCE_TOO_BROAD -> errSourceBroad
        WorkspaceMountDraftError.TARGET_RELATIVE -> errTargetRelative
        WorkspaceMountDraftError.TARGET_TOO_BROAD -> errTargetBroad
        WorkspaceMountDraftError.TARGET_DUPLICATE -> errTargetDuplicate
    }
}

@Composable
private fun workspaceAccessStrings(): WorkspaceAccessStrings = WorkspaceAccessStrings(
    title = stringResource(R.string.workspace_access_title),
    trustedTitle = stringResource(R.string.workspace_access_trusted_title),
    trustedDesc = stringResource(R.string.workspace_access_trusted_desc),
    trustedConfirmTitle = stringResource(R.string.workspace_access_trusted_confirm_title),
    trustedConfirmBody = stringResource(R.string.workspace_access_trusted_confirm_body),
    androidTitle = stringResource(R.string.workspace_access_android_title),
    androidUnauthorized = stringResource(R.string.workspace_access_android_unauthorized),
    androidDesc = stringResource(R.string.workspace_access_android_desc),
    probe = stringResource(R.string.workspace_access_android_probe),
    revoke = stringResource(R.string.workspace_access_android_revoke),
    confirm = stringResource(R.string.common_confirm),
    cancel = stringResource(R.string.common_cancel),
    modeConfirmTitle = mapOf(
        WorkspacePrivilegeMode.APP to stringResource(R.string.workspace_access_android_title),
        WorkspacePrivilegeMode.SHIZUKU to stringResource(R.string.workspace_access_android_confirm_shizuku_title),
        WorkspacePrivilegeMode.ROOT to stringResource(R.string.workspace_access_android_confirm_root_title),
    ),
    modeConfirmBody = mapOf(
        WorkspacePrivilegeMode.APP to stringResource(R.string.workspace_access_android_desc),
        WorkspacePrivilegeMode.SHIZUKU to stringResource(R.string.workspace_access_android_confirm_shizuku_body),
        WorkspacePrivilegeMode.ROOT to stringResource(R.string.workspace_access_android_confirm_root_body),
    ),
    storageTitle = stringResource(R.string.workspace_access_storage_title),
    storageGranted = stringResource(R.string.workspace_access_storage_granted),
    storageDenied = stringResource(R.string.workspace_access_storage_denied),
    storageDesc = stringResource(R.string.workspace_access_storage_desc),
    storageOpen = stringResource(R.string.workspace_access_storage_open),
    mountsTitle = stringResource(R.string.workspace_access_mounts_title),
    mountsDesc = stringResource(R.string.workspace_access_mounts_desc),
    mountsEmpty = stringResource(R.string.workspace_access_mounts_empty),
    mountPick = stringResource(R.string.workspace_access_mount_pick),
    mountPickDesc = stringResource(R.string.workspace_access_mount_pick_desc),
    mountAddPath = stringResource(R.string.workspace_access_mount_add_path),
    mountAddPathDesc = stringResource(R.string.workspace_access_mount_add_path_desc),
    mountRemove = stringResource(R.string.workspace_access_mount_remove),
    mountRemoveTitle = stringResource(R.string.workspace_access_mount_remove_confirm_title),
    pathDialogTitle = stringResource(R.string.workspace_access_path_dialog_title),
    pathSource = stringResource(R.string.workspace_access_path_source),
    pathSourceHint = stringResource(R.string.workspace_access_path_source_hint),
    pathTarget = stringResource(R.string.workspace_access_path_target),
    pathTargetHint = stringResource(R.string.workspace_access_path_target_hint),
    pathConfirm = stringResource(R.string.workspace_access_path_confirm),
    errSourceEmpty = stringResource(R.string.workspace_access_path_err_source_empty),
    errSourceNotPath = stringResource(R.string.workspace_access_path_err_source_not_path),
    errSourceRelative = stringResource(R.string.workspace_access_path_err_source_relative),
    errSourceBroad = stringResource(R.string.workspace_access_path_err_source_broad),
    errTargetRelative = stringResource(R.string.workspace_access_path_err_target_relative),
    errTargetBroad = stringResource(R.string.workspace_access_path_err_target_broad),
    errTargetDuplicate = stringResource(R.string.workspace_access_path_err_target_duplicate),
)

// ---------------------------------------------------------------------------
// 卡片本体：纯 UI，所有外部权限都靠用户点击触发，不做自动申请 / 自动探针
// ---------------------------------------------------------------------------

/**
 * 工作区权限与挂载设置卡片。只负责展示 [settings] 并把用户动作抛给调用方，
 * 不申请权限、不探测 root、不落库。
 *
 * @param statusText 实际执行身份文本（例如 UID 与 SELinux），由调用方按真实探测结果给。
 */
@Composable
fun WorkspaceAccessCard(
    settings: WorkspaceAccessSettings,
    storageGranted: Boolean,
    statusText: String,
    busy: Boolean,
    onAuthorize: (WorkspacePrivilegeMode) -> Unit,
    onRevoke: () -> Unit,
    onTrustedChange: (Boolean) -> Unit,
    onStorageAccess: () -> Unit,
    onPickDirectory: () -> Unit,
    onAddPath: (String, String) -> Unit,
    onRemoveMount: (String) -> Unit,
    onProbe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = workspaceAccessStrings()
    var pendingTrust by rememberSaveable { mutableStateOf(false) }
    // 用 name 存，避免 rememberSaveable 直接存 enum 的可保存性问题
    var pendingModeName by rememberSaveable { mutableStateOf<String?>(null) }
    var showPathDialog by rememberSaveable { mutableStateOf(false) }
    var sourceInput by rememberSaveable { mutableStateOf("") }
    var targetInput by rememberSaveable { mutableStateOf(WORKSPACE_MOUNT_DEFAULT_TARGET) }
    var pendingRemoveMountId by rememberSaveable { mutableStateOf<String?>(null) }

    val accessState = workspaceAndroidAccessState(settings)
    val activeMode = workspaceAndroidModeBadge(settings)
    val authorized = accessState == WorkspaceAndroidAccessState.AUTHORIZED
    val authorizedLabel = stringResource(
        R.string.workspace_access_android_authorized,
        workspaceAccessModeLabel(activeMode ?: settings.androidPrivilege),
    )
    val existingTargets = settings.mounts.map { it.targetPath }
    val draftError = validateWorkspaceMountDraft(sourceInput, targetInput, existingTargets)
    // 显式标注类型：避免 if 分支里的 @Composable lambda 被推断成普通函数类型
    val mountCountContent: (@Composable () -> Unit)? = if (settings.mounts.isEmpty()) {
        null
    } else {
        { CardGroupValue(settings.mounts.size.toString()) }
    }
    val pendingRemoveMount = pendingRemoveMountId?.let { id -> settings.mounts.firstOrNull { it.id == id } }
    // 资源统一在卡片本体取，Dialog 子组合里不再调 stringResource
    val removeMountBody = pendingRemoveMount?.let { mount ->
        stringResource(R.string.workspace_access_mount_remove_confirm_body, mount.targetPath)
    }
    val dismissPathDialog = {
        showPathDialog = false
        sourceInput = ""
        targetInput = WORKSPACE_MOUNT_DEFAULT_TARGET
    }

    CardGroup(
        modifier = modifier.testTag(WorkspaceAccessTestTags.CARD),
        title = { Text(strings.title) },
    ) {
        // ① Linux 工作区信任：只是少确认几次，不是 Android root
        switchItem(
            checked = settings.trustedLinux,
            onCheckedChange = { checked ->
                if (busy) return@switchItem
                if (checked) pendingTrust = true else onTrustedChange(false)
            },
            enabled = !busy,
            modifier = Modifier.testTag(WorkspaceAccessTestTags.TRUSTED_SWITCH),
            supportingContent = { Text(strings.trustedDesc) },
            leadingContent = { CardGroupIcon(HugeIcons.FingerPrint) },
            headlineContent = { Text(strings.trustedTitle) },
        )

        // ② Android 执行权限：显式三选一，未授权不显示已提权
        formItem(
            headlineContent = { Text(strings.androidTitle) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (authorized) authorizedLabel else strings.androidUnauthorized,
                    )
                    if (statusText.isNotBlank()) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            leadingContent = { CardGroupIcon(HugeIcons.ComputerTerminal01) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkspacePrivilegeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = activeMode == mode,
                        onClick = {
                            if (busy) return@FilterChip
                            if (workspacePrivilegeModeRequiresConfirm(mode)) {
                                pendingModeName = mode.name
                            } else {
                                onAuthorize(mode)
                            }
                        },
                        enabled = !busy,
                        label = { Text(workspaceAccessModeLabel(mode)) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(WorkspaceAccessTestTags.mode(mode)),
                    )
                }
            }
            Text(
                text = strings.androidDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { if (!busy) onProbe() },
                    enabled = !busy,
                    modifier = Modifier.testTag(WorkspaceAccessTestTags.ANDROID_PROBE),
                ) { Text(strings.probe) }
                TextButton(
                    onClick = { if (!busy) onRevoke() },
                    enabled = !busy && authorized,
                    modifier = Modifier.testTag(WorkspaceAccessTestTags.ANDROID_REVOKE),
                ) { Text(strings.revoke) }
            }
        }

        // ③ 共享存储：Android 系统权限，单独一条，和 Linux 信任分清
        formItem(
            headlineContent = { Text(strings.storageTitle) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (storageGranted) strings.storageGranted else strings.storageDenied)
                    Text(strings.storageDesc)
                }
            },
            leadingContent = { CardGroupIcon(HugeIcons.SmartPhone01) },
        ) {
            TextButton(
                onClick = { if (!busy) onStorageAccess() },
                enabled = !busy,
                modifier = Modifier.testTag(WorkspaceAccessTestTags.STORAGE_SETTINGS),
            ) { Text(strings.storageOpen) }
        }

        // ④ 挂载目录：PRoot 路径映射，不是内核 mount，也不宣称只读
        item(
            leadingContent = { CardGroupIcon(HugeIcons.Link01) },
            headlineContent = { Text(strings.mountsTitle) },
            supportingContent = {
                Text(if (settings.mounts.isEmpty()) strings.mountsEmpty else strings.mountsDesc)
            },
            trailingContent = mountCountContent,
        )
        settings.mounts.forEach { mount ->
            item(
                modifier = Modifier.testTag(WorkspaceAccessTestTags.mountRow(mount.id)),
                leadingContent = { CardGroupIcon(HugeIcons.FolderOpen) },
                headlineContent = {
                    Text(
                        text = mount.sourcePath,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        text = workspaceMountRowText(mount),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    IconButton(
                        onClick = { if (!busy) pendingRemoveMountId = mount.id },
                        enabled = !busy,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = strings.mountRemove,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        }
        item(
            onClick = if (busy) null else onPickDirectory,
            modifier = Modifier.testTag(WorkspaceAccessTestTags.PICK_DIRECTORY),
            leadingContent = { CardGroupIcon(HugeIcons.FolderOpen) },
            headlineContent = { Text(strings.mountPick) },
            supportingContent = { Text(strings.mountPickDesc) },
        )
        item(
            onClick = if (busy) null else ({ showPathDialog = true }),
            modifier = Modifier.testTag(WorkspaceAccessTestTags.ADD_PATH),
            leadingContent = { CardGroupIcon(HugeIcons.Add01) },
            headlineContent = { Text(strings.mountAddPath) },
            supportingContent = { Text(strings.mountAddPathDesc) },
        )
    }

    if (pendingTrust) {
        AlertDialog(
            onDismissRequest = { pendingTrust = false },
            title = { Text(strings.trustedConfirmTitle) },
            text = { Text(strings.trustedConfirmBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTrust = false
                        onTrustedChange(true)
                    },
                    enabled = !busy,
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTrust = false }) { Text(strings.cancel) }
            },
        )
    }

    val pendingMode = pendingModeName?.let { name ->
        WorkspacePrivilegeMode.entries.firstOrNull { it.name == name }
    }
    if (pendingMode != null) {
        AlertDialog(
            onDismissRequest = { pendingModeName = null },
            title = { Text(strings.modeConfirmTitle.getValue(pendingMode)) },
            text = { Text(strings.modeConfirmBody.getValue(pendingMode)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingModeName = null
                        onAuthorize(pendingMode)
                    },
                    enabled = !busy,
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { pendingModeName = null }) { Text(strings.cancel) }
            },
        )
    }

    if (pendingRemoveMount != null) {
        AlertDialog(
            onDismissRequest = { pendingRemoveMountId = null },
            title = { Text(strings.mountRemoveTitle) },
            text = { Text(removeMountBody.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val removingId = pendingRemoveMount.id
                        pendingRemoveMountId = null
                        onRemoveMount(removingId)
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag(WorkspaceAccessTestTags.MOUNT_REMOVE_CONFIRM),
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveMountId = null }) { Text(strings.cancel) }
            },
        )
    }

    if (showPathDialog) {
        val error = draftError
        AlertDialog(
            onDismissRequest = dismissPathDialog,
            title = { Text(strings.pathDialogTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sourceInput,
                        onValueChange = { sourceInput = it },
                        label = { Text(strings.pathSource) },
                        placeholder = { Text(strings.pathSourceHint) },
                        singleLine = true,
                        isError = error != null &&
                            (error == WorkspaceMountDraftError.SOURCE_EMPTY ||
                                error == WorkspaceMountDraftError.SOURCE_NOT_PATH ||
                                error == WorkspaceMountDraftError.SOURCE_RELATIVE ||
                                error == WorkspaceMountDraftError.SOURCE_TOO_BROAD),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(WorkspaceAccessTestTags.PATH_SOURCE),
                    )
                    OutlinedTextField(
                        value = targetInput,
                        onValueChange = { targetInput = it },
                        label = { Text(strings.pathTarget) },
                        placeholder = { Text(strings.pathTargetHint) },
                        singleLine = true,
                        isError = error == WorkspaceMountDraftError.TARGET_RELATIVE ||
                            error == WorkspaceMountDraftError.TARGET_TOO_BROAD ||
                            error == WorkspaceMountDraftError.TARGET_DUPLICATE,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(WorkspaceAccessTestTags.PATH_TARGET),
                    )
                    error?.let {
                        Text(
                            text = strings.draftErrorText(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPathDialog = false
                        onAddPath(sourceInput.trim(), workspaceMountTargetOrDefault(targetInput))
                        sourceInput = ""
                        targetInput = WORKSPACE_MOUNT_DEFAULT_TARGET
                    },
                    enabled = error == null && !busy,
                    modifier = Modifier.testTag(WorkspaceAccessTestTags.PATH_CONFIRM),
                ) { Text(strings.pathConfirm) }
            },
            dismissButton = {
                TextButton(onClick = dismissPathDialog) { Text(strings.cancel) }
            },
        )
    }
}
