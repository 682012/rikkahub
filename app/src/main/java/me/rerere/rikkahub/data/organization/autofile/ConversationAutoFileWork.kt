package me.rerere.rikkahub.data.organization.autofile

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

/**
 * 每个助手一个唯一的周期任务（12 小时一次），**只在开关打开时排**；关掉就取消。
 *
 * 不需要常驻循环：WorkManager 自己唤醒，Worker 拿到 Controller 后再读一次设置，
 * 所以「用户刚关掉、任务还没来得及取消」也不会真的跑分类。
 */
object ConversationAutoFileWork {
    const val INTERVAL_HOURS = 12L
    const val KEY_ASSISTANT_ID = "assistant_id"

    fun workName(assistantId: Uuid): String = "conversation-autofile-$assistantId"

    fun schedule(context: Context, assistantId: Uuid, enabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val name = workName(assistantId)
        if (!enabled) {
            manager.cancelUniqueWork(name)
            return
        }
        val request = PeriodicWorkRequestBuilder<ConversationAutoFileWorker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setInputData(workDataOf(KEY_ASSISTANT_ID to assistantId.toString()))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** 真实调度器：把 Controller 的开关决定交给 WorkManager。 */
class WorkManagerConversationAutoFileScheduler(
    context: Context,
) : ConversationAutoFileScheduler {
    private val appContext: Context = context.applicationContext ?: context

    override fun schedule(assistantId: Uuid, enabled: Boolean) {
        ConversationAutoFileWork.schedule(appContext, assistantId, enabled)
    }
}

class ConversationAutoFileWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val raw = inputData.getString(ConversationAutoFileWork.KEY_ASSISTANT_ID)
            ?: return Result.failure(workDataOf("error" to "缺少助手 id"))
        val assistantId = runCatching { Uuid.parse(raw) }.getOrNull()
            ?: return Result.failure(workDataOf("error" to "助手 id 无法解析"))
        val controller = runCatching { GlobalContext.get().get<ConversationAutoFileController>() }.getOrNull()
            ?: return Result.failure(workDataOf("error" to "自动归库还没初始化"))
        return try {
            // Controller 内部会重读设置：关闭时直接返回，不产生任何请求。
            controller.organizeScheduled(assistantId)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(workDataOf("error" to (error.message?.take(300) ?: "自动归库失败")))
        }
    }
}
