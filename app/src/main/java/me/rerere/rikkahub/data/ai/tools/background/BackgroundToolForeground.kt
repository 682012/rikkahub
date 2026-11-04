package me.rerere.rikkahub.data.ai.tools.background

import android.content.Context
import me.rerere.rikkahub.service.ChatGenerationForegroundService
import kotlin.uuid.Uuid

/**
 * 后台执行期间"进程别被回收"的租约。
 *
 * 直接复用 [ChatGenerationForegroundService]（它本来就是"生成还在跑就别杀"），每个后台任务用
 * 自己的 taskId 拿一份租约：任务结束/取消/失败都释放，不会因为一条工具调用把通知永久留住。
 *
 * 抽成接口是为了让 manager 在 JVM 单测里完全不碰 Android（[None]）。
 */
interface BackgroundForegroundLease {
    fun acquire(taskId: Uuid, conversationId: Uuid): Boolean

    fun release(taskId: Uuid)

    /** 无租约：单测与没有会话归属的场景。 */
    object None : BackgroundForegroundLease {
        override fun acquire(taskId: Uuid, conversationId: Uuid): Boolean = false
        override fun release(taskId: Uuid) = Unit
    }
}

/** 真机实现：交给 [ChatGenerationForegroundService]。 */
class AndroidBackgroundForegroundLease(context: Context) : BackgroundForegroundLease {
    private val appContext: Context = context.applicationContext

    override fun acquire(taskId: Uuid, conversationId: Uuid): Boolean =
        ChatGenerationForegroundService.acquire(appContext, taskId, conversationId)

    override fun release(taskId: Uuid) = ChatGenerationForegroundService.release(appContext, taskId)
}
