package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext

/**
 * 下拉快捷面板里的「小宠物」开关。
 *
 * 磁贴只是一个读写开关，状态完全来自 [SettingsStore.settingsFlow] 里的
 * `displaySetting.enableFloatingChatWindow`，不新增任何设置字段：设置页和磁贴改的是同一个值，
 * 悬浮窗本身的显示/隐藏仍由原有的浮动窗口逻辑负责。这里不起前台服务，也不持有悬浮窗。
 */
class CompanionQuickTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    /**
     * 磁贴进程和 App 是同一个；Koin 在 Application.onCreate 里启动，所以这里能拿到。
     * 用 getOrNull 兜底：万一磁贴先被系统拉起而 Koin 还没就绪，也只是显示为关闭，不崩。
     */
    private fun settingsStore(): SettingsStore? =
        GlobalContext.getOrNull()?.getOrNull<SettingsStore>()

    override fun onStartListening() {
        super.onStartListening()
        val store = settingsStore()
        if (store == null) {
            renderTile(enabled = false)
            return
        }
        listenJob?.cancel()
        listenJob = scope.launch {
            // 设置页里改了开关，磁贴也要跟着变
            store.settingsFlow.collect { settings ->
                renderTile(settings.displaySetting.enableFloatingChatWindow)
            }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggleCompanion() } else toggleCompanion()
    }

    /**
     * 翻转开关。
     *
     * 写入不挂在磁贴自己的 [scope] 上：面板一收起系统就会销毁 TileService，
     * 那时正在 `awaitLoaded()` / 写盘中的协程会被 cancel，设置整份丢掉，表现就是「点了没反应」。
     * 所以优先借 AppScope（和别的后台任务同一个，进程活多久它活多久），取不到才退回自己的 scope。
     */
    private fun toggleCompanion() {
        val store = settingsStore() ?: return
        val work: suspend () -> Unit = {
            store.awaitLoaded()
            if (!store.settingsFlow.value.displaySetting.enableFloatingChatWindow &&
                !Settings.canDrawOverlays(this@CompanionQuickTileService)
            ) {
                // 没有悬浮权限就不把开关打开：先去授权，回来再点一下
                openOverlayPermissionPage()
            } else {
                updateCompanionSwitch(store)?.let(::renderTile)
            }
        }
        val appScope = GlobalContext.getOrNull()?.getOrNull<AppScope>()
        if (appScope != null) appScope.launch { work() } else scope.launch { work() }
    }

    /**
     * 写入开关。
     *
     * @return 写入后的开关状态；写入失败（已提示）返回 null。
     * 只有写入本身进 try：渲染磁贴不是「保存失败」，不能让它冒充保存错误。
     */
    private suspend fun updateCompanionSwitch(store: SettingsStore): Boolean? = try {
        store.update { settings ->
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    enableFloatingChatWindow = !settings.displaySetting.enableFloatingChatWindow,
                )
            )
        }
        store.settingsFlow.value.displaySetting.enableFloatingChatWindow
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Toast.makeText(
            this,
            e.localizedMessage ?: getString(R.string.r21_companion_tile_toggle_failed),
            Toast.LENGTH_SHORT,
        ).show()
        null
    }

    private fun renderTile(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled && Settings.canDrawOverlays(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.r21_companion_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_rikkahub)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 没有悬浮权限时讲清楚为什么点了会跳去授权页
            tile.subtitle = if (Settings.canDrawOverlays(this)) {
                null
            } else {
                getString(R.string.r21_companion_tile_subtitle_permission)
            }
        }
        // 写入是异步的，磁贴可能在渲染前就被系统销毁；一次失败的 updateTile 不该抛到
        // AppScope 上（那会连带丢掉后面的渲染/提示），只当这一次没刷新
        runCatching { tile.updateTile() }
    }

    private fun openOverlayPermissionPage() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri(),
        )
        collapseWith(intent)
    }

    /**
     * 收面板并拉起 Activity。
     *
     * Android 14（API 34）起只接受 PendingIntent 版本，旧的 Intent 重载已废弃并在新系统上会抛异常；
     * 旧版本仍走 Intent 重载，那里能拿到正确的「从磁贴启动」动画和任务栈。
     */
    private fun collapseWith(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            // 个别 ROM 拒绝从磁贴拉起授权页；退化成普通启动，至少别让点击毫无反应
            runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
