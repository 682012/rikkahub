package me.rerere.rikkahub.automation

import android.content.Context
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 设置页 / WorkManager / MCP 的唯一入口。主代理只要在 Koin 里注册它（并传入自己的 [AutomationToolRunner]），
 * 就都共用同一份配方、同一份历史、同一份"待确认副作用"记录。
 *
 * 契约里的 `suspend fun runNow(recipeId): AutomationRun` 保留：它等这次 run 真正结束。
 * 设置页按钮想要"点了就走"用 [runNowAsync]。
 */
class AutomationController(
    context: Context,
    private val runner: AutomationToolRunner,
    val store: AutomationStore = AutomationStore(context),
    private val scheduler: AutomationScheduler = AutomationScheduler(context),
    private val engine: AutomationEngine = AutomationEngine(runner, store),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveMutex = Mutex()

    private val reconcileLock = Any()

    @Volatile
    private var reconciled = false

    val recipes: StateFlow<List<AutomationRecipe>> get() = store.recipes
    val runs: StateFlow<List<AutomationRun>> get() = store.runs
    val activeRun: StateFlow<AutomationRun?> get() = engine.activeRun

    /** 必须用户确认才能再跑的记录；清历史不会清掉它。 */
    val pending: StateFlow<List<AutomationPendingBlock>> get() = store.pending

    /**
     * 读盘 + 只重排一次。
     *
     * Worker 靠这里保证 recipes 是真实内容（否则 enabled/周期都是空的）。
     * 重排只在每个 Controller 实例的生命周期里做一次：每次调用都重排会把 WorkManager 当配置中心用，
     * 既浪费又可能把用户刚设的时机推走。
     */
    suspend fun initialize() {
        store.ensureLoaded()
        if (reconciled) return
        synchronized(reconcileLock) {
            if (reconciled) return
            reconciled = true
        }
        reconcileSchedules()
    }

    private suspend fun reconcileSchedules() {
        store.recipes.value.forEach { recipe ->
            if (recipe.enabled && recipe.intervalMinutes >= AutomationRecipeRules.MIN_INTERVAL_MINUTES) {
                runCatching { scheduler.schedule(recipe) }.onFailure { error ->
                    // 不假装排上了：记日志，配方仍是 enabled，下次保存/启动会再试
                    Log.w(TAG, "could not restore the schedule for ${recipe.id}", error)
                }
            } else {
                runCatching { scheduler.cancel(recipe.id) }
            }
        }
    }

    fun tools(): List<AutomationToolSpec> = runCatching { runner.tools() }.getOrDefault(emptyList())

    fun validate(recipe: AutomationRecipe): List<AutomationRecipeIssue> =
        AutomationRecipeRules.validate(recipe, tools())

    fun blockedRecipes(): Map<String, AutomationPendingBlock> =
        store.pending.value.associateBy { it.recipeId }

    suspend fun save(recipe: AutomationRecipe): AutomationSaveResult = saveMutex.withLock {
        store.ensureLoaded()
        val now = System.currentTimeMillis()
        val normalized = recipe.copy(
            name = recipe.name.trim(),
            intervalMinutes = AutomationRecipeRules.normalizeInterval(recipe.intervalMinutes),
            // 权限域在保存时固化：之后用户换了绑定助手，这条配方也不会"跟着"变成另一个助手去执行
            scope = recipe.scope ?: runCatching { runner.currentScope() }.getOrNull(),
            updatedAt = now,
            createdAt = if (recipe.createdAt > 0) recipe.createdAt else now,
        )
        val issues = validate(normalized)
        if (issues.isNotEmpty()) {
            return@withLock AutomationSaveResult.Invalid(issues, issues.joinToString("; ") { it.message })
        }
        store.saveRecipe(normalized)
        // 定时状态跟配方一起生效：关掉就撤任务**并停掉正在跑的**，开着就（重新）排
        if (normalized.enabled && normalized.intervalMinutes >= AutomationRecipeRules.MIN_INTERVAL_MINUTES) {
            try {
                scheduler.schedule(normalized)
            } catch (error: AutomationScheduleException) {
                // 排不上就是没排上：撤掉残留任务、把 enabled 改回 false，并明确告诉调用方
                scheduler.cancel(normalized.id)
                store.saveRecipe(normalized.copy(enabled = false))
                val issue = AutomationRecipeIssue(
                    code = AutomationRecipeErrorCode.SCHEDULING_FAILED,
                    message = "could not schedule this recipe: ${error.message ?: "WorkManager rejected the request"}",
                )
                return@withLock AutomationSaveResult.Invalid(listOf(issue), issue.message)
            }
        } else {
            scheduler.cancel(normalized.id)
            scheduler.cancelOnce(normalized.id)
            engine.cancel(normalized.id)
        }
        AutomationSaveResult.Saved(normalized)
    }

    suspend fun delete(recipeId: String) {
        store.ensureLoaded()
        engine.cancel(recipeId)
        scheduler.cancel(recipeId)
        scheduler.cancelOnce(recipeId)
        store.deleteRecipe(recipeId)
    }

    /**
     * 打开定时可能排不上（WorkManager 不可用）。不抛异常：返回 [AutomationScheduleOutcome]，
     * 让调用方（UI 的协程 / MCP 工具）自己看失败原因，绝不谎报成功。
     */
    suspend fun setScheduled(recipeId: String, enabled: Boolean): AutomationScheduleOutcome {
        store.ensureLoaded()
        val recipe = store.recipes.value.firstOrNull { it.id == recipeId }
            ?: return AutomationScheduleOutcome(enabled, failure = "recipe not found: $recipeId")
        if (enabled && recipe.intervalMinutes < AutomationRecipeRules.MIN_INTERVAL_MINUTES) {
            // 没设周期就不排：不替用户猜一个 15 分钟
            return AutomationScheduleOutcome(
                enabled = false,
                failure = "periodic runs must be at least ${AutomationRecipeRules.MIN_INTERVAL_MINUTES} minutes",
            )
        }
        store.saveRecipe(recipe.copy(enabled = enabled))
        if (enabled) {
            return try {
                val receipt = scheduler.schedule(recipe.copy(enabled = true))
                AutomationScheduleOutcome(enabled = true, receipt = receipt)
            } catch (error: AutomationScheduleException) {
                // 没排上就把 enabled 改回去，别留一个"定时开着但永远不会跑"的配方
                store.saveRecipe(recipe.copy(enabled = false))
                AutomationScheduleOutcome(enabled = false, failure = error.message ?: "WorkManager rejected the request")
            }
        }
        // 关掉定时 = 撤销后台执行：撤 work + 停当前任务，不留"关掉了还在跑"的状态
        scheduler.cancel(recipeId)
        scheduler.cancelOnce(recipeId)
        engine.cancel(recipeId)
        return AutomationScheduleOutcome(enabled = false)
    }

    /** 契约签名：等这次 run 真正跑完（配方不存在时返回 INVALID 记录，不返回 null）。 */
    suspend fun runNow(recipeId: String): AutomationRun = runRecipe(recipeId, AutomationTrigger.MANUAL)
        ?: AutomationRun(
            id = UUID.randomUUID().toString(),
            recipeId = recipeId,
            recipeName = "",
            trigger = AutomationTrigger.MANUAL,
            startedAt = System.currentTimeMillis(),
            finishedAt = System.currentTimeMillis(),
            status = AutomationRunStatus.INVALID,
            message = "recipe not found: $recipeId",
        )

    /** 设置页按钮用：立刻返回，结果看 activeRun/runs。 */
    fun runNowAsync(recipeId: String) {
        scope.launch { runRecipe(recipeId, AutomationTrigger.MANUAL) }
    }

    /** 立刻取消在途协程（不是"下一步再生效"）；写步骤中断会记为未知副作用等确认。 */
    fun cancelNow() {
        val activeRecipeId = engine.activeRun.value?.recipeId
        engine.cancel()
        // 还没轮到执行的那次手动任务也要撤：否则刚点取消，Worker 立刻又把同一条配方跑起来
        if (activeRecipeId != null) {
            scheduler.cancelOnce(activeRecipeId)
        } else {
            store.recipes.value.forEach { recipe -> scheduler.cancelOnce(recipe.id) }
        }
    }

    /** 只取消某个配方的运行（关定时/删配方时用）；排队中的手动任务一起撤。 */
    fun cancelRecipe(recipeId: String) {
        engine.cancel(recipeId)
        scheduler.cancelOnce(recipeId)
    }

    /** 给 Worker / 自动化自测用：等这次 run 真正结束。 */
    suspend fun runRecipe(recipeId: String, trigger: AutomationTrigger): AutomationRun? {
        store.ensureLoaded()
        val recipe = store.recipes.value.firstOrNull { it.id == recipeId } ?: return null
        return engine.run(recipe, trigger)
    }

    /** 用户显式确认"上一轮未知副作用"后放行；只有这里会清掉阻塞记录。 */
    suspend fun acknowledge(runId: String) {
        store.ensureLoaded()
        store.acknowledgeRun(runId)
    }

    /** 清历史。**不会**清掉待确认记录，所以不能靠它绕过副作用确认。 */
    suspend fun clearHistory() {
        store.ensureLoaded()
        store.clearHistory()
    }

    /** 新配方骨架；UI 填名字和步骤后用 [save]。 */
    fun newRecipe(now: Long = System.currentTimeMillis()): AutomationRecipe = AutomationRecipe(
        id = UUID.randomUUID().toString(),
        name = "",
        steps = emptyList(),
        createdAt = now,
        updatedAt = now,
    )

    /** 清空全部配方和定时任务（设置页"清除"用）。待确认记录保留。 */
    suspend fun clearAll() {
        store.ensureLoaded()
        store.recipes.value.forEach { scheduler.cancel(it.id) }
        store.recipes.value.forEach { scheduler.cancelOnce(it.id) }
        store.recipes.value.forEach { engine.cancel(it.id) }
        store.recipes.value.forEach { store.deleteRecipe(it.id) }
        store.clearHistory()
    }

    /** 仅测试/调试：阻塞等一次运行，给纯 JVM 冒烟用。 */
    fun runBlockingForTest(recipeId: String): AutomationRun? =
        runBlocking { runRecipe(recipeId, AutomationTrigger.MANUAL) }

    fun close() {
        scope.cancel()
    }

    private companion object {
        const val TAG = "AutomationController"
    }
}
