package me.rerere.rikkahub.automation

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 串行执行器。只读/写作由 runner 声明；引擎本身不知道具体工具是什么。
 *
 * 硬约束：
 * 1. **不重叠**：全局一把锁，且用 tryLock —— 已有 run 在进行时，新的 run 直接记 SKIPPED_BUSY，
 *    不排队、不并发、不重复副作用。
 * 2. **取消是真的取消**：cancel 直接 cancel 在途协程（`Job.cancel`），不是"下一步才检查的标记"。
 *    如果取消发生在写步骤里，那一步记为 UNKNOWN_SIDE_EFFECT 并登记待用户确认，已执行的步骤全部保留。
 * 3. **不跨越审批/失败**：工具要审批、返回失败 → 立刻停在当前步骤。
 * 4. **不重放未知副作用**：写步骤执行前先落盘 RUNNING(sideEffect=true)；写步骤抛异常也记为
 *    UNKNOWN_SIDE_EFFECT（异常 ≠ 确定没生效），绝不标成 FAILED 让下一次自动再跑。
 * 5. **账本是前置条件**：账本读不出来（[AutomationStore.blocked]）就一个 run 都不派发；
 *    新 run 必须先真正落盘才开始执行；写步骤的"写前落盘"失败就**绝不执行**那一步；
 *    执行后再落盘失败，则宁可把 run 降级成 UNKNOWN_SIDE_EFFECT（等用户确认），也不谎报成功。
 * 6. **真实数据接续**：步骤参数里的 `{{下标.字段}}` 用前面步骤的输出替换（见 [AutomationStepVariables]）。
 */
