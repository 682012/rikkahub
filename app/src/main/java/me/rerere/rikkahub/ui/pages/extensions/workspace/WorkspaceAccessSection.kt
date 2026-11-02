package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.WorkspaceAccessManager
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeMode
import me.rerere.rikkahub.service.BackgroundShellManager
import org.koin.compose.koinInject

@Composable
internal fun WorkspaceAccessSection(workspace: WorkspaceEntity) {
    val access: WorkspaceAccessManager = koinInject()
    val repository: WorkspaceRepository = koinInject()
    val terminals: WorkspaceTerminalSessionManager = koinInject()
    val background: BackgroundShellManager = koinInject()
    val root = workspace.root
    val config by access.settings(root).collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var storageGranted by remember(root) { mutableStateOf(access.storageAccessGranted()) }
    var busy by remember(root) { mutableStateOf(false) }
    var status by remember(root) { mutableStateOf(access.recordError(root).orEmpty()) }
    val disabledText = stringResource(R.string.workspace_access_status_disabled)
    val selectedText = stringResource(R.string.workspace_access_status_selected)
    val verifiedText = stringResource(R.string.workspace_access_status_verified)
    val changedText = stringResource(R.string.workspace_access_mount_applied)
    val act: (suspend () -> Unit) -> Unit = { action ->
        if (!busy) scope.launch {
            busy = true
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { status = error.message ?: error.javaClass.simpleName }
            finally { busy = false }
        }
    }
    val closeLinuxSessions: suspend () -> Unit = {
        terminals.closeWorkspace(root)
        background.killWorkspace(workspace.id)
        repository.stopLocalAccessJobs(workspace.id)
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        storageGranted = access.storageAccessGranted()
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) act {
            closeLinuxSessions()
            access.addMountFromTree(root, uri)
            status = changedText
        }
    }
    DisposableEffect(lifecycle, root) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageGranted = access.storageAccessGranted()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val currentStatus = status.ifBlank {
        if (config.androidShellEnabled) "$selectedText ${config.androidPrivilege.name}" else disabledText
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspaceAccessCard(
            settings = config, storageGranted = storageGranted, statusText = currentStatus, busy = busy,
            onAuthorize = { mode -> act {
                val identity = access.authorize(root, mode)
                status = "$verifiedText ${identity.mode.name} · UID ${identity.uid}"
            } },
            onRevoke = { act { access.revoke(root); status = disabledText } },
            onTrustedChange = { trusted -> act { access.setTrusted(root, trusted) } },
            onStorageAccess = {
                if (Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    try { context.startActivity(intent) }
                    catch (_: android.content.ActivityNotFoundException) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                            .onFailure { status = it.message.orEmpty() }
                    }
                } else storagePermission.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
            },
            onPickDirectory = { folderPicker.launch(null) },
            onAddPath = { source, target -> act {
                access.addMount(root, source, target)
                closeLinuxSessions()
                status = changedText
            } },
            onRemoveMount = { mountId -> act {
                access.removeMount(root, mountId)
                closeLinuxSessions()
                status = changedText
            } },
            onProbe = { act {
                val probe = access.executeAndroid(root, "id; cat /proc/self/attr/current", "/", 10_000)
                status = "$verifiedText ${probe.identity.mode.name} · UID ${probe.identity.uid}\n${probe.result.stdout.trim()}"
            } },
        )
        if (config.androidShellEnabled) WorkspaceAndroidConsole(root = root, access = access, enabled = !busy)
    }
}
