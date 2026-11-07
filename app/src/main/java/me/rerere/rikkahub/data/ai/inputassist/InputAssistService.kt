package me.rerere.rikkahub.data.ai.inputassist

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.runtime
import me.rerere.rikkahub.data.model.ConversationReviewMode
import me.rerere.rikkahub.data.model.ConversationRuntimeOptions
import kotlin.uuid.Uuid

private const val TAG = "InputAssist"

/** 界面上的审查结果只留最近这么多条；它只是缓存，不落盘，丢了顶多再看一次 */
private const val REVIEW_CACHE_SIZE = 12

/**
 * 输入优化的执行入口，也是主代理唯一需要接的东西。
 *
 * 一轮的生命周期（全部由 ChatService 的 patch 驱动，见 inputassist/PATCH-ChatService.md）：
 * 1. 用户消息落库、`runTurn` 之前 → [prepareTurn]：读开关，必要时调用一次快速模型，把这一轮要用的
 *    优化版/自检标记登记进 [InputAssistRuntime]；
 * 2. 请求组装时（`createAgent`）→ [rewriteRequestMessages]：按 messageId 精确替换/追加，库里原话不动；
 * 3. 一轮结束 → [finishTurn]：清掉登记，避免影响下一轮；
 * 4. 回复写完且 reviewMode 为 INDEPENDENT → [startReview]：后台独立审查，结果进 [reviewStates]。
 *
 * 默认开关全关：`prepareTurn` 不发请求，`startReview` 不起任务，和旧版本行为一致。
 *
 * 取消语义（别把它写成"取消就不花钱"）：
 * - [cancelOptimization] 只停掉辅助请求，这一轮改用原话继续，整轮聊天不动；
 * - [cancelReview] 只停掉审查任务，助手回答不动；
 * - 已经发出去的请求撤回不了，费用也不会因为取消而退回。
 */