class AutomationEngine(
    private val runner: AutomationToolRunner,
    private val store: AutomationStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    private val _activeRun = MutableStateFlow<AutomationRun?>(null)
    val activeRun: StateFlow<AutomationRun?> = _activeRun.asStateFlow()

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var cancelRequested = false

    /**
     * 取消当前 run（可限定 recipeId）。真正 cancel 在途协程；正在执行的单步如果本身不可中断，
     * 会在它返回后立刻停下，并且那一步记录的"副作用未知"会保留。
     */
    fun cancel(recipeId: String? = null) {
        val active = _activeRun.value ?: return
        if (recipeId != null && active.recipeId != recipeId) return
        cancelRequested = true
        activeJob?.cancel(CancellationException("cancelled by user"))
    }

    suspend fun run(recipe: AutomationRecipe, trigger: AutomationTrigger): AutomationRun {
        store.ensureLoaded()
        if (store.blocked) {
            // 账本坏了/读不出来：绝不拿"空账本"当"以前什么都没做过"放行
            return ledgerRefused(recipe, trigger)
        }
        if (!mutex.tryLock()) {
            // 不排队：排队意味着用户点两次就会真的跑两次
            val skipped = AutomationRun(
                id = newId(),
                recipeId = recipe.id,
                recipeName = recipe.name,
                trigger = trigger,
                startedAt = now(),
                finishedAt = now(),
                status = AutomationRunStatus.SKIPPED_BUSY,
                message = "another automation run is in progress; nothing was executed this time",
            )
            // 什么都没执行，记录写不进去也不该把异常抛给 Worker；但要如实说这条没保存
            return appendQuietly(skipped)
        }
        try {
            return runLocked(recipe, trigger)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runLocked(recipe: AutomationRecipe, trigger: AutomationTrigger): AutomationRun {
        val started = now()
        val catalog = runCatching { runner.tools() }.getOrDefault(emptyList())
        val issues = AutomationRecipeRules.validate(recipe, catalog)
        if (issues.isNotEmpty()) {
            val invalid = AutomationRun(
                id = newId(),
                recipeId = recipe.id,
                recipeName = recipe.name,
                trigger = trigger,
                startedAt = started,
                finishedAt = started,
                status = AutomationRunStatus.INVALID,
                message = issues.joinToString("; ") { it.message },
            )
            return appendQuietly(invalid)
        }

        val blocking = AutomationRunGuard.blockingRun(store.pending.value, store.runs.value, recipe.id)
        if (blocking != null) {
            val blocked = AutomationRun(
                id = newId(),
                recipeId = recipe.id,
                recipeName = recipe.name,
                trigger = trigger,
                startedAt = started,
                finishedAt = started,
                status = AutomationRunStatus.BLOCKED_UNKNOWN_SIDE_EFFECT,
                message = "run ${blocking.runId} may have left an unknown side effect; confirm it in settings before running again",
            )
            return appendQuietly(blocked)
        }

        // 保存时固化的权限域已经和当前绑定助手不一致：一步都不跑，绝不用另一个助手的身份执行
        if (!scopeStillValid(recipe)) {
            val drifted = AutomationRun(
                id = newId(),
                recipeId = recipe.id,
                recipeName = recipe.name,
                trigger = trigger,
                startedAt = started,
                finishedAt = started,
                status = AutomationRunStatus.INVALID,
                message = "the bound assistant changed since this recipe was saved; nothing was executed — " +
                    "save the recipe again under the current binding",
            )
            return appendQuietly(drifted)
        }

        var run = AutomationRun(
            id = newId(),
            recipeId = recipe.id,
            recipeName = recipe.name,
            trigger = trigger,
            startedAt = started,
            status = AutomationRunStatus.RUNNING,
        )
        val outputs = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var index = 0

        // 新 run 必须先真的落盘才开始执行：否则进程死在第一步，历史里既看不到这次 run，
        // 也看不到它可能留下的未知副作用（下次就会毫无顾忌地再跑一遍）。
        val startFailure = persistProblem { store.appendRun(run) }
        if (startFailure != null) {
            return finish(
                run,
                AutomationRunStatus.FAILED,
                run.steps,
                "nothing was executed: this run could not be saved before starting (${describe(startFailure)})",
            )
        }

        cancelRequested = false
        activeJob = currentCoroutineContext()[Job]
        _activeRun.value = run

        try {
            while (index < recipe.steps.size) {
                // 取消在步骤边界同样生效（即使协程没被打断，例如调用方正处在不可取消段）
                currentCoroutineContext().ensureActive()
                if (cancelRequested) return cancelAtBoundary(run, recipe, index)
                // 每步都校验一次权限域：用户中途换绑助手时，下一步绝不能落到另一个助手的授权上
                if (!scopeStillValid(recipe)) {
                    run = stopOnScopeDrift(run, recipe, index)
                    return persistOutcome(run, executedSideEffect = false)
                }

                val step = recipe.steps[index]
                val spec = catalog.firstOrNull { it.name == step.tool }
                if (spec == null) {
                    run = finish(
                        run,
                        AutomationRunStatus.INVALID,
                        run.steps + AutomationStepResult(
                            index,
                            step.tool,
                            AutomationStepStatus.FAILED,
                            "tool is gone",
                            sideEffect = false,
                        ),
                        "tool is no longer available: ${step.tool}",
                    )
                    return persistOutcome(run, executedSideEffect = false)
                }

                val readOnly = spec.readOnly

                val resolved = AutomationStepVariables.resolve(step.arguments, outputs)
                if (!resolved.ok) {
                    // 参数补不出来：这一步不会执行，没必要先写一条"要跑但结果未知"的记录
                    run = replaceStep(
                        run,
                        AutomationStepResult(
                            index,
                            step.tool,
                            AutomationStepStatus.FAILED,
                            "unresolved variable: ${resolved.unresolved.joinToString(", ").take(200)}",
                            sideEffect = false,
                        ),
                    )
                    run = finish(
                        run,
                        AutomationRunStatus.FAILED,
                        run.steps,
                        "step ${index + 1} references data that is not available yet; nothing was executed",
                    )
                    return persistOutcome(run, executedSideEffect = false)
                }

                if (!readOnly) {
                    // 先把"要写但结果未知"落盘；进程死在这一步，下次启动能看出来并阻塞。
                    // 这条记录写不进去就**绝不执行**：否则盘上不会留下任何"可能已生效"的痕迹。
                    run = run.copy(
                        steps = run.steps + AutomationStepResult(
                            index,
                            step.tool,
                            AutomationStepStatus.RUNNING,
                            sideEffect = true,
                        ),
                    )
                    val writeAhead = persistProblem { store.updateRun(run) }
                    if (writeAhead != null) {
                        run = replaceStep(
                            run,
                            AutomationStepResult(
                                index,
                                step.tool,
                                AutomationStepStatus.FAILED,
                                "not executed: the automation ledger could not record this step " +
                                    "(${describe(writeAhead)})",
                                sideEffect = false,
                            ),
                        )
                        return stopOnLedgerFailure(
                            run = run,
                            recipe = recipe,
                            currentIndex = index,
                            executed = false,
                            stepHadSideEffect = true,
                            cause = writeAhead,
                        )
                    }
                }

                val result = try {
                    runner.execute(step.tool, resolved.arguments)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // 写步骤抛异常 = 动作可能已经生效，不能当"确定失败"
                    val status = if (readOnly) {
                        AutomationStepStatus.FAILED
                    } else {
                        AutomationStepStatus.UNKNOWN_SIDE_EFFECT
                    }
                    run = replaceStep(
                        run,
                        AutomationStepResult(
                            index,
                            step.tool,
                            status,
                            "threw: ${(error.message ?: error::class.java.simpleName).take(200)}",
                            sideEffect = !readOnly,
                        ),
                    )
                    run = finish(
                        run,
                        if (readOnly) AutomationRunStatus.FAILED else AutomationRunStatus.UNKNOWN_SIDE_EFFECT,
                        run.steps,
                        if (readOnly) {
                            "step ${index + 1} failed before changing anything"
                        } else {
                            "step ${index + 1} threw while changing state; the change may or may not have happened"
                        },
                    )
                    return persistOutcome(run, executedSideEffect = !readOnly)
                }

                if (result.needsApproval) {
                    run = replaceStep(
                        run,
                        AutomationStepResult(
                            index,
                            step.tool,
                            AutomationStepStatus.FAILED,
                            "needs user approval",
                            sideEffect = !readOnly,
                        ),
                    )
                    run = finish(
                        run,
                        AutomationRunStatus.BLOCKED_NEEDS_APPROVAL,
                        run.steps,
                        "step ${index + 1} needs approval; automation never approves on its own",
                    )
                    return persistOutcome(run, executedSideEffect = !readOnly)
                }

                if (result.sideEffectUnknown) {
                    // ok=true 但 status=unknown（例如发送回执超时）：动作可能已生效，绝不能记 SUCCEEDED
                    run = replaceStep(
                        run,
                        AutomationStepResult(
                            index,
                            step.tool,
                            AutomationStepStatus.UNKNOWN_SIDE_EFFECT,
                            result.message.take(300),
                            sideEffect = true,
                        ),
                    )
                    run = finish(
                        run,
                        AutomationRunStatus.UNKNOWN_SIDE_EFFECT,
                        run.steps,
                        "step ${index + 1} reported an unclear outcome; the change may or may not have happened — " +
                            "confirm before rerunning",
                    )
                    return persistOutcome(run, executedSideEffect = !readOnly)
                }

                if (!result.ok) {
                    // 工具明确报失败：普通情况算 FAILED；但它自己说了"可能改了状态"就按未知处理
                    val uncertain = !readOnly && result.wroteSideEffect
                    run = replaceStep(
                        run,
                        AutomationStepResult(
                            index,
                            step.tool,
                            if (uncertain) AutomationStepStatus.UNKNOWN_SIDE_EFFECT else AutomationStepStatus.FAILED,
                            result.message.take(300),
                            sideEffect = !readOnly || result.wroteSideEffect,
                        ),
                    )
                    run = finish(
                        run,
                        if (uncertain) AutomationRunStatus.UNKNOWN_SIDE_EFFECT else AutomationRunStatus.FAILED,
                        run.steps,
                        if (uncertain) {
                            "step ${index + 1} reported failure after possibly changing state; confirm before rerunning"
                        } else {
                            "step ${index + 1} failed: ${result.message.take(300)}"
                        },
                    )
                    return persistOutcome(run, executedSideEffect = !readOnly)
                }

                outputs.add(result.output)
                run = replaceStep(
                    run,
                    AutomationStepResult(
                        index,
                        step.tool,
                        AutomationStepStatus.SUCCEEDED,
                        result.message.take(300),
                        sideEffect = !readOnly || result.wroteSideEffect,
                    ),
                )
                val stepSaved = persistProblem { store.updateRun(run) }
                if (stepSaved != null) {
                    // 这一步已经执行了，但结果没落盘：立刻停手，后面的步骤一步都不跑
                    return stopOnLedgerFailure(
                        run = run,
                        recipe = recipe,
                        currentIndex = index,
                        executed = true,
                        stepHadSideEffect = !readOnly || result.wroteSideEffect,
                        cause = stepSaved,
                    )
                }
                index++
            }

            run = finish(run, AutomationRunStatus.SUCCEEDED, run.steps, "done")
            val doneSaved = persistProblem { store.updateRun(run) }
            if (doneSaved == null) return run
            // 最后一行没落盘：盘上可能还停在 RUNNING，重启会被读成"没生效、可以再跑"，
            // 所以不能就这么算成功——有写步骤就降级成未知副作用，等用户确认。
            val degraded = run.copy(
                status = if (run.steps.any { it.sideEffect }) {
                    AutomationRunStatus.UNKNOWN_SIDE_EFFECT
                } else {
                    AutomationRunStatus.FAILED
                },
                message = "all steps ran, but this run could not be saved (${describe(doneSaved)}); " +
                    "confirm before rerunning",
            )
            persistProblem { store.updateRun(degraded) }
            return degraded
        } catch (cancellation: CancellationException) {
            // 取消/超时：先把真实状态写盘（cancelAtBoundary 内部 NonCancellable），再把取消抛回去
            cancelAtBoundary(run, recipe, index)
            throw cancellation
        } finally {
            activeJob = null
            cancelRequested = false
            if (_activeRun.value?.id == run.id) _activeRun.value = null
        }
    }

    /**
     * 从第 [fromIndex] 步开始取消。已经执行过的步骤原样保留；
     * 如果取消/中断发生在写步骤里（那一步还挂着 RUNNING + sideEffect），
     * 它记为 UNKNOWN_SIDE_EFFECT 并把整个 run 标成 UNKNOWN_SIDE_EFFECT —— 需要用户确认才能再跑。
     */
    private suspend fun cancelAtBoundary(
        run: AutomationRun,
        recipe: AutomationRecipe,
        fromIndex: Int,
    ): AutomationRun {
        var unknown = false
        val kept = run.steps.map { step ->
            if (step.status == AutomationStepStatus.RUNNING) {
                if (step.sideEffect) {
                    unknown = true
                    step.copy(status = AutomationStepStatus.UNKNOWN_SIDE_EFFECT, detail = "cancelled mid-step; the change may or may not have happened")
                } else {
                    step.copy(status = AutomationStepStatus.CANCELLED)
                }
            } else {
                step
            }
        }
        val alreadyRecorded = kept.map { it.index }.toSet()
        val rest = recipe.steps.withIndex()
            .filter { it.index >= fromIndex && it.index !in alreadyRecorded }
            .map { AutomationStepResult(index = it.index, tool = it.value.tool, status = AutomationStepStatus.SKIPPED) }
        val steps = (kept + rest).sortedBy { it.index }
        val finished = run.copy(
            status = if (unknown) AutomationRunStatus.UNKNOWN_SIDE_EFFECT else AutomationRunStatus.CANCELLED,
            steps = steps,
            finishedAt = now(),
            message = if (unknown) {
                "cancelled during a write step; the change may or may not have happened"
            } else {
                "cancelled by user"
            },
        )
        // updateRun 会看到 UNKNOWN_SIDE_EFFECT 并登记待确认记录（store 内部负责，且不受清历史影响）。
        // 落盘失败不能掩盖取消：状态尽力保存，取消照原样往上抛。
        withContext(NonCancellable) {
            runCatching { store.updateRun(finished) }
        }
        return finished
    }

    /**
     * 账本写失败：本 run 立刻停手，剩下的步骤一律 SKIPPED（一步都不执行）。
     *
     * [executed] = 当前这一步是否**真的已经执行**：只有执行过才可能是"未知副作用"；
     * 没执行的必须是 FAILED（确定没动）——两者绝不能混成一个状态。
     */
    private suspend fun stopOnLedgerFailure(
        run: AutomationRun,
        recipe: AutomationRecipe,
        currentIndex: Int,
        executed: Boolean,
        stepHadSideEffect: Boolean,
        cause: Throwable,
    ): AutomationRun {
        val uncertain = executed && stepHadSideEffect
        val confirmNote = if (uncertain) " — confirm before rerunning" else ""
        val message = if (executed) {
            "step ${currentIndex + 1} ran but the automation ledger could not record it " +
                "(${describe(cause)}); the remaining steps were not executed$confirmNote"
        } else {
            "step ${currentIndex + 1} was not executed because the automation ledger could not be " +
                "updated (${describe(cause)}); the remaining steps were not executed"
        }
        val stopped = stopRemaining(
            run = run,
            recipe = recipe,
            fromIndex = currentIndex,
            status = if (uncertain) AutomationRunStatus.UNKNOWN_SIDE_EFFECT else AutomationRunStatus.FAILED,
            message = message,
            skipDetail = "not executed: the automation ledger could not be updated",
        )
        // 尽力而为：多半还是失败，但绝不能留下一条"看起来成功"的记录
        persistProblem { store.updateRun(stopped) }
        return stopped
    }

    /**
     * 落盘一次已经不会再执行下一步的终态。写失败不能假成功：
     * 有副作用的步骤 → 降级成 UNKNOWN_SIDE_EFFECT（等确认）；只读 → 只把原因写进消息。
     */
    private suspend fun persistOutcome(run: AutomationRun, executedSideEffect: Boolean): AutomationRun {
        val failure = persistProblem { store.updateRun(run) } ?: return run
        if (!executedSideEffect) {
            return run.copy(message = "${run.message} (this outcome could not be saved: ${describe(failure)})".take(400))
        }
        // 写步骤：盘上可能还留着"RUNNING(sideEffect)"的写前记录，重启就会读成未知副作用，
        // 所以这里也按未知副作用收口，绝不标成"确定成功"或"确定失败"。
        val degraded = run.copy(
            status = AutomationRunStatus.UNKNOWN_SIDE_EFFECT,
            finishedAt = run.finishedAt ?: now(),
            message = "${run.message}; this outcome could not be saved (${describe(failure)}) — " +
                "confirm before rerunning",
        )
        persistProblem { store.updateRun(degraded) }
        return degraded
    }

    /** 只记录"什么都没执行"的结果（跳过/校验失败/已阻塞）：账本写不进去也不抛给调用方，但要如实标注。 */
    private suspend fun appendQuietly(run: AutomationRun): AutomationRun {
        val failure = persistProblem { store.appendRun(run) } ?: return run
        return run.copy(message = "${run.message} (this record could not be saved: ${describe(failure)})".take(400))
    }

    private suspend fun persistProblem(action: suspend () -> Unit): Throwable? = try {
        action()
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        error
    }

    private fun replaceStep(run: AutomationRun, step: AutomationStepResult): AutomationRun =
        run.copy(steps = run.steps.filterNot { it.index == step.index } + step)

    /** 账本写失败时把后续步骤标成 SKIPPED（一步都没执行），再按 [status] 收尾。 */
    private fun stopRemaining(
        run: AutomationRun,
        recipe: AutomationRecipe,
        fromIndex: Int,
        status: AutomationRunStatus,
        message: String,
        skipDetail: String,
    ): AutomationRun {
        val recorded = run.steps.map { it.index }.toSet()
        val rest = recipe.steps.withIndex()
            .filter { it.index > fromIndex && it.index !in recorded }
            .map {
                AutomationStepResult(
                    index = it.index,
                    tool = it.value.tool,
                    status = AutomationStepStatus.SKIPPED,
                    detail = skipDetail,
                )
            }
        return finish(run, status, run.steps + rest, message)
    }

    /**
     * 固化域是否仍然有效。没有 scope 的旧配方（保存前就存在）返回 true，照旧可跑；
     * runner 自己抛异常时按"校验不了 → 不许继续"处理。
     */
    private fun scopeStillValid(recipe: AutomationRecipe): Boolean {
        val frozen = recipe.scope ?: return true
        return runCatching { runner.validateRunScope(frozen) }.getOrDefault(false)
    }

    /** 绑定助手在运行中途变了：后面的步骤一律不执行（不是失败重试，也不是未知副作用）。 */
    private fun stopOnScopeDrift(run: AutomationRun, recipe: AutomationRecipe, fromIndex: Int): AutomationRun {
        val recorded = run.steps.map { it.index }.toSet()
        val rest = recipe.steps.withIndex()
            .filter { it.index >= fromIndex && it.index !in recorded }
            .map {
                AutomationStepResult(
                    index = it.index,
                    tool = it.value.tool,
                    status = AutomationStepStatus.SKIPPED,
                    detail = "not executed: the bound assistant changed",
                )
            }
        return finish(
            run,
            AutomationRunStatus.FAILED,
            run.steps + rest,
            "the bound assistant changed while this recipe was running; step ${fromIndex + 1} and later " +
                "were not executed",
        )
    }

    private fun finish(
        run: AutomationRun,
        status: AutomationRunStatus,
        steps: List<AutomationStepResult>,
        message: String,
    ): AutomationRun = run.copy(
        status = status,
        steps = steps.sortedBy { it.index },
        finishedAt = now(),
        message = message,
    )

    private fun ledgerRefused(recipe: AutomationRecipe, trigger: AutomationTrigger): AutomationRun {
        val at = now()
        return AutomationRun(
            id = newId(),
            recipeId = recipe.id,
            recipeName = recipe.name,
            trigger = trigger,
            startedAt = at,
            finishedAt = at,
            status = AutomationRunStatus.INVALID,
            message = "nothing was executed: ${store.healthMessage ?: "the automation ledger is not readable"}; " +
                "the stored recipes, history and pending confirmations were left untouched",
        )
    }

    private fun describe(error: Throwable): String =
        (error.message ?: error::class.java.simpleName).take(200)
}
