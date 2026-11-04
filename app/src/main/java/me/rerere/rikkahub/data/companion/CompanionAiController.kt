package me.rerere.rikkahub.data.companion

import java.util.Locale
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings

/**
 * 小助手的状态机。
 *
 * 负责的事：
 * - 每次触发前问 [CompanionTriggerPolicy]：去重、冷却、预算、在途只有一个；
 * - 真正发请求（通过 [CompanionReplySource]），流式更新阶段；
 * - 超时 / 用户停止 / 窗口关掉时取消，并把阶段写成 CANCELLED；
 * - 记住最近几轮自己的对话（[CompanionMemory]）和最近的互动记录（[interactions]）。
 *
 * 不负责的事：定时器、轮询、渲染。所有状态都是"真的发生了什么"的结果，
 * 界面只读 [state] / [interactions]；没有事件就一直是 IDLE，不发请求。
 *
 * 每一轮都有自己的 run token（[activeRunId]）：
 * - 只有当前的 run 能写状态、能进记忆；
 * - 取消（[stop] / 关窗口）会当场把状态落成 CANCELLED 并标记 [cancelledRunId]，
 *   **不等**协程把取消传出去——source 是阻塞式回调、包装层吞掉取消、flow 收尾时才恢复，
 *   都不能让已经取消的一轮写成 COMPLETED 或者进记忆；
 * - 旧 run 的迟到结果、以及新一轮开始后的旧取消，都因为 token 不匹配而被丢掉。
 *
 * 时间：模型解析和生成都在同一个 [timeoutMillis] 窗口里（解析也可能卡在 IO 上），
 * 超时按"取消 + TIMEOUT"记，既不假装成功也不说成失败。
 *
 * 纯 JVM 实现（不引用 Android / Compose），单测里用假 [CompanionReplySource] 就能覆盖成功、失败、
 * 超时、取消、不合作的取消、去重和限流。
 */
