package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import me.rerere.ai.agent.RetryErrorCategory
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.RetryScope
import me.rerere.ai.agent.retryCategoryOf
import me.rerere.rikkahub.data.ai.mcp.McpToolCallOutcomeUnknownException
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeException
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 这一次失败为什么不能交给自动恢复。
 *
 * 自动恢复的目标是「网络断了不用用户手点」，不是「什么错都再来一遍」。命中这里的任意一条，
 * 调用方只能报错并把队列停成需要用户处理的状态，不能建 ticket、不能等网络、不能静默重发。
 */
enum class ChatRecoveryBlocker {
    /** 用户点了停止，或会话被替换/删除。停止不能复活。 */
    USER_STOPPED,

    /** 停在等待用户审批：这是中断点，不是失败，自动继续等于替用户点同意。 */
    APPROVAL_PENDING,

    /** 登录失效、401/403、Web 会话过期。再试一次也还是 401，只能让用户重新授权。 */
    AUTH,

    /** 余额/配额用尽（402）。等多久都不会自己好。 */
    QUOTA,

    /** 请求格式、参数、内容被拒（400/422、Web 协议解析失败）。同一份请求重发结果相同。 */
    REQUEST_FORMAT,

    /** 权限、Shizuku/系统授权。需要人去授权。 */
    PERMISSION,

    /** 工具已经发出或已经执行过，副作用不确定。重放可能重复写入、重复付费。 */
    UNKNOWN_SIDE_EFFECT,

    /** 本地落库失败（磁盘/文件）。重发不会修好本地存储。 */
    STORAGE,

    /** 其它非传输层的确定性失败。 */
    OTHER,
}

/**
 * 自动恢复的决策层：只回答「这一次失败能不能自动接上」，不碰协程、不碰 Android、不改消息。
 *
 * 传输层那部分仍然交给 [RecoveryPolicy]，避免这里和 `withRetry` 各写一套 is/else 之后分叉；
 * 这里只补上 App 才知道的、绝对不能自动重试的几类（鉴权/配额/格式/权限/停止/审批/未知副作用）。
 */
object ChatAutoRecovery {
    /** 一个会话在这一轮里最多被自动拉起几次。用户的 retry.maxRetries 管单次请求，这个管整轮。 */
    const val DEFAULT_MAX_ATTEMPTS: Int = 3

    /**
     * ticket 的存活时间。网络回来得太晚（比如用户已经关屏一小时）就不再自动续跑：
     * 那时候自动插入一段迟到的回复比什么都不做更让人意外。
     */
    const val TICKET_TTL_MS: Long = 10 * 60 * 1000L

    /**
     * 失败分类。
     *
     * @param cancelled 协程是否被取消（用户停止或会话被整条换掉）
     * @param awaitingApproval 失败时是否停在等待用户审批
     * @param canResume [ChatReconnectCheckpoint.canResume]：没有「已开始但没结果」的工具才为 true
     * @param hasCommittedSideEffects 本轮已经执行过工具、写过文件或生成过图片
     * @param httpStatus 拿得到的状态码；拿不到就传 null，传输层判断不受影响
     * @param scope 用户选的重试范围；默认 [RetryScope.CLASSIFIED]，即老行为
     * @return null 表示可以自动恢复；非 null 表示必须放弃自动恢复
     */
    fun blockReason(
        error: Throwable,
        cancelled: Boolean = false,
        awaitingApproval: Boolean = false,
        canResume: Boolean = true,
        hasCommittedSideEffects: Boolean = false,
        httpStatus: Int? = null,
        scope: RetryScope = RetryScope.CLASSIFIED,
    ): ChatRecoveryBlocker? {
        if (cancelled || error is CancellationException) {
            return ChatRecoveryBlocker.USER_STOPPED
        }
        if (awaitingApproval) return ChatRecoveryBlocker.APPROVAL_PENDING
        // 工具已经开始但没结果，或本轮本来就有副作用：重放会把副作用做第二遍
        if (hasCommittedSideEffects || !canResume) return ChatRecoveryBlocker.UNKNOWN_SIDE_EFFECT
        // 安全边界：这些不是"模型请求错误"，任何模式都不自动重试，用户勾了"任何错误"也不开
        when (error) {
            is WorkspacePrivilegeException -> return ChatRecoveryBlocker.PERMISSION
            is McpToolCallOutcomeUnknownException -> return ChatRecoveryBlocker.UNKNOWN_SIDE_EFFECT
            is InboxPersistenceException, is FileNotFoundException -> return ChatRecoveryBlocker.STORAGE
        }
        // 和 withRetry 用同一个折算：真实类型 + 真实状态码，绝不从消息文本猜数字
        val category = retryCategoryOf(error, httpStatus)
        if (scope.allows(category)) return null
        return when (category) {
            RetryErrorCategory.AUTH -> ChatRecoveryBlocker.AUTH
            RetryErrorCategory.QUOTA -> ChatRecoveryBlocker.QUOTA
            RetryErrorCategory.REQUEST_FORMAT -> ChatRecoveryBlocker.REQUEST_FORMAT
            RetryErrorCategory.NETWORK, RetryErrorCategory.SERVER,
            RetryErrorCategory.EMPTY_RESPONSE -> ChatRecoveryBlocker.OTHER
            RetryErrorCategory.CUSTOM_MATCH,
            RetryErrorCategory.UNKNOWN -> ChatRecoveryBlocker.OTHER
        }
    }

