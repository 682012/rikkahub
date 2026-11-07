package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.core.sum
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.withoutEmptyGeneratedImages
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.resolveStreamToolInputs
import me.rerere.ai.util.json
import kotlin.time.Clock

// 输出为空的工具会被当成没执行过，之后每一步都会再执行一次，所以换成占位文本
private const val EMPTY_TOOL_OUTPUT = "(no output)"

/**
 * 参数没收到完整（流式中断、EOF、max_tokens 截断、取消）的工具**没有执行**，写回给模型的说明。
 *
 * 这里必须是一条结果而不是"什么都不写"：一条没有结果的 tool_call 会让下一次请求的 wire 协议
 * 不合法；同时这句话也要明确告诉模型"没有发生过副作用，需要的话请重新调用"，避免它以为调用成功。
 */
private const val INCOMPLETE_TOOL_INPUT_OUTPUT =
    "Tool not executed: its arguments are incomplete or invalid " +
        "(stream interrupted, truncated, cancelled or malformed). " +
        "Check any previously recorded execution before issuing a new complete call."

private class StreamChunkHandlingException(override val cause: Throwable) : RuntimeException(cause)

internal class AgentLoop(
    private val request: AgentRequest,
    private val hooks: AgentHooks,
    private val events: FlowCollector<AgentEvent>,
    // 单调时钟来源：生产用 System.nanoTime，测试可注入才能覆盖重试/多步窗口这类时间行为
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val history: List<UIMessage>

    // 本轮的助手消息；新的一轮在第一次请求模型时才创建
    private var message: UIMessage?
    private val loopGuard = ToolLoopGuard()

    // 工具声明在整次运行里不变：按名字索引一次，替代每个 part、每次调用都线性扫一遍
    private val toolIndex: Map<String, Tool> by lazy(LazyThreadSafetyMode.NONE) {
        HashMap<String, Tool>(request.tools.size * 2).apply {
            request.tools.forEach { putIfAbsent(it.name, it) }
        }
    }

    // 每一步都会重新规范化整段历史里的工具参数，结果只依赖参数本身，见 CanonicalArgumentsCache
    private val canonicalArguments = CanonicalArgumentsCache()

    // 审批或工具续跑时这条消息已经用掉的步数：同一条消息不能靠反复续跑换来无穷预算。
    // 只是继续写回复（没有待执行的工具）仍然是一次全新的预算，和以前一样。
    private val inheritedSteps: Int

    init {
        val last = request.messages.lastOrNull()
        if (last?.role == MessageRole.ASSISTANT) {
            history = request.messages.dropLast(1).map { it.withoutEmptyGeneratedImages() }
            message = last.withoutEmptyGeneratedImages()
        } else {
            history = request.messages.map { it.withoutEmptyGeneratedImages() }
            message = null
        }
        val resumed = message
        inheritedSteps = if (resumed != null && resumed.getTools().any { !it.isExecuted }) {
            resumed.stepCount ?: 0
        } else {
            0
        }
    }

    suspend fun run() {
        var steps = inheritedSteps
        while (true) {
            val current = message
            if (current != null && current.getTools().any { !it.isExecuted }) {
                val pending = requestApprovals(current)
                if (pending.isNotEmpty()) return finish(AgentFinishReason.AwaitingApproval(pending))
                executeTools()
            } else if (current != null && steps > 0) {
                // 模型已经给出最终回复。还有步数预算时先问一次运行中插话：有用户输入就追加进本条消息、
                // 再生成一步，让它看见。预算用完时一次都不问，队列留给之后的新一轮（不吞用户输入）。
                if (steps < request.maxSteps && appendPendingUserParts(requireModelTurn = false)) {
                    // 已经追加，落到下面的 generateStep
                } else if (steps < request.maxSteps && appendCompletionNudge(current)) {
                    // m3 会话鞭策：只追加一句推进语，然后仍然走下面同一个 generateStep，
                    // 步数从同一个预算里扣。不新增 user 消息、不新建会话、不重放工具、不改权限。
                } else {
                    return finish(AgentFinishReason.Completed)
                }
            }
            // 继承了已用步数时这里可能已经超过上限（比如换了更小的 maxSteps），用 >= 兜住
            if (steps >= request.maxSteps) return finish(AgentFinishReason.StepLimitReached)
            generateStep()
            steps++
        }
    }

    // 新的工具调用按定义判断是否需要审批，需要的标为 Pending；返回仍在等待审批的调用
    private suspend fun requestApprovals(current: UIMessage): Set<String> {
        val updated = current.copy(
            parts = current.parts.map { part ->
                if (part !is UIMessagePart.Tool || part.isExecuted) return@map part
                // 参数没收到完整（流式中/被中断/半截 JSON）的调用不进入审批：
                // 既不能拿它去问"要不要执行"，也就不会在批准后被拿去执行
                if (!part.isInputRunnable) return@map part
                if (part.approvalState != ToolApprovalState.Auto) return@map part
                // 审批判定用解析成功的参数；解析不出来时根本没有可判定的入参，不放行。
                val args = part.parseInputOrNull() ?: return@map part
                // m3: WebAccount 不再在这里无条件强制审批——那是供应商级策略，不该绕开工具自己的
                // 可配置审批表（只读查询被逐个弹窗、用户配置失效）。工具级策略是唯一来源；
                // 需要强制时由 ChatRequestFactory 侧的显式策略注入（默认关闭）。
                val needsApproval = findTool(part.toolName)?.needsApproval?.invoke(args) == true
                if (!needsApproval) return@map part
                // tool_batch：只有"含需审批成员"时才整批停下。全免审批的批直接跑（不打断），
                // 含需审批成员的批整批停下、拿到一个 Pending id，批准后只有被批准的成员执行。
                val batchPlan = planBatchApproval(part, args)
                if (batchPlan != null && !batchPlan.anyNeedsApproval) return@map part
                part.copy(approvalState = ToolApprovalState.Pending,
                    approvalRequiredMembers = batchPlan?.requiringApproval?.mapTo(linkedSetOf()) { it.index })
            }
        )
        if (updated != current) update(updated)
        return updated.getTools().filter { it.isPending }.mapTo(LinkedHashSet()) { it.toolCallId }
    }

    /**
     * tool_batch 的成员审批计划；不是 tool_batch / 参数解析不出成员时返回 null。
     *
     * 计划在**执行时**重新计算（[createToolBatch] 的 memberApproval 回调），
     * 而不是在这里把结论缓存进消息：用户批准后编辑过工具表也不会用到旧结论。
     */
    private fun planBatchApproval(part: UIMessagePart.Tool, args: JsonObject): BatchApprovalPlan? {
        if (part.toolName != TOOL_BATCH_NAME) return null
        val calls = parseBatchCalls(part.toolName, args) ?: return null
        return approvalPlan(calls) { findTool(it) }
    }

    private suspend fun generateStep() {
        // 用户插话先于后台通知：同一批里用户内容在前，模型看到的是「用户说了什么」再「后台发生了什么」
        appendPendingUserParts()
        appendPendingParts()
        val current = message
        val step = hooks.prepareStep(if (current == null) history else history + current)
        val params = step.params.copy(tools = request.tools)
        // 只带着追加内容创建的消息还没有模型 id
        val base = current?.let { it.copy(modelId = it.modelId ?: params.model.id) } ?: UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            modelId = params.model.id,
        )
        // 发给模型的副本里折叠重复的工具结果，消息本身保留完整内容
        val messages = dedupeRepeatedToolOutputs(step.messages, canonicalArguments) {
            findTool(it)?.repeatable == true
        }
        val generated = if (request.stream) {
            stream(messages, params, base)
        } else {
            generate(messages, params, base)
        }
        update(hooks.finishStep(generated, newPartsFrom = base.parts.size).copy(finishedAt = now()))
    }

    /**
     * 在安全点把运行中用户插话追加到本条助手消息末尾，返回是否真的追加了内容。
     *
     * 安全点 = 没有任何待执行的工具：正在执行工具或等待审批时插话会插到工具结果中间，
     * 模型看不到结果就先答，工具也不会因此重放。追加只是往消息末尾加 part，
     * 不取消正在进行的请求、不改写已有内容。
     *
     * [requireModelTurn] 为 true 时只在本轮轮到模型的地方追加（新的一轮开始、一批工具跑完之后、
     * 消息以通知结尾时）。续写半截回复时不追加：那会把一次续写切成两个助手消息。
     * 用户插话真正要用的判断点是「模型已经给出最终回复」，那里传 false。
     */
    private suspend fun appendPendingUserParts(requireModelTurn: Boolean = true): Boolean {
        val current = message
        if (current != null && current.getTools().any { !it.isExecuted }) return false
        if (requireModelTurn && current != null && !current.parts.lastOrNull().isModelsTurn()) return false
        val parts = hooks.pendingUserParts()
        if (parts.isEmpty()) return false
        val base = current ?: UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        update(base.copy(parts = base.parts + parts))
        return true
    }

    /**
     * m3 会话鞭策：在**正常结束之前**给一次额外机会，把一句推进语作为 [UIMessagePart.Notice]
     * 追加到当前这条助手消息末尾，返回是否真的追加了。
     *
     * 只在真正的安全点调用（[run] 里"模型已给出最终回复"那个分支）：此时没有待执行的工具、
     * 不在一轮工具执行中间、也没有半截回复要续写。开了开关才会有钩子（[AgentHooks.completionNudge]
     * 默认返回 null），关着时这里第一次调用就返回 false，行为和没有这个功能完全一致。
     *
     * 追加之后循环照常进 [generateStep]，用的还是同一个 `steps` 计数和同一个
     * [AgentRequest.maxSteps]，所以这部分最多只会多用掉正常预算里剩的那几步。
     */
    private suspend fun appendCompletionNudge(current: UIMessage): Boolean {
        // 冗余保险：有待执行工具、或在等审批时绝不追加（调用点已经保证，这里不依赖调用点）
        if (current.getTools().any { !it.isExecuted }) return false
        val prompt = hooks.completionNudge(current) ?: return false
        if (prompt.noticeText.isBlank()) return false
        update(current.copy(parts = current.parts + prompt.toNoticePart()))
        return true
    }

    // 续写普通回复时最后一个 part 不是执行完的工具或通知，这时追加会把续写变成新的一轮
    private suspend fun appendPendingParts() {
        val current = message
        if (current != null && !current.parts.lastOrNull().isModelsTurn()) return
        val parts = hooks.pendingParts()
        if (parts.isEmpty()) return
        val base = current ?: UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        update(base.copy(parts = base.parts + parts))
    }

    private suspend fun stream(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
    ): UIMessage {
        // 多轮工具调用共用同一条助手消息，消息上已有的 usage 和耗时来自之前的步骤。
        // 本步从清空的统计开始（merge 只适合同一次请求内的快照），再与之前的步骤累加。
        val stepStart = base.copy(usage = null, lastUsage = null, generationTimeMs = null, generationTiming = null)
        // 重试时已经展示出去的半截回复不能先缩水再重画，是否放行这次预览由它判断
        val previews = StreamPreviewGuard(base.parts.size)
        // 最后一次尝试的合并结果留在外面：流异常结束/取消时要靠它把半截工具参数标成 INTERRUPTED
        var attempt = stepStart
        var handler = StreamChunkHandler(params.model)
        return try {
            withRetry(request.retry, onRetry = { events.emit(AgentEvent.Retrying(it)) }) {
                // 每次重试都从本步开始时的消息重新合并，不会把重试的响应接在已经展示的半截回复后面；
                // 各次尝试共用同一个消息 id，调用方直接覆盖即可。
                handler = StreamChunkHandler(params.model)
                attempt = stepStart
                // 计时器必须每次尝试重建：上一次失败的半截内容不能算进这一次的生成窗口，
                // 否则窗口里会混进重试的网络等待，速度被算低。
                val timer = GenerationTimer(nanoTime)
                previews.beginAttempt()
                request.provider.streamText(request.providerSetting, messages, params).collect { chunk ->
                    try {
                        timer.onChunk(chunk)
                        attempt = handler.handle(listOf(attempt), chunk).single()
                        previews.onChunk(chunk)
                        // 重试后的新尝试要追上已展示的内容才更新预览，界面不会退回到更短的半截
                        if (previews.canShow(attempt.parts)) {
                            val shown = attempt.withStepStats(base, timer.finish(attempt.usage?.completionTokens))
                            preview(shown, newPartsFrom = base.parts.size)
                            previews.record(shown.parts)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // 合并失败或下游处理失败都不是网络故障，不能重放模型请求
                        throw StreamChunkHandlingException(e)
                    }
                }
                // 流正常读到终点（有些 Provider 只到 EOF、不发 Finish）：本步参数按"正常结束"收尾，
                // 完整参数转 COMPLETE，半截的留在 INTERRUPTED，等会儿也不会被执行
                attempt.withoutEmptyGeneratedImages()
                    .withStepStats(base, timer.finish(attempt.usage?.completionTokens))
                    .resolveStreamToolInputs(base.parts.size)
            }
        } catch (e: StreamChunkHandlingException) {
            publishInterruptedStream(handler, attempt, base)
            throw e.cause
        } catch (e: Throwable) {
            // 断线 / 对端 EOF / 取消 / 读取异常：先把本步还在流式中的工具参数标成 INTERRUPTED 再抛出，
            // 已经生成的那半截参数一个字都不删，上层可以照常保存这条消息
            publishInterruptedStream(handler, attempt, base)
            throw e
        }
    }

    /**
     * 流没正常结束时的收尾：把本步还在流式中的工具调用标成 `INTERRUPTED`（参数原文保留），
     * 并把这条半截消息发给上层，调用方（ChatService）能拿到它去持久化。
     *
     * 取消路径上 `emit` 本身可能失败（协程已经被取消），这里只尽力而为：失败不影响原始异常继续
     * 向上抛，参数原文也仍然留在已经发过的那条 [AgentEvent.MessageUpdated] 里。
     */
    private suspend fun publishInterruptedStream(
        handler: StreamChunkHandler,
        attempt: UIMessage,
        base: UIMessage,
    ) {
        val interrupted = handler.interrupted(listOf(attempt)).single()
        if (interrupted == attempt) return
        runCatching { preview(interrupted, newPartsFrom = base.parts.size) }
    }

    private suspend fun generate(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
    ): UIMessage {
        // 非流式拿不到首 token 时间，只能统计最后一次尝试的整个请求耗时
        var timer = GenerationTimer(nanoTime)
        val result = withRetry(request.retry, onRetry = { events.emit(AgentEvent.Retrying(it)) }) {
            timer = GenerationTimer(nanoTime)
            request.provider.generateText(request.providerSetting, messages, params)
        }
        val timing = timer.finish(result.usage?.completionTokens, streaming = false)
        // 和流式一样只追加 part，不并进之前步骤的文本或推理里，否则 newPartsFrom 之后就不全是这一步的内容了。
        // 追加前先给重复或空白的 toolCallId 换新 id，否则结果写回时会把之前步骤的工具结果一起覆盖掉。
        // 一次请求只有一份 usage；没有返回时保持为空，累加时沿用之前步骤的统计
        val newParts = normalizeNewToolCallIds(base.parts, result.message.parts)
        val generated = base.copy(
            parts = base.parts + newParts,
            annotations = result.message.annotations.ifEmpty { base.annotations },
            modelId = params.model.id,
            usage = result.usage,
            lastUsage = result.message.lastUsage,
        ).withoutEmptyGeneratedImages().finishReasoning().withStepStats(base, timing)
        preview(generated, newPartsFrom = base.parts.size)
        return generated
    }

    // Adjacent, explicitly independent calls overlap. Every serial call is a full ordering barrier.
    // Only the collector coroutine updates the message/emits events; workers report through a channel.
    private suspend fun executeTools() {
        val tools = checkNotNull(message).getTools()
        // 参数没收到完整（流被截断/断线/取消，或旧记录里就是半截 JSON）的调用**绝不执行**：
        // 拿半截参数去猜着跑，等于用默认值替用户改东西。这里只给它写一条"未执行"的结果，
        // 让循环能继续、模型知道要重新发一次完整调用；参数原文原样留着。
        val (incomplete, pending) = tools.filter { !it.isExecuted }.partition { !it.isInputRunnable }
        incomplete.forEach { reportIncompleteInput(it) }
        // 连续多次结果相同的调用、以及按固定顺序来回振荡的调用不再执行，见 ToolRepetition.kt
        val blocked = blockedRepeatCalls(tools.filter { it.isExecuted }, pending, canonicalArguments) {
            findTool(it)?.repeatable == true
        }
        // 每个待执行调用能不能并行只判定一次：原来的窗口扫描会把窗口第一个元素算两遍，
        // 而每次判定都要查工具定义、审批状态和回放保护
        val parallel = BooleanArray(pending.size) { canRunInParallel(pending[it], blocked) }
        var index = 0
        while (index < pending.size) {
            val first = pending[index]
            if (first.toolCallId in blocked && first.runs()) {
                updateTool(first.toolCallId) { it.copy(output = repeatBlockedOutput()) }
                index++
                continue
            }
            if (!parallel[index]) {
                executeOne(first)
                index++
                continue
            }
            val from = index
            while (index < pending.size && parallel[index]) index++
            executeParallel(pending.subList(from, index))
        }
        val current = checkNotNull(message)
        val ids = pending.map { it.toolCallId }.toSet()
        loopGuard.observe(current.getTools().filter { it.toolCallId in ids }) { name ->
            findTool(name)?.takeUnless { it.repeatable }?.executionMode
        }
            ?.let { notice -> update(current.copy(parts = current.parts + notice)) }
    }

    private fun UIMessagePart.Tool.runs() =
        approvalState == ToolApprovalState.Auto ||
            approvalState == ToolApprovalState.Approved ||
            approvalState is ToolApprovalState.ApprovedMembers

    private fun canRunInParallel(tool: UIMessagePart.Tool, blocked: Set<String>): Boolean =
        tool.toolCallId !in blocked &&
            (tool.approvalState == ToolApprovalState.Auto ||
                tool.approvalState == ToolApprovalState.Approved ||
                tool.approvalState is ToolApprovalState.ApprovedMembers) &&
            findTool(tool.toolName)?.executionMode?.allowsParallelExecution() == true &&
            !(tool.startedAt != null && findTool(tool.toolName)?.executionMode != ToolExecutionMode.PARALLEL_DEDUPLICATED)

    private suspend fun executeOne(tool: UIMessagePart.Tool) {
        val runs = tool.approvalState == ToolApprovalState.Auto || tool.approvalState == ToolApprovalState.Approved
        val interrupted = runs && tool.startedAt != null &&
            findTool(tool.toolName)?.executionMode != ToolExecutionMode.PARALLEL_DEDUPLICATED
        if (interrupted) {
            updateTool(tool.toolCallId) {
                it.copy(output = errorOutput(
                    "Previous execution was interrupted after it started. Its effects are unknown; " +
                        "this call was NOT replayed. Inspect the target state before issuing a new call."
                ), finishedAt = Clock.System.now())
            }
            return
        }
        if (runs) updateTool(tool.toolCallId) { it.copy(startedAt = Clock.System.now(), finishedAt = null) }
        val output = execute(tool)
        updateTool(tool.toolCallId) {
            it.copy(output = output, finishedAt = if (runs) Clock.System.now() else it.finishedAt)
        }
    }

    private sealed interface ToolProgress {
        data class Started(val calls: List<UIMessagePart.Tool>, val acknowledged: CompletableDeferred<Unit>) : ToolProgress
        data class Completed(val calls: List<UIMessagePart.Tool>, val output: List<UIMessagePart>) : ToolProgress
        data class Cancelled(val error: CancellationException) : ToolProgress
    }

    private suspend fun executeParallel(calls: List<UIMessagePart.Tool>) = coroutineScope {
        // 同名的调用才可能撞成同一组；名字在这个窗口里只出现一次时不必规范化它的参数（见 groupParallelCalls）
        val groups = groupParallelCalls(
            calls = calls,
            nameOf = { it.toolName },
            deduplicated = { findTool(it.toolName)?.executionMode == ToolExecutionMode.PARALLEL_DEDUPLICATED },
            canonicalKey = { canonicalArguments.canonical(it.input).toString() },
        )
        // 容量上限本来就是这个窗口能产生的事件数（每组 Started + Completed/Cancelled），
        // 任务数还有 Semaphore(8) 兜着；取消路径上的 trySend 需要它不阻塞，所以保持无界
        val progress = Channel<ToolProgress>(Channel.UNLIMITED)
        val permits = Semaphore(8)
        groups.forEach { group ->
            launch {
                permits.withPermit {
                    val acknowledged = CompletableDeferred<Unit>()
                    progress.send(ToolProgress.Started(group, acknowledged))
                    acknowledged.await()
                    try {
                        progress.send(ToolProgress.Completed(group, execute(group.first())))
                    } catch (e: CancellationException) {
                        // A tool may cancel itself without cancelling its siblings; wake the collector too.
                        progress.trySend(ToolProgress.Cancelled(e))
                        throw e
                    }
                }
            }
        }
        repeat(groups.size * 2) {
            when (val event = progress.receive()) {
                is ToolProgress.Started -> {
                    val startedAt = Clock.System.now()
                    updateTools(event.calls) { it.copy(startedAt = startedAt, finishedAt = null) }
                    event.acknowledged.complete(Unit)
                }
                is ToolProgress.Completed -> {
                    val finishedAt = Clock.System.now()
                    updateTools(event.calls) { it.copy(output = event.output, finishedAt = finishedAt) }
                }
                is ToolProgress.Cancelled -> throw event.error
            }
        }
        progress.close()
    }

    /**
     * 同一个进度事件里的调用合成一次消息更新。
     *
     * 每次 [update] 都要复制整条 parts 并向调用方发一个 [AgentEvent.MessageUpdated]；调用方
     * （ChatService）在工具状态发生变化时还会立刻落库一次。逐个调用更新等于把同一份复制、同一个事件
     * 和同一次落库做 N 遍，而它们本来就发生在同一瞬间。
     *
     * 语义不变：`Started` 仍然是先写 `startedAt` 再放行执行（startedAt checkpoint 在效果之前落地），
     * `Completed` 仍然一次性写回全部结果；没有被真正改动的调用不会触发多余的更新。
     */
    private suspend fun updateTools(
        calls: List<UIMessagePart.Tool>,
        transform: (UIMessagePart.Tool) -> UIMessagePart.Tool,
    ) {
        if (calls.isEmpty()) return
        val ids = calls.mapTo(HashSet(calls.size * 2)) { it.toolCallId }
        val current = checkNotNull(message)
        var changed = false
        val parts = current.parts.map { part ->
            if (part !is UIMessagePart.Tool || part.toolCallId !in ids) return@map part
            val updated = transform(part)
            if (updated != part) changed = true
            updated
        }
        if (changed) update(current.copy(parts = parts))
    }

    private suspend fun reportIncompleteInput(tool: UIMessagePart.Tool) {
        val invalidJson = if (tool.parseInputOrNull() == null)
            "Invalid tool arguments JSON for ${tool.toolName}. " else ""
        updateTool(tool.toolCallId) {
            it.copy(
                output = errorOutput(invalidJson + INCOMPLETE_TOOL_INPUT_OUTPUT),
                finishedAt = Clock.System.now(),
            )
        }
    }

    private suspend fun updateTool(toolCallId: String, transform: (UIMessagePart.Tool) -> UIMessagePart.Tool) {
        val current = checkNotNull(message)
        update(
            current.copy(
                parts = current.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) transform(part) else part
                }
            )
        )
    }

    private suspend fun execute(tool: UIMessagePart.Tool): List<UIMessagePart> =
        when (val state = tool.approvalState) {
            is ToolApprovalState.Denied -> if (tool.toolName == TOOL_BATCH_NAME) {
                withContext(BatchApprovalContext(BatchMemberApproval.None)) { runTool(tool) }
            } else errorOutput(
                "Tool execution denied by user. Reason: ${state.reason.ifBlank { "No reason provided" }}"
            )

            is ToolApprovalState.Answered -> listOf(UIMessagePart.Text(state.answer))
            // 只批准了批内一部分成员：把结论通过协程上下文交给 tool_batch 执行器，
            // 未获批准的成员不执行、只产出结构化 tool_denied。
            is ToolApprovalState.ApprovedMembers -> withContext(
                BatchApprovalContext(
                    BatchMemberApproval(
                        approved = false,
                        members = state.indices.associateWith { true },
                    )
                )
            ) { runTool(tool) }

            ToolApprovalState.Auto, ToolApprovalState.Approved -> runTool(tool)
            ToolApprovalState.Pending -> error("Tool ${tool.toolCallId} is still waiting for approval")
        }

    private suspend fun runTool(tool: UIMessagePart.Tool): List<UIMessagePart> = try {
        // 兜底闸门：即使调用方没走 executeTools 的过滤，半截参数也绝不能落到工具执行上。
        // 注意不能用 inputAsJson() 的空对象回退来兜底——那会把"解析失败"变成"没有参数"直接跑起来。
        if (!tool.isInputRunnable) {
            errorOutput(INCOMPLETE_TOOL_INPUT_OUTPUT)
        } else {
            val definition = findTool(tool.toolName) ?: error("Tool ${tool.toolName} not found")
            val args = runCatching { json.parseToJsonElement(tool.input.ifBlank { "{}" }) }
                .getOrElse { error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}") }
            definition.execute(args).ifEmpty { listOf(UIMessagePart.Text(EMPTY_TOOL_OUTPUT)) }
        }
    } catch (e: CancellationException) {
        // 取消必须向上传播，否则停止生成会被误报为工具执行错误
        throw e
    } catch (e: Throwable) {
        errorOutput("[${e.javaClass.name}] ${(e.message ?: "Tool execution failed").take(2_000)}")
    }

    // 名字索引在整次运行里只建一次；同名声明取第一个，和原来的 find 一致
    private fun findTool(name: String): Tool? = toolIndex[name]

    private suspend fun update(updated: UIMessage) {
        message = updated
        events.emit(AgentEvent.MessageUpdated(updated))
    }

    // 生成中途的内容只发给调用方，这一步结束前循环内部一直基于原始内容合并
    private suspend fun preview(generating: UIMessage, newPartsFrom: Int) {
        events.emit(AgentEvent.MessageUpdated(hooks.previewStep(generating, newPartsFrom)))
    }

    private suspend fun finish(reason: AgentFinishReason) {
        events.emit(AgentEvent.Finished(checkNotNull(message), reason))
    }
}

