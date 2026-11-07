package me.rerere.rikkahub.data.mcpserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import org.koin.android.ext.android.inject
import org.koin.core.context.GlobalContext

private const val CHANNEL_ID = "local_mcp_server"

/**
 * 本机 MCP 的前台服务（specialUse）：用户显式打开开关才会起，常驻通知带"停止"。
 *
 * 刻意不做的事：
 * - 没有 BOOT_COMPLETED receiver，开机不自启；
 * - 不自动授无障碍/设备权限，设备工具能不能用取决于用户自己的授权；
 * - `onDestroy` 一定调 [LocalMcpServerManager.stopBlocking]，不留端口、不留 RUNNING 状态。
 */
class LocalMcpService : Service() {

    companion object {
        private const val TAG = "LocalMcpService"
        const val ACTION_START = "me.rerere.rikkahub.action.LOCAL_MCP_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.LOCAL_MCP_STOP"
        const val NOTIFICATION_ID = 2102

        /**
         * Koin 里那份唯一的 [LocalMcpSettingsStore]；拿不到就返回 null（不会顺手 new 一个）。
         *
         * 早先这里每次 `LocalMcpSettingsStore(context)`：等于每次恢复都新建 StateFlow/写盘 scope，
         * 文件缺失时还会再生成一份新 token 落盘——"恢复服务"变成"偷偷换令牌"。
         */
        private fun settingsStoreOrNull(): LocalMcpSettingsStore? =
            runCatching { GlobalContext.get().get<LocalMcpSettingsStore>() }.getOrNull()

        private fun managerOrNull(): LocalMcpServerManager? =
            runCatching { GlobalContext.get().get<LocalMcpServerManager>() }.getOrNull()

        /** UI 打开开关时调：先由 UI 把 enabled 落盘，再起 FGS。失败要如实报给 UI，不能只写日志。 */
        fun requestStart(context: Context) {
            val intent = Intent(context, LocalMcpService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { error ->
                    Log.w(TAG, "start foreground service failed", error)
                    val message = runCatching {
                        context.getString(R.string.r22_mcp_error_foreground_denied)
                    }.getOrElse { "foreground_service_start_failed" }
                    managerOrNull()?.reportError(message)
                }
        }

        /** UI 关掉开关时调：直接停服务，onDestroy 负责收尾。 */
        fun requestStop(context: Context) {
            runCatching { context.stopService(Intent(context, LocalMcpService::class.java)) }
        }

        /**
         * App 正常前台启动时恢复用户此前打开的服务（主代理在 RouteActivity 里调）。
         * enabled=false 时什么都不做，不擅自开。
         *
         * 只读用户意图：优先用 Koin 里的单例 store，Koin 还没起来时直接读设置文件（不落盘、不生成 token）。
         */
        fun restoreIfEnabled(context: Context) {
            val enabled = settingsStoreOrNull()?.current()?.enabled
                ?: localMcpEnabledOnDisk(context)
            if (enabled) requestStart(context)
        }
    }

    private val manager: LocalMcpServerManager by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null
    private var sawRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 用户在通知里点了停止：这是明确意图，同步落盘 enabled=false
            serviceScope.launch {
                runCatching { manager.applySettings { it.copy(enabled = false) } }
                    .onFailure { error -> Log.w(TAG, "apply disabled on stop failed", error) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (!startForegroundCompat()) {
            // 起不了前台服务就明确失败，不要留下一个"看起来开着"的开关
            manager.reportError(getString(R.string.r22_mcp_error_foreground_denied))
            stopSelf()
            return START_NOT_STICKY
        }

        startObservingState()
        serviceScope.launch {
            // 启动失败必须让 UI 看到（runtime 状态 + 通知），不能只沉在 Log 里：
            // 这里既兜住异常（否则 SupervisorJob 下会成为未捕获异常），也兜住 start() 返回 false。
            val plan = planLocalMcpStart(
                intentAction = intent?.action,
                startAction = ACTION_START,
                enabled = manager.settings.value.enabled,
            )
            val started = runCatching {
                if (plan.writeEnabled) manager.applySettings { it.copy(enabled = true) }
                // plan.sync 恒为 true：冷启动恢复时 enabled 已经是 true，applySettings 不会同步，
                // 只靠它就会出现"前台服务在、127.0.0.1 上却什么都没有"。
                if (plan.sync) manager.syncWithSettings() else manager.runtime.value.isRunning
            }.onFailure { error ->
                Log.e(TAG, "start local mcp failed: ${error::class.simpleName}: ${error.message}")
            }.getOrDefault(false)

            val state = manager.runtime.value
            // 已经有真实原因（port_in_use / bind_failed / 设置校验）时不要用兜底文案覆盖它。
            val fallback = localMcpStartFallbackError(
                running = started && state.isRunning,
                statusIsError = state.status == LocalMcpServerStatus.ERROR,
                existingError = state.error,
                fallback = getString(R.string.r22_mcp_error_foreground_denied),
            )
            if (fallback != null) manager.reportError(fallback)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        // 前台服务死了就不能还占着端口：先关闸门再收引擎（stopBlocking 内部有界、不阻塞主线程），
        // 然后才取消协程 scope——顺序反了会把本次收尾也一起取消掉。
        runCatching { manager.stopBlocking() }
            .onFailure { error -> Log.w(TAG, "stop local mcp on destroy failed", error) }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean = try {
        val notification = buildNotification(getString(R.string.r22_mcp_notification_starting), null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (error: Exception) {
        // 部分 OEM ROM 会拒绝 specialUse FGS，即使 manifest 已声明
        Log.e(TAG, "failed to start local mcp foreground service", error)
        false
    }

    private fun startObservingState() {
        if (stateObserverJob != null) return
        stateObserverJob = serviceScope.launch {
            manager.runtime.collect { state ->
                // 通知渠道/权限在部分 ROM 上会抛异常；一条通知失败不能把服务协程打崩。
                runCatching { updateNotification(state) }
                    .onFailure { error -> Log.w(TAG, "update local mcp notification failed", error) }
            }
        }
    }

    private fun updateNotification(state: LocalMcpRuntimeState) {
        when (state.status) {
            LocalMcpServerStatus.RUNNING -> {
                sawRunning = true
                val endpoint = state.endpoint ?: localMcpEndpoint(state.port)
                notify(buildNotification(getString(R.string.r22_mcp_notification_running, endpoint), endpoint))
            }

            LocalMcpServerStatus.ERROR -> {
                val reason = state.error ?: "unknown"
                notify(buildNotification(getString(R.string.r22_mcp_notification_error, reason), null))
            }

            LocalMcpServerStatus.STOPPED -> {
                if (sawRunning) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            LocalMcpServerStatus.STARTING,
            LocalMcpServerStatus.STOPPING,
            -> notify(buildNotification(getString(R.string.r22_mcp_notification_starting), null))
        }
    }

    private fun notify(notification: Notification) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.r22_mcp_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.r22_mcp_notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, endpoint: String?): Notification {
        val stopIntent = Intent(this, LocalMcpService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPendingIntent = openIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.r22_mcp_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(endpoint ?: text))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.r22_mcp_notification_stop), stopPendingIntent)
            .build()
    }
}