class InputAssistService(
    private val optimizer: InputOptimizer,
    private val reviewer: TurnReviewer,
    private val scope: CoroutineScope,
    /** 只用于界面"对照优化版"回读缓存；不传也能正常工作，只是查不到对照文本 */
    private val cache: InputOptimizationCache? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _reviewStates = MutableStateFlow<Map<Uuid, InputAssistReviewState>>(emptyMap())

    /** 每个会话当前的审查状态，卡片直接读它。 */
    val reviewStates: StateFlow<Map<Uuid, InputAssistReviewState>> = _reviewStates.asStateFlow()

    /**
     * 审查/优化的生命周期只用这一把锁。
     *
     * 锁内只做内存操作（登记、比较代号、更新 StateFlow），绝不做会挂起的事，也不在里面 cancel：
     * 取消会同步触发 `invokeOnCompletion`，在锁里取消就可能自己等自己。
     */
    private val lifecycleLock = Any()

    private val reviewJobs = HashMap<Uuid, Job>()

    /** 每个会话正在审查的那一轮的缓存键：同一轮再次被要求审查时，绝不再发第二次请求。 */
    private val reviewKeys = HashMap<Uuid, String>()

    /** 每个会话当前有效的审查代号：只有代号对得上，结果和取消才有权改状态。 */
    private val reviewTokens = HashMap<Uuid, Uuid>()

    private val reviewCache = object : LinkedHashMap<String, InputAssistReview>(REVIEW_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InputAssistReview>) =
            size > REVIEW_CACHE_SIZE
    }

    /** 每个会话正在跑的优化：代号 + 可取消的子任务（LAZY：先登记，再启动）。 */
    private val optimizationRuns = HashMap<Uuid, OptimizationRun>()

    private class OptimizationRun(val token: Uuid, val job: Job)

    /**
     * 这一轮开始前调用一次（必须在 `createContext` 之前，那里之后消息 id 就交给请求了）。
     *
     * 不抛异常打断发送：优化失败、被 [cancelOptimization] 取消，都退回"用原话"继续。
     * 只有外层（整轮聊天）自己被取消时才把 [CancellationException] 向上抛。
     */
    suspend fun prepareTurn(
        settings: Settings,
        conversation: Conversation,
        messageId: Uuid,
        options: ConversationRuntimeOptions,
        timeoutMillis: Long = InputAssistRules.DEFAULT_TIMEOUT_MILLIS,
    ): InputAssistTurnState {
        // 先领代次：这次 prepareTurn 可能要等十几秒的辅助请求，期间这一轮可能已经被收掉
        // （会话删除、换助手、关开关）或被下一轮顶掉。领了代次之后才登记，迟到的结果就进不来。
        val generation = InputAssistRuntime.beginTurn(conversation.id)
        val assistantId = conversation.assistantId
        val parts = conversation.currentMessages.firstOrNull { it.id == messageId }?.parts.orEmpty()
        // 定轮次的文本和请求改写用的是同一个规范文本：只取 Text part、单换行连接。
        // 用 toText() 会把附件也拼进分隔符，带附件的消息两边指纹永远对不上。
        val textParts = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        val text = InputAssistRules.canonicalUserText(textParts)
        val contiguous = InputAssistRules.textPartsContiguous(parts.map { it is UIMessagePart.Text })
        val outcome = when {
            // 开关关着：一个额外请求都不发，也不读盘
            !options.optimizeUserInput -> InputOptimizationOutcome(InputOptimizationStatus.NOT_REQUESTED)
            // 纯附件消息没有可澄清的散文，优化只可能弄坏附件顺序
            text.isBlank() -> InputOptimizationOutcome(
                InputOptimizationStatus.NOT_ELIGIBLE,
                failureReason = "no text to optimize",
            )
            // 多段文本夹着附件：整体折叠会把文字挪到附件另一边。跳过优化，只保留零请求的自检。
            !contiguous -> InputOptimizationOutcome(
                InputOptimizationStatus.NOT_ELIGIBLE,
                failureReason = "text parts are interleaved with attachments",
            )
            else -> optimizeCancellable(
                settings = settings,
                conversation = conversation,
                messageId = messageId,
                rawText = text,
                assistantId = assistantId,
                timeoutMillis = timeoutMillis,
                explicitModelId = options.optimizerModelId,
            )
        }
        val state = InputAssistTurnState(
            conversationId = conversation.id,
            messageId = messageId,
            assistantId = assistantId,
            optimization = outcome,
            // SELF_CHECK 只是请求尾部多一句同模型自检，不额外调用
            selfCheck = options.reviewMode == ConversationReviewMode.SELF_CHECK,
            originalHash = InputAssistRules.canonicalTextHash(textParts),
        )
        // 代次对不上说明这一轮已经作废（删了会话/换了助手/被下一轮顶掉）：这时候什么都不登记，
        // 请求组装拿不到状态就发原话，绝不会把过期的优化版或自检尾巴贴到别的轮次上。
        InputAssistRuntime.stageIfCurrent(generation, state)
        return state
    }

    /**
     * 优化请求放在 supervisorScope 里单独起一个 LAZY 子任务：
     * 这样 [cancelOptimization] 能只取消这一条辅助请求（子任务失败/取消不会带走外层），
     * 而外层聊天自己被取消时 job 仍然照常向上抛。
     */
    private suspend fun optimizeCancellable(
        settings: Settings,
        conversation: Conversation,
        messageId: Uuid,
        rawText: String,
        assistantId: Uuid,
        timeoutMillis: Long,
        explicitModelId: Uuid?,
    ): InputOptimizationOutcome = supervisorScope {
        val conversationId = conversation.id
        val token = Uuid.random()
        // LAZY 建好但先不启动：登记进表之后才 start，这样 cancel 在登记之后一定能找到它
        val run = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            optimizer.optimize(
                settings = settings,
                conversation = conversation,
                messageId = messageId,
                rawText = rawText,
                assistantId = assistantId,
                timeoutMillis = timeoutMillis,
                explicitModelId = explicitModelId,
            )
        }
        val stale = synchronized(lifecycleLock) {
            optimizationRuns.put(conversationId, OptimizationRun(token, run))?.job
        }
        // 同一会话不该有两条优化在跑：旧的只取消辅助请求，不影响任何聊天
        if (stale != null && stale !== run) stale.cancel()
        val outcome = try {
            run.start()
            run.await()
        } catch (e: CancellationException) {
            // 外层聊天被取消（用户点了停止、会话销毁）：这是真正的取消，照旧向上抛
            currentCoroutineContext().ensureActive()
            // 只有辅助请求被取消（关开关/会话迁移）：这一轮用原话继续，绝不取消整轮聊天
            InputOptimizationOutcome(
                status = InputOptimizationStatus.FAILED,
                failureReason = if (isCurrentOptimization(conversationId, token)) {
                    "optimization cancelled"
                } else {
                    "optimization superseded"
                },
                cached = false,
            )
        } catch (e: Exception) {
            // 优化器自己抛出来的意外（选模型/包 provider/读缓存/守卫里的任何 bug）也只是这一步失败：
            // 绝不能因为一条锦上添花的辅助请求把用户这条消息整轮发不出去。
            Log.w(TAG, "input optimization crashed; sending the user's own words", e)
            InputOptimizationOutcome(
                status = InputOptimizationStatus.FAILED,
                failureReason = e.message?.take(160) ?: "optimization crashed",
                cached = false,
            )
        } finally {
            synchronized(lifecycleLock) {
                if (optimizationRuns[conversationId]?.token == token) optimizationRuns.remove(conversationId)
            }
        }
        outcome
    }

    /**
     * 重新生成这类"没有新用户消息"的轮次：只登记自查标记，不做优化请求。
     * 没有可定位的消息时什么也不做（宁可没有自查，也不改错消息）。
     */
    fun stageSelfCheck(
        conversation: Conversation,
        messageId: Uuid,
        options: ConversationRuntimeOptions,
    ): InputAssistTurnState? {
        if (options.reviewMode != ConversationReviewMode.SELF_CHECK) return null
        // 重新生成也是一"轮"：领一个新代次，之前那次还在飞的 prepareTurn 就不能再登记
        val generation = InputAssistRuntime.beginTurn(conversation.id)
        val state = InputAssistTurnState(
            conversationId = conversation.id,
            messageId = messageId,
            assistantId = conversation.assistantId,
            selfCheck = true,
        )
        InputAssistRuntime.stageIfCurrent(generation, state)
        return state
    }

    /** 一轮结束时清掉登记：下一轮重新判定，绝不沿用上一轮的优化版。 */
    fun finishTurn(conversationId: Uuid) {
        // 只清"这一轮怎么发请求"的登记；已经起来的审查是独立任务，由 cancelReview/dismissReview 管
        InputAssistRuntime.clear(conversationId)
    }

    /**
     * 只停掉这个会话正在跑的输入优化：这一轮改用原话继续，整轮聊天不动。
     *
     * 关开关时调用它。已经发出去的请求撤回不了，费用也不会因此退回。
     */
    fun cancelOptimization(conversationId: Uuid) {
        val run = synchronized(lifecycleLock) { optimizationRuns.remove(conversationId) } ?: return
        run.job.cancel()
    }

    /**
     * 会话选项变化时统一收敛辅助任务（主代理在 updateRuntimeOptions 里调用）。
     *
     * - 关掉优化 → 只取消优化请求，聊天继续用原话；
     * - 关掉独立审查 → 取消审查（助手回答不受影响）；
     * - 两项都不需要 → 顺手清掉这一轮的请求改写登记。
     */
    fun updateOptions(conversationId: Uuid, options: ConversationRuntimeOptions) {
        if (!options.optimizeUserInput) cancelOptimization(conversationId)
        if (options.reviewMode != ConversationReviewMode.INDEPENDENT) cancelReview(conversationId)
        InputAssistRuntime.updateOptions(
            conversationId,
            optimize = options.optimizeUserInput,
            selfCheck = options.reviewMode == ConversationReviewMode.SELF_CHECK,
        )
    }

    /**
     * 起一次独立审查（异步）。开关不是 INDEPENDENT、文本为空时什么都不做。
     *
     * 一个会话同时只有一个审查任务：起新的之前先取走旧的（取走和登记代号在同一把锁里完成），
     * 旧任务醒来时代号已经不是它，就不会再写状态。命中缓存会直接把结果贴出来，不再发请求。
     */
    fun startReview(
        settings: Settings,
        conversation: Conversation,
        userText: String,
        replyText: String,
        userMessageId: Uuid?,
        replyMessageId: Uuid?,
        options: ConversationRuntimeOptions,
    ) {
        if (options.reviewMode != ConversationReviewMode.INDEPENDENT) return
        if (userText.isBlank() || replyText.isBlank()) return
        val conversationId = conversation.id
        val assistantId = conversation.assistantId
        val key = InputAssistRules.reviewCacheKey(
            assistantId = assistantId,
            conversationId = conversationId,
            userMessageId = userMessageId,
            userText = userText,
            replyMessageId = replyMessageId,
            replyText = AssistRolePrompts.cacheInput(replyText, options.reviewerSystemPrompt, options.reviewerModelId?.toString()),
        )
        val cached = cachedReview(key)
        val token = Uuid.random()
        var job: Job? = null
        var previous: Job? = null
        synchronized(lifecycleLock) {
            // 同一轮已经在审查中（重连、重新生成、恢复重试都可能再要一次）：直接沿用，
            // 不再花第二次钱，也不把正在跑的卡片推倒重来。
            val running = reviewJobs[conversationId]
            if (running != null && running.isActive && reviewKeys[conversationId] == key) {
                return
            }
            previous = reviewJobs.remove(conversationId)
            if (cached != null) {
                // 命中缓存：不发请求，也不留代号——没有在跑的任务，谁也没权再改这张卡片
                reviewTokens.remove(conversationId)
                reviewKeys.remove(conversationId)
                _reviewStates.update { it + (conversationId to InputAssistReviewState.Done(cached)) }
            } else {
                reviewTokens[conversationId] = token
                reviewKeys[conversationId] = key
                _reviewStates.update {
                    it + (conversationId to InputAssistReviewState.Running(replyMessageId))
                }
                val started = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    runReview(
                        settings = settings,
                        conversation = conversation,
                        assistantId = assistantId,
                        userText = userText,
                        replyText = replyText,
                        userMessageId = userMessageId,
                        replyMessageId = replyMessageId,
                        key = key,
                        token = token,
                        explicitModelId = options.reviewerModelId,
                    )
                }
                reviewJobs[conversationId] = started
                job = started
            }
        }
        // 取消和启动都在锁外：取消会同步触发 invokeOnCompletion，不能在锁里做
        previous?.cancel()
        val started = job
        if (started != null) {
            started.invokeOnCompletion { cause -> onReviewCompletion(conversationId, token, started, cause) }
            started.start()
        }
    }

    private suspend fun runReview(
        settings: Settings,
        conversation: Conversation,
        assistantId: Uuid,
        userText: String,
        replyText: String,
        userMessageId: Uuid?,
        replyMessageId: Uuid?,
        key: String,
        token: Uuid,
        explicitModelId: Uuid?,
    ) {
        val outcome = try {
            reviewer.review(
                settings = settings,
                conversation = conversation,
                assistantId = assistantId,
                userText = userText,
                replyText = replyText,
                explicitModelId = explicitModelId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 任何意外都收成失败结论：界面绝不能一直转圈，也不能把原文说成被改过
            Log.w(TAG, "independent review crashed", e)
            ReviewOutcome(
                parsed = null,
                modelName = null,
                failureReason = e.message?.take(160) ?: "review failed",
            )
        }
        val parsed = outcome.parsed
        if (parsed == null) {
            publishIfCurrent(conversation.id, token, InputAssistReviewState.Failed(outcome.failureReason))
            return
        }
        val review = InputAssistReview(
            conversationId = conversation.id,
            userMessageId = userMessageId,
            replyMessageId = replyMessageId,
            verdict = parsed.verdict,
            findings = parsed.findings,
            suggestion = parsed.suggestion,
            limits = parsed.limits.ifBlank { InputAssistNotice.INDEPENDENT_DISCLAIMER },
            modelName = outcome.modelName,
            cached = false,
            independent = true,
            createdAtMillis = clock(),
        )
        val published = publishIfCurrent(conversation.id, token, InputAssistReviewState.Done(review))
        // 只有仍然有效的那一次审查才写缓存：会话已经删除/换了助手时，迟到的结果连缓存都不许留，
        // 否则下次同一个助手再问一次就会把这条早该消失的结论贴出来。
        if (published) remember(key, review)
    }

    private fun onReviewCompletion(conversationId: Uuid, token: Uuid, job: Job, cause: Throwable?) {
        synchronized(lifecycleLock) {
            if (reviewJobs[conversationId] === job) {
                reviewJobs.remove(conversationId)
                reviewKeys.remove(conversationId)
            }
            if (cause == null) return
            // 已经被新的一轮取代：结果和收尾都不许动别人的卡片
            if (reviewTokens[conversationId] != token) return
            reviewTokens.remove(conversationId)
            _reviewStates.update { states ->
                val current = states[conversationId]
                // 只有还停在 Running 时才收尾：已经给出结论的卡片不许被改写
                if (current !is InputAssistReviewState.Running) {
                    states
                } else {
                    states + (conversationId to if (cause is CancellationException) {
                        InputAssistReviewState.Idle
                    } else {
                        InputAssistReviewState.Failed(cause.message?.take(160))
                    })
                }
            }
        }
    }

    /**
     * 取消这个会话排队中/进行中的审查；toggle 关掉时调用。
     *
     * 先摘掉 token 再 cancel：被取消的协程醒来后看到 token 已不是自己，就不会再写任何状态。
     * 取消只停止等待和显示，已经发出的审查请求照样计费。
     */
    fun cancelReview(conversationId: Uuid) {
        val job = synchronized(lifecycleLock) {
            reviewTokens.remove(conversationId)
            reviewKeys.remove(conversationId)
            reviewJobs.remove(conversationId)
        }
        job?.cancel()
        _reviewStates.update { states ->
            if (states[conversationId] is InputAssistReviewState.Running) states - conversationId else states
        }
    }

    /** 用户手动关掉卡片。 */
    fun dismissReview(conversationId: Uuid) {
        cancelReview(conversationId)
        _reviewStates.update { it - conversationId }
    }

    /**
     * 会话被迁移/删除/换助手时调用：把还挂在这条会话上的东西全部收掉。
     *
     * 内存部分同步做完（登记、审查任务、审查缓存、界面状态）；磁盘上的优化缓存交给 IO 线程清，
     * 绝不阻塞调用方。清完之后这个会话不会再把上一个助手/上一段对话的卡片贴出来。
     */
    fun forgetConversation(conversationId: Uuid) {
        cancelOptimization(conversationId)
        InputAssistRuntime.clear(conversationId)
        cancelReview(conversationId)
        synchronized(lifecycleLock) {
            reviewCache.entries.removeAll { it.value.conversationId == conversationId }
        }
        _reviewStates.update { it - conversationId }
        val store = cache
        if (store != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { store.invalidateConversation(conversationId) }
                    .onFailure { Log.w(TAG, "could not drop cached optimization for $conversationId", it) }
            }
        }
    }

    fun reviewStateFor(conversationId: Uuid): InputAssistReviewState =
        reviewStates.value[conversationId] ?: InputAssistReviewState.Idle

    /**
     * 对照显示用：这条消息当前还有效的优化版。
     *
     * 只有"原文没被改过、助手没换、没过期"时才拿得到；拿不到就说明这一轮发出去的就是原话，
     * 界面不该再展示一个可能已经过时的改写版本。
     */
    fun optimizedTextFor(conversation: Conversation, messageId: Uuid): String? {
        val store = cache ?: return null
        val parts = conversation.currentMessages.firstOrNull { it.id == messageId }?.parts.orEmpty()
        val original = InputAssistRules.canonicalUserText(
            parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        ).trim()
        if (original.isEmpty()) return null
        return store.get(
            conversationId = conversation.id,
            messageId = messageId,
            originalHash = InputAssistRules.inputHash(AssistRolePrompts.cacheInput(original,
                conversation.runtime.optimizerSystemPrompt, conversation.runtime.optimizerModelId?.toString())),
            assistantId = conversation.assistantId,
        )?.takeIf { it.status == OptimizationCacheKind.REWRITE }?.optimizedText
    }

    fun hasRunningReview(conversationId: Uuid): Boolean =
        synchronized(lifecycleLock) { reviewJobs[conversationId]?.isActive == true }

    /** 只有代号还是自己时才写状态：其它一律丢弃，避免旧任务覆盖新卡片。 */
    private fun publishIfCurrent(conversationId: Uuid, token: Uuid, state: InputAssistReviewState): Boolean =
        synchronized(lifecycleLock) {
            if (reviewTokens[conversationId] != token) return@synchronized false
            // 已经有结论了，代号就用完了：收回它，别让每个审查过的会话永久留一条记录。
            // 收掉之后这个任务的收尾（onReviewCompletion）看到代号对不上，不会再来改这张卡片。
            reviewTokens.remove(conversationId)
            _reviewStates.update { it + (conversationId to state) }
            true
        }

    private fun isCurrentOptimization(conversationId: Uuid, token: Uuid): Boolean =
        synchronized(lifecycleLock) { optimizationRuns[conversationId]?.token == token }

    private fun cachedReview(key: String): InputAssistReview? = synchronized(lifecycleLock) {
        reviewCache[key]?.copy(cached = true)
    }

    private fun remember(key: String, review: InputAssistReview) {
        synchronized(lifecycleLock) { reviewCache[key] = review }
    }

    /** 供设置页/调试用的统计：当前有多少会话还在跑审查。 */
    fun runningReviewCount(): Int = synchronized(lifecycleLock) {
        reviewJobs.count { it.value.isActive }
    }

    init {
        Log.i(TAG, "input assist ready (optimization and review are opt-in per conversation)")
    }
}
