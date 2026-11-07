package me.rerere.ai.agent

/**
 * 一次失败到底属于哪一类。
 *
 * 分成「哪一类」而不是直接看异常类型，是因为重试、界面提示、待发队列三处需要的是同一套判断：
 * 用户点了停止不能复活；没发出去的请求没花 token 可以原样重发；换网作废的连接不是模型的错；
 * 半截流不能当成功。这个文件不引用任何 Android / 协程 / provider 类型，纯 Kotlin，
 * 所以能被直接单测，也能被 ChatService 与 [withRetry] 共用。
 */
enum class FailureKind {
    /** 用户点了停止。不能重试、不能进待发队列、不能自动复活。 */
    USER_STOPPED,

    /** 请求根本没离开设备（没有可用默认网络）。没花 token，网络回来后可以原样重发。 */
    OFFLINE,

    /** 请求在途时网络换代（换 Wi-Fi / 换 VPN 节点）。连接作废，可以在新路由上重发。 */
    ROUTE_RETIRED,

    /** 传输层提前关闭且没收到协议终态。半截回复，可整条重发，但不能当完成持久化。 */
    TRUNCATED,

    /** 传输故障：超时、连接重置、读到一半断开。 */
    TRANSPORT,

    /** 服务端暂时性错误（408 / 429 / 5xx）。按次数重试，但不要排队等网络。 */
    SERVER,

    /**
     * 用户显式打开「任何错误也重试」之后才可重试的失败：401/403、402/额度、400/422/格式、未知异常。
     *
     * 默认分类下这些还是 [TERMINAL]；[RetryScope] 放行时才归到这一类，好让预算按次数记账、
     * 界面能说清楚"在重试的是你勾选的那一类错误"。它和 [TERMINAL] 的区别只在 `isRetryable`。
     */
    OPT_IN,

    /** 4xx、鉴权、内容被拒。重发也一样，直接失败。 */
    TERMINAL,
}

/** 判断失败类别需要的纯事实。由调用方从真实异常折算，[RecoveryPolicy] 只做决策。 */
data class FailureFacts(
    /** 协程已经被取消（用户点了停止 / 会话被替换）。 */
    val cancelled: Boolean = false,
    /** 请求没有发出去。 */
    val notSent: Boolean = false,
    /** 请求在途时网络换代。 */
    val routeRetired: Boolean = false,
    /** 响应流没有协议终态就结束了。 */
    val truncatedStream: Boolean = false,
    /** HTTP 状态码，拿不到就是 null。 */
    val httpStatus: Int? = null,
    /**
     * 服务端明确说了「额度/余额用完了」。
     *
     * 这类 429 和普通的频率限制长得一样，但重发永远不会好：只有这一个事实能把两边区分开。
     * 少了它，[RecoveryPolicy] 会把额度错误归成 SERVER（可重试），而 [retryableFailure] 已经
     * 判成不可重试——两层判断分叉，自动恢复就会拿着额度错误反复重发。
     */
    val quotaExhausted: Boolean = false,
    /** 是否是传输层故障（IOException 一族）。 */
    val transport: Boolean = true,
)

object RecoveryPolicy {
    /**
     * 一次生成里最多容忍几次「连接被换网作废」。
     *
     * 它不占用用户设置的重试次数：换网后的请求从来没有在新网络上跑过，
     * 拿它去扣用户的三次机会，正是「切个 Wi-Fi 就再也不重连」的原因。
     * 但也不能无限：否则一条流被反复换网打断就会永远重放。
     */
    const val ROUTE_RETIRED_LIMIT: Int = 8

    /**
     * 离线时最多等几轮。每轮都按用户配置的间隔等，网络恢复会立刻唤醒，
     * 所以这个上限只决定「一直没网时最多等多久」，不决定「一次失败的次数」。
     */
    const val OFFLINE_WAIT_LIMIT: Int = 10

