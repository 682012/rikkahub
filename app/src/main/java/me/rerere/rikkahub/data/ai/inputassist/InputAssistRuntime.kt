package me.rerere.rikkahub.data.ai.inputassist

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

/**
 * 进程内「这一轮用哪条优化版」的登记处。
 *
 * 为什么需要它：优化必须在 `createContext` 之前就定好（那时才拿得到原消息 id 和助手 id），
 * 而请求组装发生在 `runTurn` 里、`createAgent` 那一刻。两边都改共享类只会把构造参数越接越长，
 * 所以把这一轮的事实按会话登记在这里，请求组装时按会话取一次、按消息 id 精确替换。
 *
 * 规则：
 * - 一轮结束（[clear]）就没了；不跨轮、不跨会话、不跨助手；
 * - 断网重连会再组装一次请求，但登记还在，**不会**重新优化、不会二次扣费；
 * - 只有 staged 里那条 messageId 会被改写，其它历史消息一个字都不动。
 *
 * 迟到的登记（[beginTurn] / [stageIfCurrent]）：
 * 优化是网络请求，`prepareTurn` 可能慢到这一轮已经被收掉之后才回来。为了不让它把过期的一轮
 * 重新登记回来（会话已删、助手已换、开关已关），每个会话有一个单调递增的**代次**：
 * - 开始一轮时领一个代次（[beginTurn]）；
 * - 只有代次还是最新的那次才允许登记（[stageIfCurrent]）；
 * - [clear] 会作废代次，所以"收掉这一轮"之后再回来的登记一律被拒。
 *
 * 这里不碰 Android、不碰协程调度，纯内存判断，可以离线单测。
 */
object InputAssistRuntime {
    private val states = ConcurrentHashMap<Uuid, InputAssistTurnState>()

    /** 每个会话当前的代次；[clear] 之后旧代次永远拿不回登记权。 */
    private val generations = ConcurrentHashMap<Uuid, Long>()

    /** 全局单调递增，避免"两个会话各自从 1 数"时出现代次相等的情况。 */
    private val counter = AtomicLong(0)

    /** 开始一轮：领一个代次。同一个会话再开一轮时，之前的代次立即作废。 */
    @Synchronized
    fun beginTurn(conversationId: Uuid): Long {
        val next = counter.incrementAndGet()
        generations[conversationId] = next
        states.remove(conversationId)
        return next
    }

    /**
     * 只有代次还是当前代次时才登记。
     *
     * 返回 false 表示这一轮已经过期（被下一轮顶掉、或被 [clear] 收掉）：调用方什么都不用做，
     * 请求组装拿不到登记就等于用原话发送，这正是我们想要的结果。
     */
    @Synchronized
    fun stageIfCurrent(generation: Long, state: InputAssistTurnState): Boolean {
        if (generations[state.conversationId] != generation) return false
        states[state.conversationId] = state
        return true
    }

    @Synchronized
    fun stateFor(conversationId: Uuid): InputAssistTurnState? = states[conversationId]

    /** Invalidate a pending preparation and apply switch changes to the already-staged copy. */
    @Synchronized
    fun updateOptions(conversationId: Uuid, optimize: Boolean, selfCheck: Boolean) {
        generations.remove(conversationId)
        val previous = states[conversationId] ?: return
        if (!optimize && !selfCheck) {
            states.remove(conversationId)
        } else {
            states[conversationId] = previous.copy(
                optimization = if (optimize) previous.optimization else
                    InputOptimizationOutcome(InputOptimizationStatus.NOT_REQUESTED),
                selfCheck = selfCheck,
            )
        }
    }

    /**
     * 收掉这一轮的登记，并作废代次：从此还在飞的那次 `prepareTurn` 不能再把它登记回来。
     * 返回被清掉的状态，调用方可以据此决定要不要留审查卡片。
     */
    @Synchronized
    fun clear(conversationId: Uuid): InputAssistTurnState? {
        generations.remove(conversationId)
        return states.remove(conversationId)
    }

    @Synchronized
    fun clearAll() {
        states.clear()
        generations.clear()
    }

    fun size(): Int = states.size
}