class CompanionAiController(
    private val scope: CoroutineScope,
    private val source: CompanionReplySource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val policy: CompanionTriggerPolicy = CompanionTriggerPolicy(),
    private val timeoutMillis: Long = CompanionLimits.REQUEST_TIMEOUT_MILLIS,
    private val memory: CompanionMemory = CompanionMemory(),
    private val conversationId: () -> Uuid? = { null },
    private val languageTag: () -> String = { Locale.getDefault().toLanguageTag() },
) {
    private val _state = MutableStateFlow(CompanionAiState())
    val state: StateFlow<CompanionAiState> = _state.asStateFlow()

    private val _interactions = MutableStateFlow<List<CompanionInteraction>>(emptyList())
    val interactions: StateFlow<List<CompanionInteraction>> = _interactions.asStateFlow()

    private val tokenLock = Any()

    @Volatile
    private var runCounter = 0L

    /** 当前唯一允许写状态的 run；0 表示还没有跑过。 */
    @Volatile
    private var activeRunId = 0L

    /** 已经被取消的 run：它的迟到结果一律作废（不许 COMPLETED，不许进记忆）。 */
    @Volatile
    private var cancelledRunId = 0L

    /** 已经写过终态的 run：一轮只落一条互动记录。 */
    @Volatile
    private var finishedRunId = 0L

    private var job: Job? = null

    @Volatile
    private var visible = false
    private var cancelReason: CompanionCancelReason = CompanionCancelReason.LEFT_WINDOW
    private var lastTrigger: CompanionTrigger? = null
    private var lastCompanion: FloatingCompanionSettings = FloatingCompanionSettings()

    /**
     * 当前角色会话的身份键（r16）。
     *
     * 除了角色键和名字，还带上解析出来的内置卡 id/版本/hash 和来源：
     * 用户改名、改自写覆盖、切角色，或者内置目录换了版本/hash，都会让旧 run 失效，
     * 迟到结果不能写进新设定。自动互动预算不在这个键里，切角色不会把预算重置。
     */
    private data class CompanionSessionIdentity(
        val characterKey: String,
        val name: String,
        val personaId: String?,
        val personaVersion: String,
        val personaHash: String,
        val source: PersonaSource,
    )

    private var characterIdentity: CompanionSessionIdentity? = null
    private var currentPersona: ResolvedCompanionPersona? = null

    /**
     * 当前角色会话里已经被接受的点击次数（TAP 序号）。
     *
     * 只影响“这一次点我要换个方向说”的轮换（[CompanionTapImagination]），不是台词库：
     * 每次点击仍然是 source.generate 现场生成。换角色归零，所以新角色的第一下点从 1 开始；
     * 被拒绝的点击（冷却/忙/关掉）不占号。
     */
    private var tapTurn: Int = 0

    /** 只读给状态卡/测试看：已经接受了多少次 TAP。换角色会归零。 */
    val acceptedTapTurns: Int get() = synchronized(tokenLock) { tapTurn }

    /** Change only the companion session; provider choices and rate/budget limits stay intact. */
    fun switchCharacter(companion: FloatingCompanionSettings) {
        val settings = companion.normalized()
        val resolved = CompanionPersonas.resolve(settings)
        val identity = CompanionSessionIdentity(
            characterKey = settings.characterKey,
            name = settings.name,
            personaId = resolved.id,
            personaVersion = resolved.version,
            personaHash = resolved.hash,
            source = resolved.source,
        )
        val previousJob = synchronized(tokenLock) {
            lastCompanion = settings
            currentPersona = resolved
            if (characterIdentity == identity) return
            val first = characterIdentity == null
            characterIdentity = identity
            if (first) return
            val old = job
            activeRunId = 0L // Invalidate before cancellation, including non-cooperative sources.
            job = null
            runModel = null
            lastTrigger = null
            tapTurn = 0 // 新角色的第一次点击从方向 1 开始，旧角色的序号不继承
            memory.clear()
            _interactions.value = emptyList()
            _state.value = CompanionAiState(model = _state.value.model)
            old
        }
        previousJob?.cancel()
    }

    /**
     * 内置人设目录换了版本（覆盖安装、将来的热导入）。
     *
     * 由界面观察 [CompanionPersonas.revision] 后调用：会重新解析当前设置的身份键，
     * 版本/hash 变了就按切角色同一路径处理——先失效 run token，再取消旧 job、清当前记忆。
     * 不动自动互动预算，也不换模型。
     */
    fun personaCatalogChanged() {
        switchCharacter(lastCompanion)
    }

    /** 本次 run 解析出来的模型；取消时用它写互动记录，和这一轮真正请求的模型一致。 */
    @Volatile
    private var runModel: CompanionModelDescription? = null

    /** 悬浮窗是否真的在显示。关掉窗口时取消在途请求，也不再有自动互动。 */
    fun setVisible(value: Boolean) {
        visible = value
        if (!value) cancelActive(CompanionCancelReason.LEFT_WINDOW)
    }

    fun isVisible(): Boolean = visible

    /**
     * 只重新读一遍"下一次会用哪个模型"，不发请求。
     * 悬浮窗出现、设置变化、用户点刷新时调用；状态卡里的模型一栏来自这里。
     *
     * 在途请求期间**不覆盖**当前请求的模型：那一栏必须还是这次真正用着的那个，
     * 等这一轮结束后的下一次刷新再更新（窗口出现、设置变化、用户点刷新都会再叫一次）。
     */
    fun refreshModel() {
        scope.launch {
            val description = describeModelSafely()
            if (isRunning()) return@launch
            _state.update { it.copy(model = description) }
        }
    }

    /**
     * 设置里的模型配置变了（换模式、换模型、删掉模型）时调用：下一次解析一定重新读，
     * 不会拿旧描述继续发请求。在途请求不会被悄悄换掉模型（那一轮继续用自己的快照，或者被取消）。
     */
    fun invalidateModel() {
        refreshModel()
    }

    /** [invalidateModel] 的语义化别名，给"设置项变化"的调用点用。 */
    fun settingsChanged() = invalidateModel()

    /** 用户主动操作（点击角色、在面板里说一句）。点击本身即授权，不再需要额外开关。 */
    fun submit(trigger: CompanionTrigger, companion: FloatingCompanionSettings): Boolean {
        val normalized = companion.normalized()
        val now = clock()
        var rejected: CompanionSkipReason? = null
        var acceptedJob: Job? = null
        synchronized(tokenLock) {
            switchCharacter(normalized)
            val personaSnapshot = checkNotNull(currentPersona)
            // 检查和占位在同一把锁里：界面点击（主线程）和自动事件（appScope）不会同时通过
            val busy = job?.isActive == true || _state.value.busy
            val reason = policy.evaluate(
                trigger = trigger,
                now = now,
                visible = visible,
                aiEnabled = normalized.aiEnabled,
                autoEnabled = normalized.autoEventsEnabled,
                busy = busy,
            )
            if (reason != null) {
                rejected = reason
            } else {
                lastTrigger = trigger
                policy.record(trigger, now)
                runCounter += 1
                val runId = runCounter
                activeRunId = runId
                cancelledRunId = 0L
                cancelReason = CompanionCancelReason.LEFT_WINDOW
                runModel = null
                // 接受时才算这一下的“即兴方向”：被拒绝/冷却的点击不占号，两个人在同一时刻看到的方向也不同
                val improvHint = nextTapImprovHint(normalized, trigger)
                _state.value = _state.value.copy(phase = CompanionAiPhase.THINKING, skipped = null,
                    text = "", thinking = "", failure = null, cancelReason = null,
                    trigger = trigger, startedAtMillis = now, finishedAtMillis = null)
                acceptedJob = scope.launch(start = CoroutineStart.LAZY) {
                    run(runId, trigger, normalized, now, improvHint, personaSnapshot)
                }
                job = acceptedJob
            }
        }
        val skipReason = rejected
        if (skipReason != null) {
            skip(skipReason, now)
            return false
        }
        acceptedJob?.start()
        return true
    }

    /** 再来一次：重跑上一次的触发（花费一次请求，界面上是显式按钮）。 */
    fun repeatLast() {
        val trigger = lastTrigger ?: CompanionTrigger(CompanionTriggerKind.TAP)
        submit(trigger, lastCompanion)
    }

    /** 停止入口：取消在途请求，阶段立即变成 CANCELLED。没有在途请求时什么都不做。 */
    fun stop() {
        cancelActive(CompanionCancelReason.USER)
    }

    /** 仅供测试/重置使用：清掉冷却和记忆。 */
    fun resetPolicy() {
        policy.reset()
    }

    /**
     * 取消当前这一轮。
     *
     * 状态是**当场**落地的：不依赖 source 是否配合取消。标志位先立（[cancelledRunId]），
     * 所以即使 source 之后仍然正常返回，那一轮也写不成 COMPLETED、进不了记忆。
     */
    private fun cancelActive(reason: CompanionCancelReason) {
        val activeJob = job
        if (activeJob == null || activeJob.isCompleted) {
            // 没有在途请求：不伪造一次取消；尚未 start 的 LAZY job 也需要取消。
            if (activeJob == null) cancelReason = reason
            return
        }
        val runId = synchronized(tokenLock) {
            cancelReason = reason
            if (activeRunId != 0L && activeRunId != finishedRunId) {
                cancelledRunId = activeRunId
            }
            activeRunId
        }
        finish(
            runId = runId,
            trigger = _state.value.trigger,
            phase = CompanionAiPhase.CANCELLED,
            text = "",
            failure = null,
            cancelReason = reason,
            model = runModel ?: _state.value.model,
            startedAt = _state.value.startedAtMillis ?: clock(),
        )
        activeJob.cancel()
    }

    private suspend fun run(
        runId: Long,
        trigger: CompanionTrigger,
        companion: FloatingCompanionSettings,
        startedAt: Long,
        tapImprovHint: String?,
        personaSnapshot: ResolvedCompanionPersona,
    ) {
        if (!isWritable(runId)) return
        _state.update { current ->
            if (!isWritable(runId)) {
                current
            } else {
                current.copy(
                    phase = CompanionAiPhase.THINKING,
                    trigger = trigger,
                    text = "",
                    thinking = "",
                    failure = null,
                    cancelReason = null,
                    startedAtMillis = startedAt,
                    finishedAtMillis = null,
                )
            }
        }

        val outcome: Attempt? = try {
            // 模型解析也在超时窗口里：解析卡住同样会按 TIMEOUT 结束，不会一直挂在 THINKING
            withTimeoutOrNull(timeoutMillis) { attempt(runId, trigger, companion, tapImprovHint, personaSnapshot) }
        } catch (cancel: CancellationException) {
            finish(
                runId = runId,
                trigger = trigger,
                phase = CompanionAiPhase.CANCELLED,
                text = "",
                failure = null,
                cancelReason = cancelReason,
                model = runModel ?: _state.value.model,
                startedAt = startedAt,
            )
            throw cancel
        } catch (error: Throwable) {
            finish(
                runId = runId,
                trigger = trigger,
                phase = CompanionAiPhase.FAILED,
                text = "",
                failure = failureOf(error),
                cancelReason = null,
                model = runModel ?: _state.value.model,
                startedAt = startedAt,
            )
            return
        }

        if (outcome == null) {
            finish(
                runId = runId,
                trigger = trigger,
                phase = CompanionAiPhase.CANCELLED,
                text = "",
                failure = null,
                cancelReason = CompanionCancelReason.TIMEOUT,
                model = runModel ?: _state.value.model,
                startedAt = startedAt,
            )
            return
        }

        val line = outcome.text
        when {
            outcome.failure != null -> finish(
                runId = runId,
                trigger = trigger,
                phase = CompanionAiPhase.FAILED,
                text = "",
                failure = outcome.failure,
                cancelReason = null,
                model = outcome.model,
                startedAt = startedAt,
            )

            line.isEmpty() -> finish(
                runId = runId,
                trigger = trigger,
                phase = CompanionAiPhase.FAILED,
                text = "",
                failure = CompanionFailure.Request(sanitizeCompanionFailureMessage("empty reply")),
                cancelReason = null,
                model = outcome.model,
                startedAt = startedAt,
            )

            else -> finish(
                runId = runId,
                trigger = trigger,
                phase = CompanionAiPhase.COMPLETED,
                text = line,
                failure = null,
                cancelReason = null,
                model = outcome.model,
                startedAt = startedAt,
                memoryPrompt = trigger.detail.ifBlank { outcome.userPrompt },
                memoryReply = line,
            )
        }
    }

    /** 一次请求的结果：模型描述 + 正文，或者一个明确的失败。 */
    private class Attempt(
        val model: CompanionModelDescription,
        val text: String = "",
        val failure: CompanionFailure? = null,
        val userPrompt: String = "",
    )

    private suspend fun attempt(
        runId: Long,
        trigger: CompanionTrigger,
        companion: FloatingCompanionSettings,
        tapImprovHint: String?,
        resolvedPersona: ResolvedCompanionPersona,
    ): Attempt {
        currentCoroutineContext().ensureActive()
        val model = describeModelSafely()
        currentCoroutineContext().ensureActive()
        if (!isWritable(runId)) throw CancellationException("Companion session changed")
        publishModel(runId, model)
        if (!model.available) {
            return Attempt(model = model, failure = model.failure ?: CompanionFailure.ModelMissing)
        }
        // r16：界面与请求共用同一个解析结果；非空自写覆盖整段优先，否则用完整内置卡。
        // Use exactly the normalized persona accepted with this run token, not a later catalog value.
        if (CompanionPersonas.resolve(companion) != resolvedPersona) {
            personaCatalogChanged()
            throw CancellationException("Companion persona changed before request")
        }
        val request = CompanionPromptBuilder.build(
            persona = resolvedPersona.prompt,
            name = resolvedPersona.name,
            trigger = trigger,
            memory = synchronized(tokenLock) { memory.snapshot() },
            sessionId = Uuid.random(),
            languageTag = languageTag(),
            // 点击的“即兴方向”：只有 TAP 有，且每次不同；Builder 只把它放进 userPrompt，不进人设。
            tapImprovHint = tapImprovHint,
            // 完整内置卡自带口吻与长度规则，不再叠旧版内置风格；只有旧短兜底才用。
            useBuiltInStyle = resolvedPersona.source == PersonaSource.FALLBACK,
            resolvedPersona = resolvedPersona,
            // 界面显示的模型和真正发出去的请求必须是同一个：实现会拿这份快照比对
        ).copy(expectedModel = model.snapshot)
        currentCoroutineContext().ensureActive()
        if (!isWritable(runId)) throw CancellationException("Companion session changed")
        val reply = source.generate(conversationId(), request) { delta -> onDelta(runId, delta) }
        // source 可能吞掉取消照样返回：只有还活着的 run 才算数
        currentCoroutineContext().ensureActive()
        return Attempt(
            model = model,
            text = cleanCompanionLine(reply),
            // 记忆里只留“用户点了一下”这件事，不把这一轮的即兴方向当成用户说过的话再喂回去
            userPrompt = if (trigger.kind == CompanionTriggerKind.TAP) TAP_MEMORY_PROMPT else request.userPrompt,
        )
    }

    private fun publishModel(runId: Long, model: CompanionModelDescription) = synchronized(tokenLock) {
        if (!isWritable(runId)) return@synchronized
        runModel = model
        _state.update { current -> if (isWritable(runId)) current.copy(model = model) else current }
    }

    private fun onDelta(runId: Long, delta: CompanionDelta) {
        if (!isWritable(runId)) return
        _state.update { current ->
            if (!isWritable(runId)) {
                current
            } else {
                val text = mergeStream(current.text, delta.text)
                val thinking = mergeStream(current.thinking, delta.reasoning)
                current.copy(
                    phase = if (text.isNotEmpty()) CompanionAiPhase.GENERATING else CompanionAiPhase.THINKING,
                    text = text,
                    thinking = thinking,
                )
            }
        }
    }

    /**
     * 流式合并：真实实现给的是"到目前为止的完整文本"，有的实现给增量。
     * 新片段以旧内容开头就整体替换，否则追加，两种情况都不会丢字也不会重复。
     */
    private fun mergeStream(current: String, incoming: String): String {
        if (incoming.isEmpty()) return current
        val merged = if (incoming.startsWith(current) || current.isEmpty()) incoming else current + incoming
        return if (merged.length > CompanionLimits.MAX_REPLY_CHARS * 4) {
            merged.takeLast(CompanionLimits.MAX_REPLY_CHARS * 4)
        } else {
            merged
        }
    }

    /**
     * 落终态。带着 run token 进来，只有"这一轮还属于当前 run、且没有被取消过"才写：
     * 被取消的一轮只能写 CANCELLED，而且一轮只写一次（[finishedRunId]）。
     */
    private fun finish(
        runId: Long,
        trigger: CompanionTrigger?,
        phase: CompanionAiPhase,
        text: String,
        failure: CompanionFailure?,
        cancelReason: CompanionCancelReason?,
        model: CompanionModelDescription,
        startedAt: Long,
        memoryPrompt: String? = null,
        memoryReply: String? = null,
    ) {
        synchronized(tokenLock) {
            if (runId == 0L || runId != activeRunId) return
            if (runId == finishedRunId) return
            if (runId == cancelledRunId && phase != CompanionAiPhase.CANCELLED) return
            finishedRunId = runId
            // Commit together with the token check: a concurrent character switch cannot leak
            // an old terminal state, interaction, or memory entry into the new character.
            val finishedAt = clock()
            _state.update { current ->
                current.copy(
                    phase = phase,
                    text = text,
                    failure = failure,
                    cancelReason = cancelReason,
                    finishedAtMillis = finishedAt,
                    startedAtMillis = startedAt,
                    trigger = trigger ?: current.trigger,
                )
            }
            val interaction = CompanionInteraction(
                trigger = trigger ?: CompanionTrigger(CompanionTriggerKind.TAP),
                phase = phase,
                text = text,
                failure = failure,
                modelLabel = model.label,
                atMillis = finishedAt,
            )
            _interactions.update { listOf(interaction) + it.take(CompanionLimits.MAX_INTERACTIONS - 1) }
            if (phase == CompanionAiPhase.COMPLETED && !memoryPrompt.isNullOrBlank() && !memoryReply.isNullOrBlank()) {
                memory.record(memoryPrompt, memoryReply)
            }
        }
    }

    private fun isWritable(runId: Long): Boolean =
        runId != 0L && runId == activeRunId && runId != cancelledRunId && runId != finishedRunId

    private fun isRunning(): Boolean = job?.isActive == true || _state.value.busy

    /**
     * 这一下点击的"即兴方向"。
     *
     * 只在 **接受** TAP 时取：被冷却/忙/关掉的点击不占号，所以连续两次真的发出去的点击
     * 一定拿到相邻的两个方向（[CompanionTapImagination] 用步长 3、周期 7 轮换，含"别重复上一句"），
     * 而不是从固定台词里抽一句。手动输入和自动事件不注入方向，保持它们各自的语境。
     *
     * 返回的是给 [CompanionPromptBuilder.build] 的 `tapImprovHint`（只进 userPrompt），
     * 不是可以照读的句子。
     */
    private fun nextTapImprovHint(
        companion: FloatingCompanionSettings,
        trigger: CompanionTrigger,
    ): String? {
        if (trigger.kind != CompanionTriggerKind.TAP) return null
        tapTurn = if (tapTurn == Int.MAX_VALUE) 1 else tapTurn + 1
        val resolved = CompanionPersonas.resolve(companion)
        return CompanionTapImagination.hint(
            // 只有真的用内置卡时才按角色轮换方向；自写覆盖/兜底用通用方向，免得借别的角色性格。
            profileId = resolved.id.takeIf { resolved.source == PersonaSource.BUILTIN },
            turn = tapTurn,
            hasHistory = memory.size > 0,
        )
    }

    private fun failureOf(error: Throwable): CompanionFailure =
        (error as? CompanionModelUnavailableException)?.failure
            ?: CompanionFailure.Request(
                sanitizeCompanionFailureMessage(
                    error.message ?: error::class.simpleName ?: "request failed",
                ),
            )

    private fun skip(reason: CompanionSkipReason, now: Long) {
        _state.update { it.copy(skipped = CompanionSkip(reason, now)) }
    }

    private suspend fun describeModelSafely(): CompanionModelDescription = try {
        source.describeModel(conversationId())
    } catch (cancel: CancellationException) {
        // 超时/取消不能被吃成"模型读不到"：交给 withTimeoutOrNull 和调用方处理
        throw cancel
    } catch (error: CompanionModelUnavailableException) {
        CompanionModelDescription(failure = error.failure)
    } catch (error: Throwable) {
        CompanionModelDescription(
            failure = CompanionFailure.Request(
                sanitizeCompanionFailureMessage(
                    error.message ?: error::class.simpleName ?: "model lookup failed",
                ),
            ),
        )
    }
}

/** TAP 进记忆时写的"用户说了什么"：一句稳定的事实，不带这一轮的即兴方向。 */
private const val TAP_MEMORY_PROMPT = "The user just tapped you."
