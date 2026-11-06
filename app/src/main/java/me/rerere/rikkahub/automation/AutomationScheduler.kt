package me.rerere.rikkahub.automation

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次"真的排上了"的回执：WorkManager 接受了请求，并且能查到对应的 WorkInfo。
 * 调用方拿它回给用户/模型，而不是自己编一个 queued。
 */
data class AutomationScheduleReceipt(
    val recipeId: String,
    val workName: String,
    /** true = 手动一次性任务（允许配方 disabled），false = 周期任务。 */
    val manual: Boolean,
    val workIds: List<String>,
    /** WorkInfo.State 名字（ENQUEUED / RUNNING / ...），来自 WorkManager 自己的查询结果。 */
    val state: String,
)

/** 排队失败：WorkManager 没初始化、enqueue 被拒、或排完查不到 work。调用方必须当成失败，不能假成功。 */
class AutomationScheduleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 打开/关闭定时的结果。给 UI/HTTP 同一个东西：要么真的排上了（[receipt]），
 * 要么明确说没排上（[failure]）——不存在"看起来排上了"。
 */
data class AutomationScheduleOutcome(
    val enabled: Boolean,
    val receipt: AutomationScheduleReceipt? = null,
    val failure: String? = null,
) {
    val ok: Boolean get() = failure == null
}

/**
 * 周期执行交给 WorkManager：系统在合适的时候拉起，App 没在跑也能跑（受省电策略影响，不承诺准点）。
 * 周期下限 15 分钟，和 UI 里的提示一致；手动运行不经过 WorkManager（见 AutomationController.runNowAsync）。
 *
 * 两条硬要求：
 * - **不许吞错误**：enqueue 后必须等到 Operation 成功、并查到 WorkInfo 才算排上；否则抛
 *   [AutomationScheduleException]，让保存/调用方看到真实结果（以前吞掉异常后外层照回 queued，等于骗人）。
 * - **一次性任务用 KEEP**：REPLACE 会取消正在执行的那次写步骤，"再点一次"不应该变成"打断正在跑的那次"。
 */
class AutomationScheduler(private val context: Context) {

    /** 返回 null = 这条配方不该有周期任务（未启用或周期 < 下限），已顺手撤掉旧任务。 */
    suspend fun schedule(recipe: AutomationRecipe): AutomationScheduleReceipt? {
        if (!recipe.enabled || recipe.intervalMinutes < AutomationRecipeRules.MIN_INTERVAL_MINUTES) {
            cancel(recipe.id)
            return null
        }
        val request = PeriodicWorkRequestBuilder<AutomationWorker>(recipe.intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    AutomationWorker.KEY_RECIPE_ID to recipe.id,
                    AutomationWorker.KEY_MANUAL to false,
                ),
            )
            .setConstraints(Constraints.NONE)
            .build()
        val name = workName(recipe.id)
        val workManager = workManagerOrThrow()
        val operation = try {
            workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
        } catch (error: Throwable) {
            throw AutomationScheduleException("scheduling ${recipe.id} failed: ${error.message ?: error::class.simpleName}", error)
        }
        awaitAccepted(operation, "schedule ${recipe.id}")
        val infos = workInfos(name)
        if (infos.isEmpty()) {
            throw AutomationScheduleException("WorkManager accepted the schedule for ${recipe.id} but reports no work ($name)")
        }
        return AutomationScheduleReceipt(
            recipeId = recipe.id,
            workName = name,
            manual = false,
            workIds = infos.map { it.id.toString() },
            state = infos.first().state.name,
        )
    }

    fun cancel(recipeId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(recipeId)) }
            .onFailure { Log.w(TAG, "cancel failed for $recipeId", it) }
    }

    /**
     * 一次性后台跑一遍。唯一任务名 + **KEEP**：已经在排队/正在跑时保持原样，
     * 不会取消正在执行的写步骤（REPLACE 会）。
     */
    suspend fun runOnce(recipeId: String, manual: Boolean = true): AutomationScheduleReceipt {
        val request = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInputData(
                workDataOf(
                    AutomationWorker.KEY_RECIPE_ID to recipeId,
                    AutomationWorker.KEY_MANUAL to manual,
                ),
            )
            .build()
        val name = onceName(recipeId)
        val workManager = workManagerOrThrow()
        val operation = try {
            workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        } catch (error: Throwable) {
            throw AutomationScheduleException("run-once $recipeId failed: ${error.message ?: error::class.simpleName}", error)
        }
        awaitAccepted(operation, "run-once $recipeId")
        val infos = workInfos(name)
        if (infos.isEmpty()) {
            throw AutomationScheduleException("WorkManager accepted the run for $recipeId but reports no work ($name)")
        }
        return AutomationScheduleReceipt(
            recipeId = recipeId,
            workName = name,
            manual = manual,
            workIds = infos.map { it.id.toString() },
            state = infos.first().state.name,
        )
    }

    fun cancelOnce(recipeId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(onceName(recipeId)) }
            .onFailure { Log.w(TAG, "cancelOnce failed for $recipeId", it) }
    }

    private fun workManagerOrThrow(): WorkManager = try {
        WorkManager.getInstance(context)
    } catch (error: Throwable) {
        throw AutomationScheduleException(
            "WorkManager is not available, nothing was scheduled: ${error.message ?: error::class.simpleName}",
            error,
        )
    }

    /** 等 WorkManager 自己确认这次 enqueue；失败就抛，绝不静默。 */
    private suspend fun awaitAccepted(operation: Operation, what: String) {
        val failure = withContext(Dispatchers.IO) { runCatching { operation.result.get() }.exceptionOrNull() }
        if (failure != null) {
            val cause = (failure as? ExecutionException)?.cause ?: failure
            throw AutomationScheduleException(
                "$what was rejected by WorkManager: ${cause.message ?: cause::class.simpleName}",
                cause,
            )
        }
    }

    private suspend fun workInfos(name: String): List<WorkInfo> = withContext(Dispatchers.IO) {
        runCatching { workManagerOrThrow().getWorkInfosForUniqueWork(name).get() }
            .getOrElse { error -> throw AutomationScheduleException("cannot read work state for $name: ${error.message}", error) }
    }

    private fun workName(recipeId: String): String = "local-automation-$recipeId"
    private fun onceName(recipeId: String): String = "local-automation-once-$recipeId"

    private companion object {
        const val TAG = "AutomationScheduler"
    }
}