    /** [blockReason] 的布尔形式，给只关心能不能接的调用点用 */
    fun mayAutoRecover(
        error: Throwable,
        cancelled: Boolean = false,
        awaitingApproval: Boolean = false,
        canResume: Boolean = true,
        hasCommittedSideEffects: Boolean = false,
        httpStatus: Int? = null,
        scope: RetryScope = RetryScope.CLASSIFIED,
    ): Boolean = blockReason(
        error = error,
        cancelled = cancelled,
        awaitingApproval = awaitingApproval,
        canResume = canResume,
        hasCommittedSideEffects = hasCommittedSideEffects,
        httpStatus = httpStatus,
        scope = scope,
    ) == null

    /**
     * 一轮对话里最多自动拉起几次（外层）。
     *
     * - 有限模式：就是用户设的次数（[RetryPolicy.maxRetries]），和界面"重试 N 次"一致；
     * - 「一直重试到手动停止」：没有次数上限，只有取消生成、关掉总开关、命中安全边界（鉴权/额度/权限/
     *   未知副作用……）或 ticket 10 分钟有效期才会停。以前的 20 次硬上限和"直到停止"是矛盾的，已删掉。
     *
     * 内外两层的总请求数是 **(N+1) + N = 2N+1**，线性；整轮内多个模型步骤另外共用一份预算
     * （[RetryPolicy.sharedBudget]），不会随工具步数再乘一次。
     */
    fun turnAttemptLimit(policy: RetryPolicy): Int = if (policy.unlimitedRetries) {
        Int.MAX_VALUE
    } else {
        policy.maxRetries.coerceIn(0, 100)
    }

    /** 自动重试是不是已经关掉（总开关关闭，或次数为 0 且没开"一直重试"）。 */
    fun retryDisabled(policy: RetryPolicy): Boolean = !policy.unlimitedRetries && policy.maxRetries <= 0

    /** 整轮自动恢复的次数是否已经用完（无限模式永远没用完）。 */
    fun turnExhausted(policy: RetryPolicy, turnAttempts: Int): Boolean =
        turnAttempts >= turnAttemptLimit(policy)

    /**
     * 这一轮**内层**（单次请求、整轮内所有模型步骤）还允许重试几次。
     *
     * 第一轮按用户设置给满；自动恢复拉起的轮次一律为 0 —— 那一轮本身就是为了接上上一次失败，
     * 内层再连环重试就是"外层无限 × 内层 N 次"的叠加。整轮的次数由外层 [turnAttemptLimit] 管，
     * 两边加起来是 2N+1 次请求，不是 N(N+1)/2 那种平方（也不是"平方减半"）。
     */
    fun requestRetryAllowance(policy: RetryPolicy, turnAttempts: Int): Int =
        if (turnAttempts <= 0) policy.maxRetries else 0

    /**
     * 给这一轮生成用的设置快照：自动恢复轮把**内层**重试彻底关掉（次数 0，同时取消"一直重试"，
     * 否则 maxRetries=0 会被无限标记重新打开）。第一轮原样返回。
     *
     * 只影响这一轮快照里的 `networkSetting.maxRetries/retryUnlimited`：总开关、范围、等待时长一个不动，
     * 落库值也不动。`Settings.retryPolicy()` 用这份快照时，本轮每次模型请求都只发一次；
     * 外层恢复读的是 `settingsStore` 的实时值，次数与界面显示仍是用户设置的那个数。
     */
    fun settingsForTurnRecovery(settings: Settings, turnAttempts: Int): Settings {
        if (turnAttempts <= 0) return settings
        val network = settings.networkSetting
        if (network.maxRetries == 0 && !network.retryUnlimited) return settings
        return settings.copy(networkSetting = network.copy(maxRetries = 0, retryUnlimited = false))
    }
}

