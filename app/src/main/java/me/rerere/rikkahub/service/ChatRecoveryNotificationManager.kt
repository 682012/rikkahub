package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.NotificationUtil
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid

// 自动恢复的通知身份（CHAT_RECOVERY_* ID/TAG 前缀/超时与 chatRecoveryNotificationTag）已集中到
// NotificationIdentity.kt：那里只依赖 kotlin.uuid，可在纯 JVM 下单独编译测试，本文件只留 Android
// 通知管道的接线。

/** 取会话的 deep link 用不同的 requestCode，和生成完成通知的 PendingIntent 分开 */
private const val CHAT_RECOVERY_REQUEST_CODE_MASK = 0x2100

/**
 * 订阅 [AppEvent.ChatRecoveryStatus]，把自动恢复的进度与结果变成系统通知。
 *
 * 一次掉线在整个生命周期里只占一个通知身份（tag = 会话 id）：
 * 非终态是低声的进度卡（不震动、不弹窗、只更新不叠加），终态才换成高重要度的提醒。
 * 前台不发：那时行内错误和进度已经看得见，再加一条系统通知只是噪音。
 *
 * 两条通道都用现成的（[CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID] 低声、
 * [CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID] 高重要度），所以不需要新渠道、不新起前台服务；
 * 用户关掉聊天通知开关或有任何渠道被关，这里都跟着安静。
 */
class ChatRecoveryNotificationManager(
    private val context: Application,
    appScope: AppScope,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
) {
    private val notificationLock = Any()
    private val isForeground = MutableStateFlow(false)

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者（AppScope 就是 Main）
        appScope.launch {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            // 进程可能是在界面已经可见之后才建出这个管理器；此时不会再有 ON_START，
            // 不先对齐一次就会把「正在重连」发到用户眼前的那块屏幕上。
            isForeground.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            isForeground.value = true
                            // 回到前台时上一条「正在重连」已经没意义，撤掉；终态提醒留给用户自己划掉
                            appScope.launch(Dispatchers.IO) {
                                synchronized(notificationLock) { clearProgressNotifications() }
                            }
                        }

                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }
        appScope.launch(Dispatchers.IO) {
            // 冷启动兜底：上一进程留下的进度卡没人会撤（恢复状态只存在内存里），
            // 留着只会让人以为还在重连。终态提醒不清，等用户自己划掉。
            synchronized(notificationLock) { clearProgressNotifications() }
        }
        appScope.launch(Dispatchers.IO) {
            eventBus.events.collect { event ->
                if (event !is AppEvent.ChatRecoveryStatus) return@collect
                synchronized(notificationLock) { handleStatus(event) }
            }
        }
    }

    private fun handleStatus(event: AppEvent.ChatRecoveryStatus) {
        val tag = chatRecoveryNotificationTag(event.conversationId)
        // 空文案 = 这一轮已经不需要恢复（成功、停止、取消）：只撤卡片，不再打扰
        if (event.message.isBlank()) {
            context.cancelNotification(CHAT_RECOVERY_PROGRESS_NOTIFICATION_ID, tag)
            context.cancelNotification(CHAT_RECOVERY_RESULT_NOTIFICATION_ID, tag)
            return
        }
        // 前台有行内错误与进度，系统通知只在后台提醒
        if (isForeground.value) {
            cancelProgress(event.conversationId)
            return
        }

        val notificationSetting = settingsStore.settingsFlow.value.displaySetting
        val title = event.senderName.ifBlank { context.getString(R.string.app_name) }

        if (!event.terminal) {
            // 安静进度：用户没开聊天通知或没开 Live Update 就完全不出声
            if (!notificationSetting.enableNotificationOnMessageGeneration ||
                !notificationSetting.enableLiveUpdateNotification
            ) {
                cancelProgress(event.conversationId)
                return
            }
            // 上一轮的失败提醒不留在列表里，免得「已放弃」和「正在重连」同时存在
            context.cancelNotification(CHAT_RECOVERY_RESULT_NOTIFICATION_ID, tag)
            context.sendNotification(
                channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                notificationId = CHAT_RECOVERY_PROGRESS_NOTIFICATION_ID,
            ) {
                this.tag = tag
                this.title = title
                this.content = event.message
                subText = context.getString(R.string.r21_recovery_progress_subtext)
                ongoing = true
                onlyAlertOnce = true
                useBigTextStyle = true
                category = NotificationCompat.CATEGORY_PROGRESS
                contentIntent = recoveryPendingIntent(event.conversationId)
                // 进程被杀时这条没人撤，靠系统超时兜底
                timeoutAfterMs = CHAT_RECOVERY_PROGRESS_TIMEOUT_MS
            }
            return
        }

        // 终态：撤掉进度卡，换一条需要人看的提醒。权限被拒时 sendNotification 直接返回 false
        cancelProgress(event.conversationId)
        if (!notificationSetting.enableNotificationOnMessageGeneration) {
            context.cancelNotification(CHAT_RECOVERY_RESULT_NOTIFICATION_ID, tag)
            return
        }
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = CHAT_RECOVERY_RESULT_NOTIFICATION_ID,
        ) {
            this.tag = tag
            this.title = title
            this.content = event.message
            subText = context.getString(R.string.r21_recovery_terminal_subtext)
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_ERROR
            contentIntent = recoveryPendingIntent(event.conversationId)
        }
    }

    private fun cancelProgress(conversationId: Uuid) {
        context.cancelNotification(
            CHAT_RECOVERY_PROGRESS_NOTIFICATION_ID,
            chatRecoveryNotificationTag(conversationId),
        )
    }

    /** 只清自己的进度卡：终态提醒留给用户划掉，前台服务的通知一个都不碰 */
    private fun clearProgressNotifications() {
        NotificationUtil.cancelWhere(context) { id, tag ->
            id == CHAT_RECOVERY_PROGRESS_NOTIFICATION_ID &&
                tag != null &&
                tag.startsWith(CHAT_RECOVERY_TAG_PREFIX) &&
                tag.length > CHAT_RECOVERY_TAG_PREFIX.length
        }
    }

    private fun recoveryPendingIntent(conversationId: Uuid): PendingIntent {
        // 显式 Intent + 按用途和会话唯一的 data URI：互不覆盖，也不给外部应用留口子
        val intent = conversationIntent(context, conversationId.toString())
            .setData(chatNotificationPendingIntentUri(PENDING_INTENT_PURPOSE_RECOVERY, conversationId).toUri())
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode() xor CHAT_RECOVERY_REQUEST_CODE_MASK,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
