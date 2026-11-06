package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.reading.ReadingPreferences
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.setting.HighPrivilegeSheet
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.utils.plus

private enum class GrantState { Granted, Denied, Blocked }

private enum class HubPermission { Notifications, Camera, Microphone, Overlay, Battery, AllFiles }

private data class PermissionRow(
    val permission: HubPermission,
    val labelRes: Int,
    val state: GrantState,
)

/** Opens a system settings screen, falling back to the app detail page when the OEM has no such activity. */
private fun openPermissionSettings(context: Context, intent: Intent) {
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
        runCatching { context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * One place for what this app needs from the system, with honest state: each row reflects the real
 * grant, and a permission the user already refused (system no longer shows a dialog) links straight
 * to its system settings page instead of silently doing nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionHubPage() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val owner = LocalLifecycleOwner.current
    // Recomputed whenever the app comes back to the foreground, so the state is never stale after a
    // trip to the system settings or a permission dialog.
    var revision by remember { mutableIntStateOf(0) }
    // Permission keys the user has already been asked for in this page instance.
    val permissionPrefs = remember { context.getSharedPreferences("permission_requests", Context.MODE_PRIVATE) }
    val asked = remember { mutableStateListOf<String>().apply { addAll(permissionPrefs.all.keys) } }
    var readLinks by remember { mutableStateOf(ReadingPreferences.enabled(context)) }
    // 高级权限与设备能力的入口；内容全在同一个 bottomsheet 里，四组都从这一页可达。
    var highPrivilegeOpen by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // "Don't ask again" (or a second refusal) means the system would never show a dialog again:
    // that state has to be reported as blocked and routed to settings, not offered as a dead tap.
    fun state(permission: String): GrantState = when {
        granted(permission) -> GrantState.Granted
        asked.contains(permission) && activity?.shouldShowRequestPermissionRationale(permission) == false ->
            GrantState.Blocked
        else -> GrantState.Denied
    }

    fun request(permission: String) {
        if (!asked.contains(permission)) asked.add(permission)
        permissionPrefs.edit().putBoolean(permission, true).apply()
        launcher.launch(arrayOf(permission))
    }

    val appUri = Uri.parse("package:${context.packageName}")
    fun details() = openPermissionSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri))
    fun notificationSettings() = openPermissionSettings(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )

    val systemRows = remember(revision) {
        buildList {
            add(
                PermissionRow(
                    permission = HubPermission.Notifications,
                    labelRes = R.string.r20_permissions_notifications,
                    state = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                        GrantState.Granted
                    } else {
                        if (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) GrantState.Blocked
                        else state(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            )
            listOf(
                HubPermission.Camera to Manifest.permission.CAMERA,
                HubPermission.Microphone to Manifest.permission.RECORD_AUDIO,
            ).forEach { (row, permission) ->
                add(
                    PermissionRow(
                        permission = row,
                        labelRes = if (row == HubPermission.Camera) R.string.r20_permissions_camera
                        else R.string.r20_permissions_microphone,
                        state = state(permission),
                    )
                )
            }
            add(
                PermissionRow(
                    permission = HubPermission.Overlay,
                    labelRes = R.string.r20_permissions_overlay,
                    state = if (Settings.canDrawOverlays(context)) GrantState.Granted else GrantState.Denied,
                )
            )
            add(
                PermissionRow(
                    permission = HubPermission.Battery,
                    labelRes = R.string.r20_permissions_battery,
                    state = if (ignoringBatteryOptimizations(context)) GrantState.Granted else GrantState.Denied,
                )
            )
            // All-files access only exists from Android 11; the row stays hidden on older systems,
            // where the system picker already covers per-file access.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    PermissionRow(
                        permission = HubPermission.AllFiles,
                        labelRes = R.string.r20_permissions_all_files,
                        state = if (Environment.isExternalStorageManager()) GrantState.Granted else GrantState.Denied,
                    )
                )
            }
        }
    }

    fun onRowClick(row: PermissionRow) {
        when (row.permission) {
            HubPermission.Notifications -> {
                // Runtime notification permission only exists from Android 13 on.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !granted(Manifest.permission.POST_NOTIFICATIONS) &&
                    row.state != GrantState.Blocked
                ) {
                    request(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    notificationSettings()
                }
            }

            HubPermission.Camera -> if (row.state == GrantState.Denied) request(Manifest.permission.CAMERA) else details()
            HubPermission.Microphone -> if (row.state == GrantState.Denied) request(Manifest.permission.RECORD_AUDIO) else details()
            HubPermission.Overlay -> openPermissionSettings(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri))
            HubPermission.Battery -> openPermissionSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            HubPermission.AllFiles -> openPermissionSettings(context, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri))
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.r20_permissions_title)) }, navigationIcon = { BackButton() })
    }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(title = { Text(stringResource(R.string.m1_priv_entry_title)) }) {
                    item(headlineContent = { Text(stringResource(R.string.m1_priv_entry_desc)) })
                    val open: () -> Unit = { highPrivilegeOpen = true }
                    item(
                        onClick = open,
                        headlineContent = { Text(stringResource(R.string.m1_priv_entry_shizuku)) },
                        supportingContent = { Text(stringResource(R.string.m1_priv_entry_shizuku_desc)) },
                        trailingContent = {
                            Text(
                                text = stringResource(R.string.m1_priv_entry_open),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                    item(
                        onClick = open,
                        headlineContent = { Text(stringResource(R.string.m1_priv_entry_dev)) },
                        supportingContent = { Text(stringResource(R.string.m1_priv_entry_dev_desc)) },
                        trailingContent = {
                            Text(
                                text = stringResource(R.string.m1_priv_entry_open),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                    item(
                        onClick = open,
                        headlineContent = { Text(stringResource(R.string.m1_priv_entry_special)) },
                        supportingContent = { Text(stringResource(R.string.m1_priv_entry_special_desc)) },
                        trailingContent = {
                            Text(
                                text = stringResource(R.string.m1_priv_entry_open),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                    item(
                        onClick = open,
                        headlineContent = { Text(stringResource(R.string.m1_priv_entry_automation)) },
                        supportingContent = { Text(stringResource(R.string.m1_priv_entry_automation_desc)) },
                        trailingContent = {
                            Text(
                                text = stringResource(R.string.m1_priv_entry_open),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(title = { Text(stringResource(R.string.r20_permissions_group_system)) }) {
                    systemRows.forEach { row ->
                        val blockedHint: (@Composable () -> Unit)? =
                            if (row.state == GrantState.Blocked) {
                                { Text(stringResource(R.string.r20_permissions_blocked_hint)) }
                            } else {
                                null
                            }
                        item(
                            headlineContent = { Text(stringResource(row.labelRes)) },
                            supportingContent = blockedHint,
                            trailingContent = {
                                Text(
                                    text = stringResource(
                                        when (row.state) {
                                            GrantState.Granted -> R.string.r20_permissions_granted
                                            GrantState.Denied -> R.string.r20_permissions_denied
                                            GrantState.Blocked -> R.string.r20_permissions_blocked
                                        }
                                    ),
                                    color = if (row.state == GrantState.Granted) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            onClick = { onRowClick(row) },
                        )
                    }
                }
            }
            item {
                CardGroup(title = { Text(stringResource(R.string.r20_permissions_group_reading)) }) {
                    switchItem(
                        checked = readLinks,
                        onCheckedChange = {
                            readLinks = it
                            ReadingPreferences.setEnabled(context, it)
                        },
                        headlineContent = { Text(stringResource(R.string.r20_permissions_read_links)) },
                        supportingContent = { Text(stringResource(R.string.r20_permissions_read_links_desc)) },
                    )
                    item(
                        onClick = ::details,
                        headlineContent = { Text(stringResource(R.string.r20_permissions_app_details)) },
                        supportingContent = { Text(stringResource(R.string.r20_permissions_app_details_desc)) },
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.r20_permissions_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    // revision 在 ON_RESUME 时自增：从系统设置回来，sheet 里的真实状态会重新读取。
    if (highPrivilegeOpen) {
        HighPrivilegeSheet(
            refreshKey = revision,
            onDismiss = { highPrivilegeOpen = false },
        )
    }
}

private fun ignoringBatteryOptimizations(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
}.getOrDefault(false)
