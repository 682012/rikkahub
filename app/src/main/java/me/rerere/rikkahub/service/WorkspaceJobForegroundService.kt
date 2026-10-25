package me.rerere.rikkahub.service

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

private const val TAG = "WorkspaceJobFgs"

/**
 * 工作区后台任务的前台服务。
 *
 * 只负责保活与通知，任务本身仍由 WorkspaceJobManager 在 Rootfs 里运行。
 * 用 specialUse 类型，避免 dataSync 的 6 小时配额；持有 partial wakelock，
 * 保证锁屏后进程还能继续跑。
 */
class WorkspaceJobForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "workspace_jobs"
        private const val NOTIFICATION_ID = WORKSPACE_JOB_NOTIFICATION_ID
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.WORKSPACE_JOB_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.WORKSPACE_JOB_RELEASE"
        private const val EXTRA_JOB_ID = "job_id"

        fun acquire(context: Context, jobId: String): Boolean {
            val intent = Intent(context, WorkspaceJobForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_JOB_ID, jobId)
            }
            return runCatching { ContextCompat.startForegroundService(context, intent); true }
                .onFailure { Log.e(TAG, "acquire failed", it) }.getOrDefault(false)
        }

        fun release(context: Context, jobId: String) {
            val intent = Intent(context, WorkspaceJobForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_JOB_ID, jobId)
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.e(TAG, "release failed", it) }
        }
    }

    private val jobs = linkedSetOf<String>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        runCatching {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rikkahub:workspace_job").apply {
                setReferenceCounted(false)
                acquire() // Released when the final owned job finishes or the service is destroyed.
            }
        }.onFailure { Log.e(TAG, "acquire wake lock failed", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID)
        when (intent?.action) {
            ACTION_ACQUIRE -> if (jobId != null) {
                jobs.add(jobId)
                startAsForeground(jobs.size)
            }
            ACTION_RELEASE -> {
                if (jobId != null) jobs.remove(jobId)
                if (jobs.isEmpty()) {
                    stopSelf(startId)
                } else {
                    startAsForeground(jobs.size)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForeground(count: Int) {
        val notification = buildNotification(count)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(count: Int): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RouteActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("工作区任务运行中")
            .setContentText("$count 个后台任务在执行")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "工作区任务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "长时间运行的工作区任务"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
