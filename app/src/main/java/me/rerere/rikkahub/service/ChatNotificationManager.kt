package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.event.CompressionOutcome
import me.rerere.rikkahub.data.model.GenerationPhase
import me.rerere.rikkahub.data.model.generationPhase
import me.rerere.rikkahub.utils.NotificationUtil
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

// Live Update 通知节流间隔：流式输出每个chunk都会触发一次更新，
// notify() 是 binder IPC 且系统本身会对高频更新限流，必须在应用侧节流
private const val LIVE_UPDATE_NOTIFICATION_THROTTLE_MS = 1000L

// 手动压缩用自己的一组通知身份，不和生成完成（id=1）、Live Update（2006）、自动恢复（2009/2010）
// 互相覆盖；身份常量集中在 NotificationIdentity.kt，测试在那里核对互不相同。

// 进程被杀时进度卡没人来撤，靠系统超时兜底（30 分钟），结果通知照常到时单独发
private const val CHAT_COMPRESSION_PROGRESS_TIMEOUT_MS = 30 * 60 * 1000L

/**
 * 订阅 [AppEventBus] 上的聊天生成事件，负责后台生成相关的系统通知
 * （Live Update 进度通知和生成完成通知），以及手动压缩的进度/结果通知。
 */
class ChatNotificationManager(
    private val context: Application,
    appScope: AppScope,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
) {
    private val notificationLock = Any()
    private val isForeground = MutableStateFlow(false)
    private val liveUpdateLastSentAt = ConcurrentHashMap<Uuid, Long>()

    // 已经发过结果通知的压缩任务，保证同一个任务只发一次（受 notificationLock 保护）
    private val notifiedCompressionTasks = ArrayDeque<Uuid>()

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            // 进程可能是在界面已经可见之后才建出这个管理器（此时不会再有 ON_START）；
            // 不先对齐一次，就会把后台才该发的进度/完成通知发到用户眼前的屏幕上。
            isForeground.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            isForeground.value = true
                            appScope.launch(Dispatchers.IO) {
                                synchronized(notificationLock) { clearStaleLiveUpdates() }
                            }
                        }
                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }
        appScope.launch(Dispatchers.IO) {
            synchronized(notificationLock) { clearStaleLiveUpdates() }
        }
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                synchronized(notificationLock) {
                    when (event) {
                        is AppEvent.ChatGenerationUpdate -> handleGenerationUpdate(event)
                        is AppEvent.ChatGenerationEnded -> handleGenerationEnded(event)
                        is AppEvent.ChatCompressionStarted -> handleCompressionStarted(event)
                        is AppEvent.ChatCompressionEnded -> handleCompressionEnded(event)
                        else -> {}
                    }
                }
            }
        }
    }

    private fun handleGenerationUpdate(event: AppEvent.ChatGenerationUpdate) {
        if (isForeground.value) return
        val displaySetting = settingsStore.settingsFlow.value.displaySetting
        if (!displaySetting.enableNotificationOnMessageGeneration) return
        if (!displaySetting.enableLiveUpdateNotification) return

        val now = SystemClock.elapsedRealtime()
        val lastSentAt = liveUpdateLastSentAt[event.conversationId]
        if (lastSentAt != null && now - lastSentAt < LIVE_UPDATE_NOTIFICATION_THROTTLE_MS) return
        liveUpdateLastSentAt[event.conversationId] = now

        sendLiveUpdateNotification(event.conversationId, event.lastMessage, event.senderName)
    }

    private fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
        cancelLiveUpdateNotification(event.conversationId)

        val contentPreview = event.contentPreview ?: return
        if (isForeground.value) return
        if (!settingsStore.settingsFlow.value.displaySetting.enableNotificationOnMessageGeneration) return
        sendGenerationDoneNotification(event.conversationId, event.senderName, contentPreview)
    }

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        contentPreview: String
    ) {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            // 每条完成通知带自己的会话 tag：不同对话的完成卡不再互相覆盖
            tag = chatDoneTag(conversationId)
            title = senderName
            content = contentPreview
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId, PENDING_INTENT_PURPOSE_CHAT_DONE)
        }
    }

    // ---- 手动压缩 ----

    /**
     * 压缩进度卡：只在 App 不在前台时发（前台有弹窗和列表状态，不再加一条系统通知）。
     * 它和前台服务卡片（2002）、Live Update（2006）都是不同身份，不会互相覆盖。
     */
    private fun handleCompressionStarted(event: AppEvent.ChatCompressionStarted) {
        if (isForeground.value) return
        if (!compressionNotificationsEnabled()) return
        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = CHAT_COMPRESSION_PROGRESS_NOTIFICATION_ID,
        ) {
            tag = chatCompressionNotificationTag(event.conversationId)
            title = event.title.ifBlank { context.getString(R.string.app_name) }
            content = context.getString(R.string.r11_compression_progress_text)
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            contentIntent = getPendingIntent(context, event.conversationId, PENDING_INTENT_PURPOSE_COMPRESSION)
            // 进程被杀时这条通知没人来撤，到期由系统收走；结果通知另有身份
            timeoutAfterMs = CHAT_COMPRESSION_PROGRESS_TIMEOUT_MS
        }
    }

    /**
     * 压缩结果：成功可点开原对话，失败/丢弃说明对话没有被改。
     *
     * 用独立的 id 和 tag，普通生成完成通知（id=1）不会冒充压缩结果；
     * 同一个 taskId 只发一次，避免重复事件发两条。
     */
    private fun handleCompressionEnded(event: AppEvent.ChatCompressionEnded) {
        // 不管后面发不发结果通知，进度卡都要撤掉
        context.cancelNotification(
            CHAT_COMPRESSION_PROGRESS_NOTIFICATION_ID,
            chatCompressionNotificationTag(event.conversationId),
        )
        if (event.outcome == CompressionOutcome.Cancelled) return
        if (!compressionNotificationsEnabled()) return
        if (!markCompressionResultNotified(event.taskId)) return

        val title = event.title.ifBlank { context.getString(R.string.app_name) }
        val content = when (event.outcome) {
            CompressionOutcome.Succeeded -> context.getString(R.string.r11_compression_result_success_text)
            CompressionOutcome.Failed -> context.getString(
                R.string.r11_compression_result_failed_text,
                event.detail?.take(200) ?: context.getString(R.string.r11_compression_result_unknown_error),
            )

            CompressionOutcome.Discarded -> context.getString(R.string.r11_compression_result_discarded_text)
            CompressionOutcome.Cancelled -> return
        }
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = CHAT_COMPRESSION_RESULT_NOTIFICATION_ID,
        ) {
            tag = chatCompressionNotificationTag(event.conversationId)
            this.title = title
            this.content = content
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, event.conversationId, PENDING_INTENT_PURPOSE_COMPRESSION)
        }
    }

    /** 同一个压缩任务的结果只通知一次（受 notificationLock 保护） */
    private fun markCompressionResultNotified(taskId: Uuid): Boolean {
        if (notifiedCompressionTasks.contains(taskId)) return false
        notifiedCompressionTasks.addLast(taskId)
        while (notifiedCompressionTasks.size > 32) notifiedCompressionTasks.removeFirst()
        return true
    }

    // 手动压缩的完成通知独立于普通聊天生成通知。
    private fun compressionNotificationsEnabled(): Boolean =
        settingsStore.settingsFlow.value.displaySetting.enableNotificationOnCompression

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        lastMessage: UIMessage,
        senderName: String
    ) {
        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(lastMessage)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            // Each conversation owns its progress notification; never replace the FGS notification.
            notificationId = CHAT_LIVE_UPDATE_NOTIFICATION_ID
        ) {
            tag = chatLiveUpdateNotificationTag(conversationId)
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId, PENDING_INTENT_PURPOSE_LIVE_UPDATE)
            requestPromotedOngoing = true
            shortCriticalText = chipText
            // A progress card is advisory. Five minutes without an update clears it;
            // the separate foreground service and the running task are not stopped.
            timeoutAfterMs = CHAT_LIVE_UPDATE_NOTIFICATION_TIMEOUT_MS
        }
    }

    private fun determineNotificationContent(message: UIMessage): Triple<String, String, String> =
        when (val phase = message.generationPhase()) {
            is GenerationPhase.RunningTool -> Triple(
                context.getString(R.string.notification_live_update_chip_tool),
                context.getString(R.string.notification_live_update_tool, phase.toolName),
                phase.input.take(100)
            )

            is GenerationPhase.Thinking -> Triple(
                context.getString(R.string.notification_live_update_chip_thinking),
                context.getString(R.string.notification_live_update_thinking),
                phase.reasoning.takeLast(200)
            )

            is GenerationPhase.Writing -> Triple(
                context.getString(R.string.notification_live_update_chip_writing),
                context.getString(R.string.notification_live_update_writing),
                phase.text.takeLast(200)
            )

            GenerationPhase.Waiting -> Triple(
                context.getString(R.string.notification_live_update_chip_writing),
                context.getString(R.string.notification_live_update_title),
                ""
            )
        }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        liveUpdateLastSentAt.remove(conversationId)
        // Cancel only this conversation; the shared foreground-service notification has a separate identity.
        context.cancelNotification(CHAT_LIVE_UPDATE_NOTIFICATION_ID, chatLiveUpdateNotificationTag(conversationId))
    }

    private fun clearStaleLiveUpdates() {
        liveUpdateLastSentAt.clear()
        NotificationUtil.cancelWhere(context, ::isChatLiveUpdateNotification)
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid, purpose: String): PendingIntent {
        // 显式 Intent + 唯一 data URI：hashCode 撞车的两个会话，或同一会话的不同用途，都不会被系统
        // 当成同一个 PendingIntent 而互相覆盖
        val intent = conversationIntent(context, conversationId.toString())
            .setData(chatNotificationPendingIntentUri(purpose, conversationId).toUri())
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
