package me.rerere.rikkahub.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R

/**
 * 通知构建器的配置 DSL
 */
class NotificationConfig {
    /** Optional identity namespace; null preserves the existing untagged notifications. */
    var tag: String? = null
    var title: String = ""
    var content: String = ""
    var subText: String? = null
    var smallIcon: Int = R.drawable.ic_stat_rikkahub
    var autoCancel: Boolean = false
    var ongoing: Boolean = false
    var onlyAlertOnce: Boolean = false
    var category: String? = null
    var visibility: Int = NotificationCompat.VISIBILITY_PRIVATE
    var contentIntent: PendingIntent? = null
    var useBigTextStyle: Boolean = false

    // Live Update 相关
    var requestPromotedOngoing: Boolean = false
    var shortCriticalText: String? = null

    // 默认通知效果
    var useDefaults: Boolean = false

    /**
     * 进程死亡兜底：超过这个毫秒数系统自动撤掉这条通知。
     *
     * 只适用于"发布者还活着就会不断重新发布"的通知（流式进度）。每次 notify() 重新计时，
     * 所以正常运行时会持续续期；只有发布进程被杀、再没人重新发布时才会到点消失。
     */
    var timeoutAfterMs: Long? = null
}

/** 系统里一条当前生效的通知，用来按身份精确清理 */
data class ActiveNotification(
    val tag: String?,
    val id: Int,
)

object NotificationUtil {
    private const val TAG = "NotificationUtil"

    /**
     * 检查是否有通知权限
     */
    fun hasNotificationPermission(context: Context): Boolean {
        // POST_NOTIFICATIONS does not exist before Android 13.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 使用 DSL 风格创建并发送通知
     *
     * @param context 上下文
     * @param channelId 通知渠道 ID
     * @param notificationId 通知 ID
     * @param config 通知配置 lambda
     * @return 是否成功发送
     */
    @SuppressLint("MissingPermission")
    fun notify(
        context: Context,
        channelId: String,
        notificationId: Int,
        config: NotificationConfig.() -> Unit
    ): Boolean {
        if (!hasNotificationPermission(context)) {
            return false
        }

        val notificationConfig = NotificationConfig().apply(config)
        val notification = buildNotification(context, channelId, notificationConfig)

        NotificationManagerCompat.from(context).notify(notificationConfig.tag, notificationId, notification.build())
        return true
    }

    /**
     * 构建通知
     */
    fun buildNotification(
        context: Context,
        channelId: String,
        config: NotificationConfig
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channelId).apply {
            setContentTitle(config.title)
            setContentText(config.content)
            setSmallIcon(config.smallIcon)
            setAutoCancel(config.autoCancel)
            setOngoing(config.ongoing)
            setOnlyAlertOnce(config.onlyAlertOnce)
            setVisibility(config.visibility)

            config.subText?.let { setSubText(it) }
            config.category?.let { setCategory(it) }
            config.contentIntent?.let { setContentIntent(it) }

            if (config.useBigTextStyle) {
                setStyle(NotificationCompat.BigTextStyle().bigText(config.content))
            }

            if (config.useDefaults) {
                setDefaults(NotificationCompat.DEFAULT_ALL)
            }

            config.timeoutAfterMs?.let { setTimeoutAfter(it) }

            // Android 15+ Live Update 支持
            if (config.requestPromotedOngoing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                setRequestPromotedOngoing(true)
            }

            // Android 16+ 状态栏 chip 文本
            if (config.shortCriticalText != null && Build.VERSION.SDK_INT >= 36) {
                setShortCriticalText(config.shortCriticalText!!)
            }
        }
    }

    /**
     * 取消通知
     */
    fun cancel(context: Context, notificationId: Int, tag: String? = null) {
        NotificationManagerCompat.from(context).cancel(tag, notificationId)
    }

    /**
     * 当前所有还没被用户划掉的通知。
     *
     * 只有活跃通知能拿到 tag，而进程重启后上一进程留下的通知也在其中，所以这是清理历史残留的依据。
     * 权限被拒或系统查询失败时返回空表，调用方不需要额外判断。
     */
    @SuppressLint("MissingPermission")
    fun activeNotifications(context: Context): List<ActiveNotification> {
        val manager = NotificationManagerCompat.from(context)
        return runCatching {
            manager.activeNotifications.map { ActiveNotification(tag = it.tag, id = it.id) }
        }.getOrElse { e ->
            // 没有 POST_NOTIFICATIONS 时部分 ROM 会抛 SecurityException；清理是尽力而为，不该成为崩溃点。
            Log.w(TAG, "Unable to read active notifications", e)
            emptyList()
        }
    }

    /**
     * 只取消匹配 [predicate] 的通知，其它通知一律不动。
     *
     * 刻意不用 cancelAll()：它会连带清掉 foreground service 通知和生成完成通知。
     *
     * @return 实际匹配到的通知数量（用于日志）
     */
    fun cancelWhere(context: Context, predicate: (notificationId: Int, tag: String?) -> Boolean): Int {
        val targets = activeNotifications(context).filter { predicate(it.id, it.tag) }
        targets.forEach { active ->
            runCatching { cancel(context, active.id, active.tag) }
        }
        return targets.size
    }

    /**
     * 取消所有通知
     */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}

/**
 * Context 扩展函数，简化通知发送
 */
fun Context.sendNotification(
    channelId: String,
    notificationId: Int,
    config: NotificationConfig.() -> Unit
): Boolean = NotificationUtil.notify(this, channelId, notificationId, config)

/**
 * Context 扩展函数，取消通知
 */
fun Context.cancelNotification(notificationId: Int, tag: String? = null) {
    NotificationUtil.cancel(this, notificationId, tag)
}