/**
 * 每个会话的自动恢复账本：有限次数 + ticket 有效期。
 *
 * 只记两件事：这一轮已经自动拉起过几次、现有 ticket 是几点发的。计数按会话隔离，
 * 一个会话把 3 次用完不影响另一个会话。所有方法线程安全，调用方不需要再包一层锁。
 *
 * 调用方需要遵守的契约：
 * - 一次失败调用一次 [noteFailure]，返回 true 才建 ticket；
 * - 用户停止、用户发新消息、换助手、删除会话、审批结束都调用一次 [clear]；
 * - 网络恢复后先问 [resumeAllowed]，false 就不再拉起（ticket 过期或次数用完）；
 * - 进程长时间在后台时调 [sweep] 顺手清掉过期会话。
 */
class ChatRecoveryLedger(
    private val maxAttempts: Int = ChatAutoRecovery.DEFAULT_MAX_ATTEMPTS,
    private val ticketTtlMillis: Long = ChatAutoRecovery.TICKET_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry {
        var attempts: Int = 0
        var issuedAt: Long? = null
    }

    private val entries = ConcurrentHashMap<Uuid, Entry>()

    /**
     * 记一次失败：还能不能再自动拉起。
     *
     * 返回 false 表示这个会话在这一轮里已经把 [maxAttempts] 次自动恢复用完了，
     * 调用方必须直接报错并把上下文留给用户看，而不是继续等网络。
     */
    fun noteFailure(conversationId: Uuid, limit: Int = maxAttempts): Boolean {
        val entry = entries.computeIfAbsent(conversationId) { Entry() }
        return synchronized(entry) {
            // 上限由调用方按重试策略算好（无限模式给 Int.MAX_VALUE），这里只拒绝负数
            if (entry.attempts >= limit.coerceAtLeast(0)) {
                false
            } else {
                entry.attempts++
                entry.issuedAt = clock()
                true
            }
        }
    }

    /** 这个会话这一轮已经用掉几次自动恢复 */
    fun attempts(conversationId: Uuid): Int {
        val entry = entries[conversationId] ?: return 0
        return synchronized(entry) { entry.attempts }
    }

    /** 还有没有 ticket，且没有过期 */
    fun hasFreshTicket(conversationId: Uuid, now: Long = clock()): Boolean {
        val entry = entries[conversationId] ?: return false
        return synchronized(entry) {
            val issuedAt = entry.issuedAt ?: return@synchronized false
            now - issuedAt in 0..ticketTtlMillis
        }
    }

    /** 网络回来时用：还有 ticket、没过期、次数没超才允许续跑 */
    fun resumeAllowed(conversationId: Uuid, now: Long = clock()): Boolean {
        val entry = entries[conversationId] ?: return false
        return synchronized(entry) {
            val issuedAt = entry.issuedAt ?: return@synchronized false
            now - issuedAt in 0..ticketTtlMillis
        }
    }

    /** ticket 已经发太久了（或者根本没有 ticket） */
    fun isStale(conversationId: Uuid, now: Long = clock()): Boolean {
        val entry = entries[conversationId] ?: return true
        return synchronized(entry) {
            val issuedAt = entry.issuedAt ?: return@synchronized true
            now - issuedAt > ticketTtlMillis
        }
    }

    /** 成功、停止、发新消息、换助手、删除、审批：把这个会话的账清干净 */
    fun clear(conversationId: Uuid) {
        entries.remove(conversationId)
    }

    /**
     * 清掉所有过期会话，返回被清掉的会话 id。
     *
     * 调用方拿到 id 后负责把那些会话残留的「等网络」状态收掉（清 ticket、把队列暂停原因从
     * NETWORK 改成 ERROR），否则列表里会一直显示一条永远不会继续的等待。
     */
    fun sweep(now: Long = clock()): List<Uuid> {
        val stale = entries.entries
            .filter { (_, entry) ->
                synchronized(entry) {
                    val issuedAt = entry.issuedAt ?: return@synchronized false
                    now - issuedAt > ticketTtlMillis
                }
            }
            .map { it.key }
        stale.forEach(entries::remove)
        return stale
    }

    /** 当前还被记账的会话，便于日志与测试（只读快照） */
    fun tracked(): Set<Uuid> = entries.keys.toSet()
}