private fun UIMessagePart?.isModelsTurn() =
    (this is UIMessagePart.Tool && isExecuted) || this is UIMessagePart.Notice

/**
 * 重试时保住已经展示给用户的内容。
 *
 * 第一次尝试照常逐字刷新预览；某次尝试失败后，新尝试的内容先不往外发，直到它明确延续
 * 已展示的文本并且长度追上来——否则界面会先缩水、再从零重画，看起来像回复被打断重写。
 *
 * 判定用增量长度做闸门，再比一次前缀：本次尝试的内容一旦确认延续，后面就不再重复比较
 * （同一次尝试里的文本只会往后加），长回复不会退化成逐字符的全量比较。
 * 如果新尝试和已展示的内容对不上（模型换了内容重新生成），本次尝试就不再尝试追上，
 * 等它成功之后由这一步的最终消息整体替换：安全优先于逐字无缝。
 */
private class StreamPreviewGuard(private val baseParts: Int) {
    // 上一次真正发出去的本步内容，以及它的文本总长
    private var shown: List<UIMessagePart> = emptyList()
    private var shownTextLength = 0
    // 本次尝试已经收到的文本长度，只累加增量
    private var attemptTextLength = 0
    // 本次尝试已经确认延续了已展示的内容，之后的预览不必再比前缀
    private var trusted = false
    // 本次尝试已经确认和已展示的内容不同，直到这次尝试结束都不再更新预览
    private var diverged = false