/**
 * 进程级的 controller 入口：主代理在 DI 里 [install] 一次即可（见 device/WIRING.md）。
 * 不直接依赖 Koin 的 GlobalContext —— 宿主 App 若用 KoinApplication 而不是 startKoin，
 * GlobalContext.get() 会抛，Worker 就永远跑不起来。
 */
object AutomationWorkerAccess {
    @Volatile
    private var controller: AutomationController? = null

    fun install(instance: AutomationController?) {
        controller = instance
    }

    fun current(): AutomationController? = controller
}

/**
 * Worker 不自己持有状态：从 [AutomationWorkerAccess] 拿 Controller（主代理注册），跑完把结果写进 history。
 * 任何失败都不重试 —— 自动重试等于重复副作用，这是本模块明确禁止的。
 *
 * 触发类型从输入区分（[KEY_MANUAL]），不再把两种都当成 WORKER：
 * - 周期任务（SCHEDULED）必须**当前**仍是 enabled 且周期 >= 15 分钟。用户关掉定时后，
 *   已经排上的那次可能还会被系统拉起，这里必须自己放弃，否则"关掉了还在跑"。
 * - 手动任务（MANUAL）允许 disabled 的配方。
 */
class AutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recipeId = inputData.getString(KEY_RECIPE_ID)?.trim().orEmpty()
        if (recipeId.isEmpty()) return@withContext Result.failure()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val controller = AutomationWorkerAccess.current() ?: return@withContext Result.failure()
        // store 没加载时 recipes 是空的：先把真实配方读进来，enabled / 周期才有意义
        controller.initialize()
        val recipe = controller.recipes.value.firstOrNull { it.id == recipeId }
            ?: return@withContext Result.success() // 配方已被删除：没有可跑的东西
        if (!manual) {
            val stillScheduled = recipe.enabled &&
                recipe.intervalMinutes >= AutomationRecipeRules.MIN_INTERVAL_MINUTES
            if (!stillScheduled) return@withContext Result.success() // 定时已被关掉，这次不跑
        }
        val trigger = if (manual) AutomationTrigger.MANUAL else AutomationTrigger.SCHEDULED
        val run = controller.runRecipe(recipeId, trigger) ?: return@withContext Result.success()
        when (run.status) {
            AutomationRunStatus.SUCCEEDED -> Result.success()
            // 不确定/失败/取消都不重试：重试就是自动再跑一遍副作用
            else -> Result.failure()
        }
    }

    companion object {
        const val KEY_RECIPE_ID = "recipe_id"
        /** true = 手动触发（允许 disabled），false/缺省 = 周期触发（必须仍 enabled）。 */
        const val KEY_MANUAL = "manual_run"
    }
}
