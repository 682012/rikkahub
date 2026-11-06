package me.rerere.rikkahub.data.organization.autofile

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * 每个助手一个**唯一**的周期任务（12 小时一批），只在开关打开时排；关掉就取消。
 *
 * 为什么用 WorkManager 而不是常驻协程/页面里的循环：
 * - 由系统调度，App 进程被杀、页面关掉、主会话没在生成，到点仍会拉起（系统省电策略下可能延后，
 *   这里不承诺精确到点、也不承诺永不被杀，见 README「已知取舍」）；
 * - `enqueueUniquePeriodicWork(名字, UPDATE, ...)` 保证同一助手只有一条任务，不会因为多次
 *   `initialize()` / 反复切开关叠出多条并发路径；
 * - 约束按模式给：AI 模式要联网（不发网也跑不了），本地规则模式完全离线，不需要网络；
 * - Worker 每轮拿到 Controller 后**重新读设置**，所以「刚关掉、任务还没来得及取消」也不会真的跑。
 *
 * 手动「立即整理」是另一个唯一名字的**一次性**任务（见 [enqueueManual]），它带 `KEY_MANUAL=true`，
 * Worker 走 `organizeNow`：不因为自动开关关着就变成 no-op，也仍然守 24h 闲置门槛；
 * 两个名字共享 Controller 的同一把批次锁，所以不会同时搬库。
 */
object ConversationAutoFileWork {
    const val INTERVAL_HOURS = 12L
    const val KEY_ASSISTANT_ID = "assistant_id"

    /** 一次性手动任务标记：true 时 Worker 走 organizeNow（用户显式动作），false 走 organizeScheduled。 */
    const val KEY_MANUAL = "manual"

    fun workName(assistantId: Uuid): String = AutoFileManualPolicy.periodicWorkName(assistantId)

    fun manualWorkName(assistantId: Uuid): String = AutoFileManualPolicy.manualWorkName(assistantId)

    fun schedule(
        context: Context,
        assistantId: Uuid,
        enabled: Boolean,
        mode: AutoFileMode = AutoFileMode.AI,
    ) {
        val manager = WorkManager.getInstance(context.applicationContext ?: context)
        val name = workName(assistantId)
        if (!enabled) {
            manager.cancelUniqueWork(name)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                // 本地规则模式完全不发网，强行要求联网只会让离线用户永远排不上任务。
                if (mode == AutoFileMode.AI) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
            )
            .build()
        val request = PeriodicWorkRequestBuilder<ConversationAutoFileWorker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setInputData(workDataOf(KEY_ASSISTANT_ID to assistantId.toString()))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        // UPDATE：模式/约束变了就换成新请求，同时保持同一条唯一任务，不重复、不叠跑。
        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** 撤掉还没开始跑的手动任务；正在跑的那一批由 Controller.cancelRun 打断。 */
    fun cancelManual(context: Context, assistantId: Uuid) {
        WorkManager.getInstance(context.applicationContext ?: context)
            .cancelUniqueWork(manualWorkName(assistantId))
    }

    /**
     * 排一次**手动**整理，返回真实入队的回执。
     *
     * 唯一名字 + `ExistingWorkPolicy.KEEP`：同一助手已经有任务在排队/运行时不再叠第二个，
     * 此时返回的是**队列里那一个**的 id 和状态（`enqueued=false`），不是这次刚 build 出来、
     * 其实没入队的新 uuid。手动任务不带网络约束（本地模式可离线跑，AI 模式失败会写进 state）。
     */
    suspend fun enqueueManual(context: Context, assistantId: Uuid): AutoFileManualWork =
        withContext(Dispatchers.IO) {
            val manager = WorkManager.getInstance(context.applicationContext ?: context)
            val name = manualWorkName(assistantId)
            val before = runCatching { manager.getWorkInfosForUniqueWork(name).get() }
                .getOrDefault(emptyList())
                .filterNot { it.state.isFinished }
            val request = OneTimeWorkRequestBuilder<ConversationAutoFileWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ASSISTANT_ID to assistantId.toString(),
                        KEY_MANUAL to true,
                    ),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
            val after = runCatching { manager.getWorkInfosForUniqueWork(name).get() }
                .getOrDefault(emptyList())
            val live = after.firstOrNull { !it.state.isFinished }
                ?: after.firstOrNull { it.id == request.id }
                ?: before.firstOrNull()
            AutoFileManualPolicy.manualWork(
                assistantId = assistantId,
                workId = live?.id?.toString(),
                state = live?.state?.name ?: "ENQUEUED",
                enqueued = before.isEmpty() && live?.id == request.id,
            )
        }
}

/** 真实调度器：把 Controller 的开关决定交给 WorkManager。 */
class WorkManagerConversationAutoFileScheduler(
    context: Context,
) : ConversationAutoFileScheduler {
    private val appContext: Context = context.applicationContext ?: context

    override fun schedule(assistantId: Uuid, enabled: Boolean, mode: AutoFileMode) {
        ConversationAutoFileWork.schedule(appContext, assistantId, enabled, mode)
    }

    override suspend fun scheduleManual(assistantId: Uuid): AutoFileManualWork =
        ConversationAutoFileWork.enqueueManual(appContext, assistantId)

    override fun manualWorkName(assistantId: Uuid): String = ConversationAutoFileWork.manualWorkName(assistantId)

    override fun cancelManual(assistantId: Uuid) = ConversationAutoFileWork.cancelManual(appContext, assistantId)
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
        val manual = inputData.getBoolean(ConversationAutoFileWork.KEY_MANUAL, false)
        val controller = runCatching { GlobalContext.get().get<ConversationAutoFileController>() }.getOrNull()
            ?: return Result.failure(workDataOf("error" to "自动归库还没初始化"))
        return try {
            // 手动任务走 organizeNow：自动开关关着也真的执行（用户显式点过，且入队前已确认外发）；
            // 周期任务走 organizeScheduled，每轮重读设置，关掉就直接返回，不产生任何请求。
            // 两条路共用 Controller 的批次锁，撞车时不会同时搬库。
            if (manual) controller.organizeNow(assistantId) else controller.organizeScheduled(assistantId)
            Result.success()
        } catch (cancelled: CancellationException) {
            // 用户关掉了开关或系统回收：不是失败，别让 WorkManager 记一次错误并重试。
            throw cancelled
        } catch (error: Exception) {
            // 这一类是可重试的意外（读库失败等）；Controller 已经把错误写进状态，界面能看到。
            Result.retry()
        }
    }
}
