package me.rerere.rikkahub.ui.pages.setting

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.service.CompanionQuickTileService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

private val UPDATE_PAUSE_DAY_OPTIONS = listOf(7, 14, 21)
private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1_000L

// StatusBarManager 的添加结果码（Android 13+）。这里用数值比较，不依赖系统类里可能不可见的常量。
private const val TILE_ADD_RESULT_ALREADY_ADDED = 1
private const val TILE_ADD_RESULT_ADDED = 2

/**
 * 把「小宠物」磁贴塞进下拉快捷面板。
 *
 * Android 13 起系统提供 requestAddTileService（弹出确认框由系统负责）；更低版本只能提示用户
 * 自己去快捷面板编辑界面手动添加。添加失败/已存在都要给出明确结果，不能点了没反应。
 */
private fun requestAddCompanionTile(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        addCompanionTile(context)
        return
    }
    Toast.makeText(context, R.string.r21_companion_tile_add_unsupported, Toast.LENGTH_LONG).show()
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun addCompanionTile(context: Context) {
    val statusBarManager = context.getSystemService(StatusBarManager::class.java)
    if (statusBarManager == null) {
        Toast.makeText(context, R.string.r21_companion_tile_add_unsupported, Toast.LENGTH_LONG).show()
        return
    }
    statusBarManager.requestAddTileService(
        ComponentName(context, CompanionQuickTileService::class.java),
        context.getString(R.string.r21_companion_tile_label),
        // 用全名，避免和 Compose 的 Icon 组成 import 冲突
        android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_rikkahub),
        context.mainExecutor,
    ) { result ->
        val message = when (result) {
            TILE_ADD_RESULT_ADDED -> R.string.r21_companion_tile_add_added
            TILE_ADD_RESULT_ALREADY_ADDED -> R.string.r21_companion_tile_add_already
            else -> R.string.r21_companion_tile_add_failed
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var showUpdatePauseDialog by remember { mutableStateOf(false) }
    var selectedUpdatePauseDays by remember { mutableStateOf(UPDATE_PAUSE_DAY_OPTIONS.first()) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings { it.copy(displaySetting = setting) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val updateChecksEnabled =
        displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    val context = LocalContext.current
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // 从系统设置页授权回来后刷新
    LifecycleResumeEffect(Unit) {
        canDrawOverlays = Settings.canDrawOverlays(context)
        onPauseOrDispose {}
    }

    fun requestOverlayPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_notification))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.pageContainerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Play 版由商店负责更新，不提供更新提醒
            if (!BuildConfig.IS_PLAY_STORE) {
                item {
                    CardGroup {
                        item(
                            onClick = {
                                selectedUpdatePauseDays = UPDATE_PAUSE_DAY_OPTIONS.first()
                                showUpdatePauseDialog = true
                            },
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_updates_title)) },
                            supportingContent = {
                                Text(
                                    if (updateChecksEnabled) {
                                        stringResource(R.string.setting_update_reminder_enabled)
                                    } else {
                                        stringResource(
                                            R.string.setting_update_reminder_paused_until,
                                            Instant.ofEpochMilli(displaySetting.updateCheckDisabledUntilEpochMillis)
                                                .toLocalDateTime(),
                                        )
                                    }
                                )
                            },
                            trailingContent = {
                                Icon(HugeIcons.ArrowRight01, contentDescription = null)
                            },
                        )
                    }
                }
            }
            item {
                CardGroup(title = { Text(stringResource(R.string.setting_page_preferences_notification_background_generation)) }) {
                    switchItem(
                        checked = displaySetting.enableNotificationOnMessageGeneration,
                        onCheckedChange = {
                            if (it && !permissionState.allPermissionsGranted) {
                                permissionState.requestPermissions()
                            }
                            updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                        },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                    )
                    switchItem(
                        checked = displaySetting.enableNotificationOnCompression,
                        onCheckedChange = {
                            if (it && !permissionState.allPermissionsGranted) permissionState.requestPermissions()
                            updateDisplaySetting(displaySetting.copy(enableNotificationOnCompression = it))
                        },
                        headlineContent = { Text(stringResource(R.string.r11_compression_notify_setting)) },
                        supportingContent = { Text(stringResource(R.string.r11_compression_notify_setting_desc)) },
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        switchItem(
                            checked = displaySetting.enableLiveUpdateNotification,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                            },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                        )
                    }
                    switchItem(
                        checked = displaySetting.enableFloatingChatWindow,
                        onCheckedChange = {
                            if (it && !canDrawOverlays) requestOverlayPermission()
                            updateDisplaySetting(displaySetting.copy(enableFloatingChatWindow = it))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_preferences_notification_floating_window_desc))
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_notification_floating_window)) },
                    )
                    if (displaySetting.enableFloatingChatWindow && !canDrawOverlays) {
                        item(
                            onClick = ::requestOverlayPermission,
                            headlineContent = { Text(stringResource(R.string.setting_page_preferences_notification_overlay_permission)) },
                            supportingContent = { Text(stringResource(R.string.setting_page_preferences_notification_overlay_permission_desc)) },
                            trailingContent = {
                                Icon(HugeIcons.ArrowRight01, contentDescription = null)
                            },
                        )
                    }
                    // 和上面的悬浮开关是同一个设置项，只是把入口放进下拉快捷面板
                    item(
                        onClick = { requestAddCompanionTile(context) },
                        headlineContent = { Text(stringResource(R.string.r21_companion_tile_add_title)) },
                        supportingContent = { Text(stringResource(R.string.r21_companion_tile_add_desc)) },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                }
            }
            floatingCompanionSettingsItem(
                companion = displaySetting.floatingCompanion,
                onChange = { companion ->
                    displaySetting = displaySetting.copy(floatingCompanion = companion)
                },
                persistChange = vm::persistFloatingCompanion,
                providers = settings.providers,
            )
        }
    }

    if (showUpdatePauseDialog) {
        AlertDialog(
            onDismissRequest = { showUpdatePauseDialog = false },
            title = { Text(stringResource(R.string.setting_update_reminder_pause_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.setting_update_reminder_pause_description))
                    ConnectedChoiceButtons(
                        options = UPDATE_PAUSE_DAY_OPTIONS,
                        selected = selectedUpdatePauseDays,
                        onSelect = { selectedUpdatePauseDays = it },
                        // 对话框底色和默认的按钮底色一样，换一个能分开的
                        containerColor = MaterialTheme.colorScheme.surfaceBright,
                    ) { days, selected ->
                        ConnectedChoiceLabel(
                            text = stringResource(R.string.setting_update_reminder_pause_days, days),
                            selected = selected,
                        )
                    }
                    if (!updateChecksEnabled) {
                        TextButton(
                            onClick = {
                                updateDisplaySetting(
                                    displaySetting.copy(updateCheckDisabledUntilEpochMillis = 0L)
                                )
                                showUpdatePauseDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setting_update_reminder_resume_now))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateDisplaySetting(
                            displaySetting.copy(
                                updateCheckDisabledUntilEpochMillis =
                                    System.currentTimeMillis() + selectedUpdatePauseDays * MILLIS_PER_DAY,
                            )
                        )
                        showUpdatePauseDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatePauseDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