    fun classify(facts: FailureFacts, scope: RetryScope = RetryScope.CLASSIFIED): FailureKind {
        if (facts.cancelled) return FailureKind.USER_STOPPED
        if (facts.notSent) return FailureKind.OFFLINE
        if (facts.routeRetired) return FailureKind.ROUTE_RETIRED
        // 额度用尽的 429 必须先于下面的 SERVER 判定：它带着 429 的码，但默认重发不会好
        if (facts.quotaExhausted) {
            return if (scope.allows(RetryErrorCategory.QUOTA)) FailureKind.OPT_IN else FailureKind.TERMINAL
        }
        val status = facts.httpStatus
        if (status == 408 || status == 429 || (status != null && status >= 500)) {
            return if (scope.allows(RetryErrorCategory.SERVER)) FailureKind.SERVER else FailureKind.TERMINAL
        }
        // 其余真实 4xx（401/403/402/400/422……）默认终止，用户勾了对应类别才归到 OPT_IN
        if (status != null) {
            return if (scope.allows(categoryOf(facts))) FailureKind.OPT_IN else FailureKind.TERMINAL
        }
        if (facts.truncatedStream) {
            return if (scope.allows(RetryErrorCategory.EMPTY_RESPONSE)) FailureKind.TRUNCATED
            else FailureKind.TERMINAL
        }
        if (facts.transport) {
            return if (scope.allows(RetryErrorCategory.NETWORK)) FailureKind.TRANSPORT
            else FailureKind.TERMINAL
        }
        return if (scope.allows(categoryOf(facts))) FailureKind.OPT_IN else FailureKind.TERMINAL
    }

    /**
     * 从同一份事实里读出用户可选类别，界面和 [classify] 都用它，不另写一套 is/else。
     */
    fun categoryOf(facts: FailureFacts): RetryErrorCategory = when {
        facts.quotaExhausted -> RetryErrorCategory.QUOTA
        facts.httpStatus == 401 || facts.httpStatus == 403 -> RetryErrorCategory.AUTH
        facts.httpStatus == 402 -> RetryErrorCategory.QUOTA
        facts.httpStatus == 408 || facts.httpStatus == 429 ||
            (facts.httpStatus != null && facts.httpStatus >= 500) -> RetryErrorCategory.SERVER
        facts.httpStatus != null -> RetryErrorCategory.REQUEST_FORMAT
        facts.truncatedStream -> RetryErrorCategory.EMPTY_RESPONSE
        facts.transport -> RetryErrorCategory.NETWORK
        else -> RetryErrorCategory.UNKNOWN
    }

    /** 这一类失败还值不值得再试。用户停止和默认终止的失败一律不试。 */
    fun isRetryable(kind: FailureKind): Boolean = when (kind) {
        FailureKind.USER_STOPPED, FailureKind.TERMINAL -> false
        else -> true
    }

    /**
     * 纯文本回复能不能整条重发。
     *
     * [hasCommittedSideEffects] 为 true（这一步已经执行过工具、已经生成/保存过图片或文件）时永远不重放：
     * 重发只会再花一次钱，不会把已经发生的副作用收回来。
     */
    fun mayReplayText(kind: FailureKind, hasCommittedSideEffects: Boolean): Boolean =
        !hasCommittedSideEffects && isRetryable(kind)

    /** 这条失败该不该留在「等网络」的队列里，而不是当成一次真正的失败上报。 */
    fun waitsForNetwork(kind: FailureKind): Boolean =
        kind == FailureKind.OFFLINE || kind == FailureKind.ROUTE_RETIRED