    fun beginAttempt() {
        attemptTextLength = 0
        trusted = false
        diverged = false
    }

    fun onChunk(chunk: StreamChunk) {
        if (chunk is StreamChunk.TextDelta) attemptTextLength += chunk.text.length
    }

    /** 这次尝试的当前内容能不能更新预览 */
    fun canShow(parts: List<UIMessagePart>): Boolean {
        if (trusted) return true
        // 还没有展示过任何内容（第一次尝试，或这一步本来就没产生内容），照常刷新
        if (shown.isEmpty()) {
            trusted = true
            return true
        }
        if (diverged || attemptTextLength < shownTextLength) return false
        val tail = parts.subList(baseParts, parts.size)
        val extends = tail.size >= shown.size && shown.indices.all { tail[it].extendsPart(shown[it]) }
        if (!extends) {
            diverged = true
            return false
        }
        trusted = true
        return true
    }

    /** 记录这一次真正发出去的内容 */
    fun record(parts: List<UIMessagePart>) {
        shown = parts.subList(baseParts, parts.size).toList()
        shownTextLength = shown.sumOf { it.textLength() }
    }
}

// 新内容是否严格延续旧内容：占位不同就算不延续
private fun UIMessagePart.extendsPart(shown: UIMessagePart): Boolean = when {
    this is UIMessagePart.Text && shown is UIMessagePart.Text ->
        text.length >= shown.text.length && text.startsWith(shown.text)

    this is UIMessagePart.Reasoning && shown is UIMessagePart.Reasoning ->
        reasoning.length >= shown.reasoning.length && reasoning.startsWith(shown.reasoning)

    // 工具参数是流式拼的，只要求还是同一个调用，重试的工具卡片就不会串位
    this is UIMessagePart.Tool && shown is UIMessagePart.Tool ->
        toolCallId == shown.toolCallId && toolName == shown.toolName &&
            input.length >= shown.input.length && input.startsWith(shown.input)

    else -> this == shown
}

private fun UIMessagePart.textLength(): Int = (this as? UIMessagePart.Text)?.text?.length ?: 0

private fun errorOutput(error: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", error) }.toString()))

// 把本步的 usage 和生成耗时累加到之前步骤的统计上；接收者只带本步的 usage
private fun UIMessage.withStepStats(stepBase: UIMessage, timing: GenerationTiming): UIMessage {
    val knownSteps = stepBase.stepCount ?: if (stepBase.usage != null || stepBase.parts.isNotEmpty()) 1 else 0
    val previous = stepBase.generationTiming ?: GenerationTiming(
        totalSteps = knownSteps,
        // 旧消息本来就没有计时记录：这些步骤要算进覆盖范围，但只能注明「不知道」而不是当成已测
        skippedReasons = if (knownSteps > 0) {
            mapOf(GenerationSkipReason.LEGACY_UNKNOWN to knownSteps)
        } else {
            emptyMap()
        },
    )
    val combined = previous.add(timing)
    return copy(
        usage = stepBase.usage.sum(usage),
        generationTimeMs = combined.generationMs ?: 0L,
        generationTiming = combined,
        lastUsage = lastUsage ?: usage,
        stepCount = (stepBase.stepCount ?: 0) + 1,
    )
}

private fun now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