    /** 给日志和界面的一句话说明。不带 URL、不带账号。 */
    fun describe(kind: FailureKind): String = when (kind) {
        FailureKind.USER_STOPPED -> "已停止"
        FailureKind.OFFLINE -> "当前没有可用网络，恢复后继续"
        FailureKind.ROUTE_RETIRED -> "网络已切换，正在新网络上重连"
        FailureKind.TRUNCATED -> "连接中断，回复没有收完"
        FailureKind.TRANSPORT -> "网络错误"
        FailureKind.SERVER -> "服务端暂时不可用"
        FailureKind.OPT_IN -> "你选择了这类错误也重试"
        FailureKind.TERMINAL -> "请求被拒绝"
    }

    /** 某一类用户可选错误的说法，重试状态里用（例：等 30 秒后重试「鉴权失败」）。 */
    fun describe(category: RetryErrorCategory): String = when (category) {
        RetryErrorCategory.NETWORK -> "网络错误"
        RetryErrorCategory.SERVER -> "服务端暂时不可用"
        RetryErrorCategory.AUTH -> "鉴权失败"
        RetryErrorCategory.QUOTA -> "额度或余额不足"
        RetryErrorCategory.REQUEST_FORMAT -> "请求格式或响应格式错误"
        RetryErrorCategory.EMPTY_RESPONSE -> "空响应或回复中断"
        RetryErrorCategory.UNKNOWN -> "未知错误"
        RetryErrorCategory.CUSTOM_MATCH -> "自定义错误匹配"
    }
}

/**
 * 重试预算账本：把「真实失败」「没发出去」「被换网作废」分开记账。
 *
 * 抽出来的原因：[withRetry] 的循环没法在纯 JVM 里断言预算，而预算算错正是
 * 「断网超过几次就永久不再重连」的根因，必须能被单测直接问。
 */
class RetryBudget(
    val maxRetries: Int,
    /** 用户选的重试范围；默认老行为。 */
    val scope: RetryScope = RetryScope.CLASSIFIED,
    /**
     * 用户勾了「一直重试到停止」后为 true：[maxRetries] 不再限制真正失败的重试次数。
     * 离线等待与换网作废仍各有独立上限，否则一条永远连不上的流会被无限重放。
     */
    val unlimited: Boolean = false,
) {
    /** 已经用掉的重试次数，只统计真正失败过的尝试。 */
    var retries: Int = 0
        private set

    /** 离线等待轮数。 */
    var unsent: Int = 0
        private set

    /** 被换网作废的次数。 */
    var retired: Int = 0
        private set

    /** 最近一次离线等待所属的网络代际，用来在换代后重新给离线预算。 */
    private var offlineEpoch: Long? = null

    /** 展示给界面的「第几次重试」：没有真正失败过时仍算第一次。 */
    val attempt: Int get() = maxOf(retries, 1)

    /**
     * 记一笔失败，返回还能不能继续重试。
     *
     * 用户停止和 4xx 直接结束；离线与换网作废有各自的上限，不占用 [maxRetries]。
     * [maxRetries] <= 0（用户关掉自动重试）时只有第一条首发，不做任何等待。
     */
    fun record(kind: FailureKind, epoch: Long? = null): Boolean = when (kind) {
        FailureKind.USER_STOPPED, FailureKind.TERMINAL -> false
        FailureKind.OFFLINE -> {
            if (maxRetries <= 0) return false
            // 换了新网络就重新给离线等待预算：上一代网络上等满的轮数不该算到新网络上
            val previous = offlineEpoch
            if (previous != null && epoch != null && previous != epoch) unsent = 0
            offlineEpoch = epoch
            unsent++
            unsent <= RecoveryPolicy.OFFLINE_WAIT_LIMIT
        }

        FailureKind.ROUTE_RETIRED -> {
            if (maxRetries <= 0) return false
            retired++
            retired <= RecoveryPolicy.ROUTE_RETIRED_LIMIT
        }

        FailureKind.TRANSPORT, FailureKind.SERVER, FailureKind.TRUNCATED, FailureKind.OPT_IN -> {
            if (!unlimited && (maxRetries <= 0 || retries >= maxRetries)) return false
            retries++
            true
        }
    }
}
