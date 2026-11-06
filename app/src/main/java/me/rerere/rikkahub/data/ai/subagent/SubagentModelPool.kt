package me.rerere.rikkahub.data.ai.subagent

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SubagentModelStrategy
import me.rerere.rikkahub.service.ChatReconnectCheckpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * 子代理模型池的纯逻辑部分：白名单、排序、轮转、换渠道判定、总预算、续跑检查点。
 *
 * 全部不碰 Android API，可以单独跑 JVM 单测（见 `SubagentModelPoolTest` /
 * `SubagentFailoverPolicyTest`）。运行期的胶水（permit、runAgent）留在 SubagentRunner。
 */

/**
 * 解析一个子代理任务可用的有序模型池。
 *
 * 规则（顺序即优先级）：
 * 1. `modelIds` 非空就是**严格白名单**：只有池内的模型会被尝试。default/fallback 掉在池外时
 *    直接忽略，绝不为了"有个兜底"去拿池外的模型——尤其不会从 Settings 的全局当前助手模型里
 *    偷偷取一个别的助手/别的渠道的模型。
 * 2. 白名单里 defaultModelId 排第一（用户显式选的默认渠道优先），其余按用户配置顺序。
 * 3. `modelIds` 为空时保持 r21 语义并向后兼容：`defaultModelId ?: parentModelId` 打头，
 *    再补 `fallbackModelId`。没有任何可用模型时返回空列表，由调用方报错。
 *
 * 去重保序：同一个 model 在池里只出现一次，不会因为 default/fallback 重复而被多试一遍。
 */
fun resolveSubagentModelPool(
    modelIds: List<Uuid>,
    defaultModelId: Uuid? = null,
    fallbackModelId: Uuid? = null,
    parentModelId: Uuid? = null,
): List<Uuid> {
    val whitelist = modelIds.distinct()
    if (whitelist.isNotEmpty()) {
        return buildList {
            defaultModelId?.takeIf { it in whitelist }?.let { add(it) }
            addAll(whitelist)
        }.distinct()
    }
    return listOfNotNull(defaultModelId ?: parentModelId, fallbackModelId).distinct()
}

/**
 * 排一次任务依次尝试的模型顺序：首选 [first]，然后是池里剩下的。
 *
 * [first] 不在池里（例如设置被改过）时按池顺序走，不把池外模型塞进候选。
 * [maxAttempts] <= 0 视为不限次数，但仍不超过池大小；至少给 1 次机会。
 */
fun planSubagentAttempts(
    pool: List<Uuid>,
    first: Uuid? = null,
    maxAttempts: Int = 0,
): List<Uuid> {
    if (pool.isEmpty()) return emptyList()
    val ordered = buildList {
        first?.takeIf { it in pool }?.let { add(it) }
        pool.forEach { if (it != first) add(it) }
    }
    val cap = when {
        maxAttempts <= 0 -> ordered.size
        else -> maxAttempts.coerceIn(1, ordered.size)
    }
    return ordered.take(cap)
}

/**
 * 渠道分配器：决定一次任务从哪个模型起步。
 *
 * - [SubagentModelStrategy.ORDERED]：总是池首（= 默认模型）。要可预测、要省钱时用。
 * - [SubagentModelStrategy.BALANCED]：游标轮转 + 跳过已满载的模型。同时创建的 N 个独立任务
 *   会被分散到池里的多个渠道，而不是一起打主模型；[active] 给出每个模型当前的运行数，
 *   [perModelLimit] > 0 时会绕开达到上限的模型（全满时仍按轮转顺序用第一个，
 *   让全局并发 permit 去排队，不会因为没渠道就丢弃任务）。
 *
 * 游标按 [key]（通常是父会话 id）分开，互不干扰；同一 key 的多次 pick 严格轮转。
 */
class SubagentChannelRouter {
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    fun pick(
        pool: List<Uuid>,
        strategy: SubagentModelStrategy = SubagentModelStrategy.ORDERED,
        key: String = "",
        active: (Uuid) -> Int = { 0 },
        perModelLimit: Int = 0,
    ): Uuid? {
        if (pool.isEmpty()) return null
        if (pool.size == 1) return pool.first()
        if (strategy == SubagentModelStrategy.ORDERED) return pool.first()
        val cursor = cursors.computeIfAbsent(key) { AtomicInteger(0) }
        val start = Math.floorMod(cursor.getAndIncrement(), pool.size)
        val rotated = List(pool.size) { pool[(start + it) % pool.size] }
        return rotated.firstOrNull { perModelLimit <= 0 || active(it) < perModelLimit } ?: rotated.first()
    }
}

/** 单次尝试的结局。换渠道判定只看这一个枚举，不看异常类型。 */
enum class SubagentAttemptOutcome {
    COMPLETED,
    EMPTY_OUTPUT,
    STEP_LIMIT,
    AWAITING_APPROVAL,
    TIMEOUT,
    ERROR,
}

/**
 * 这次尝试失败后能不能换下一个模型继续。
 *
 * 只有「真的还能再试一次」的情况才换：
 * - [SubagentAttemptOutcome.ERROR]：provider 报错（4xx/5xx/连接失败/非法 JSON 等）；
 * - [SubagentAttemptOutcome.EMPTY_OUTPUT]：模型返回空输出。
 *
 * 明确不换的：
 * - TIMEOUT：超时是**整个任务**的预算，不是每个渠道各有一份，预算花完就没有下一次；
 * - AWAITING_APPROVAL：子代理侧不放开审批，换模型也不会变成可执行；
 * - STEP_LIMIT：步数已经用掉，换渠道等于白给预算；
 * - COMPLETED：没有失败。
 *
 * [autoFailover] 关闭时一律不换。
 */
fun subagentShouldSwitchModel(
    outcome: SubagentAttemptOutcome,
    autoFailover: Boolean,
): Boolean = autoFailover &&
    (outcome == SubagentAttemptOutcome.ERROR || outcome == SubagentAttemptOutcome.EMPTY_OUTPUT)

/**
 * 换渠道前的安全闸：只有能从**已完成的 checkpoint** 续跑时才允许换。
 *
 * 换模型不是重跑任务。已经执行成功的工具（可能带副作用）必须原样保留在续跑上下文里，
 * 在另一个渠道上接着往下走；从零重跑会把同样的工具再执行一遍。所以：
 * - [resumeSeed] 有值（checkpoint 可续跑）→ 允许换，续跑里带着已完成的工具结果；
 * - 有进展但 checkpoint 不可续（工具已开始未执行、媒体半成品）→ 不允许换，任务直接失败，
 *   宁可报错也不重放未知副作用；
 * - 完全没有进展（第一步都没拿到响应）→ 允许换，此时从零开始是安全的。
 */
fun subagentCanSwitchWithCheckpoint(hasProgress: Boolean, canResume: Boolean): Boolean =
    !hasProgress || canResume

/**
 * 整个任务的时间预算。**不分渠道重置**：所有尝试共用同一个截止时刻，
 * 每次尝试只能拿到"剩余时间"，剩余不足时直接不再尝试。
 *
 * [totalSec] <= 0 表示不限制，此时 [remainingMs] 返回 [Long.MAX_VALUE]。
 */
class SubagentTimeBudget(
    totalSec: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val deadline: Long = if (totalSec <= 0) Long.MAX_VALUE else clock() + totalSec * 1000L

    val unlimited: Boolean get() = deadline == Long.MAX_VALUE

    val expired: Boolean get() = !unlimited && clock() >= deadline

    /** 剩余毫秒；不限制时返回 [Long.MAX_VALUE]，可能为 0 或负数（表示预算已花完）。 */
    fun remainingMs(): Long = if (unlimited) Long.MAX_VALUE else deadline - clock()
}

/**
 * 一次运行的续跑检查点：复用主对话的 [ChatReconnectCheckpoint] 口径（只认已完成的模型步和
 * 工具生命周期），这样换渠道续跑和"主对话重连"用的是同一套安全边界。
 *
 * 只读复用：不修改 ChatReconnectPolicy 的内部状态。
 */
class SubagentRunCheckpoint {
    private val inner = ChatReconnectCheckpoint()

    fun observe(message: UIMessage) {
        inner.observe(message)
    }

    /** 最近一个可用的检查点消息（可能包含已完成的工具结果）。 */
    val message: UIMessage? get() = inner.message

    /** 检查点是否可安全续跑（没有半截媒体、没有"已开始但未执行"的工具）。 */
    val canResume: Boolean get() = inner.canResume

    /** 有进展：已经拿到过任何模型输出。 */
    val hasProgress: Boolean get() = inner.message != null

    /** 续跑种子：可续时返回检查点消息，否则 null（调用方按 [subagentCanSwitchWithCheckpoint] 判定）。 */
    fun resumeSeed(): UIMessage? = if (inner.canResume) inner.message else null
}

/**
 * 每模型并发许可：在同一次任务里先拿全局 permit，再按模型拿一个，避免"均衡分配把 3 个任务
 * 全塞到同一个渠道"时把该渠道打爆。
 *
 * [perModelLimit] <= 0 表示不加模型级限制，只受全局子代理并发约束（行为与 r21 一致）。
 */
internal class SubagentModelPermits {
    private val pools = ConcurrentHashMap<Uuid, SubagentPermitPool>()
    private val holders = ConcurrentHashMap<Uuid, AtomicInteger>()

    fun activeCount(modelId: Uuid): Int = holders[modelId]?.get() ?: 0

    /**
     * 申请一个每模型许可。返回 **true 表示真的拿到了**，调用方必须只在 true 时 [release]。
     *
     * 返回 Boolean 而不是 Unit 的原因：`perModelConcurrency` 可能在任务运行期间被用户改成 0。
     * 老实现里 `release(modelId, limit)` 也按当时的 limit 判断，limit 从 3 改成 0 后
     * release 直接 return → 许可与 holders 计数永久泄漏 → BALANCED 路由以为该渠道永远满载，
     * 之后所有任务都绕开它。现在\"拿\"和\"还\"只由调用方确实持有许可这一事实配平。
     */
    suspend fun acquire(modelId: Uuid, limit: Int): Boolean {
        if (limit <= 0) return false
        pools.computeIfAbsent(modelId) { SubagentPermitPool() }.acquire(limit)
        holders.computeIfAbsent(modelId) { AtomicInteger(0) }.incrementAndGet()
        return true
    }

    /** 归还一个许可；不会因为 limit 被改小/改成 0 而跳过，也不会把计数减成负数。 */
    suspend fun release(modelId: Uuid) {
        pools[modelId]?.release() ?: return
        holders[modelId]?.updateAndGet { if (it > 0) it - 1 else 0 }
    }
}

/**
 * 在整任务预算内申请一个每模型许可。
 *
 * 为什么必须带预算：渠道等待（perModelConcurrency 满载排队）发生在 [SubagentTimeBudget] 建好之后，
 * 老实现直接把 `modelPermits.acquire` 裸挂在 withTimeout 外面——渠道一直满载时，一个已经开始、
 * 预算早就花完的任务会永远挂在排队上，既不超时也不失败。这里把等待限制在剩余预算内：
 * - 拿到 → true（正常继续执行）；
 * - 预算耗尽 / 排队超时 → false（调用方按 TIMEOUT 收尾）；
 * - 任务被用户取消 → CancellationException 原样抛出，不吞。
 *
 * 预算是无限的（用户显式 timeout_sec=0）时不做额外限制——这是用户明确要的无限。
 */
internal suspend fun SubagentModelPermits.acquireWithinBudget(
    budget: SubagentTimeBudget,
    modelId: Uuid,
    limit: Int,
): Boolean {
    if (limit <= 0) return false
    val remaining = budget.remainingMs()
    if (remaining == Long.MAX_VALUE) return acquire(modelId, limit)
    if (remaining <= 0) return false
    // withTimeoutOrNull 取消排队不会泄漏许可：SubagentPermitPool 在取消钩子里把许可转给下一个等待者。
    return kotlinx.coroutines.withTimeoutOrNull(remaining) { acquire(modelId, limit) } ?: false
}

/**
 * 换渠道后每个尝试还剩多少模型步数。
 *
 * `runAgent` 的 maxSteps 是**单次请求**的预算；续跑时检查点消息已经用掉的步数（`stepCount`）
 * 不会被自动扣掉，所以老实现里\"40 步 + 换 3 次渠道\"实际能跑 120 步，`max_steps` 设置被放大约
 * `maxModelAttempts` 倍。这里按已完成步数递减，跨尝试守住用户设置的总步数上限。
 */
internal fun subagentRemainingSteps(maxSteps: Int, consumedSteps: Int): Int =
    (maxSteps - consumedSteps.coerceAtLeast(0)).coerceAtLeast(1)

/**
 * `subagent_team` 的一次批量派单，纯数据 + 白名单校验，便于单测。
 *
 * 每个成员是一个独立的子代理任务（自己的 prompt/角色/模型），**不是**同一个任务的多渠道竞速：
 * 团队的作用是把互不相同的副作用分发到不同渠道同时跑，同一个任务永远不会被并发跑两遍。
 */
data class SubagentTeamMember(
    val role: String,
    val prompt: String,
    val modelId: Uuid? = null,
)

/**
 * 把一次 team 调用的成员分配到具体模型：
 * - 成员显式给了 model_id → 必须在白名单内；
 * - 没给 → 按策略从池里分配（BALANCED 依次轮转，多成员自然落到不同渠道）。
 *
 * 返回值与输入等长、同序；空白 prompt 的成员被丢弃；重名的 role 会被加上序号后缀。
 * 这个方法不启动任何任务，只做分配，便于用纯单测验证"3 个成员分散到 3 个渠道"。
 */
fun assignSubagentTeamModels(
    members: List<SubagentTeamMember>,
    pool: List<Uuid>,
    strategy: SubagentModelStrategy = SubagentModelStrategy.BALANCED,
): List<SubagentTeamMember> {
    val usable = members.filter { it.prompt.isNotBlank() }
    require(usable.isNotEmpty()) { "tasks must contain at least one member with a prompt" }
    val cursor = AtomicInteger(0)
    val seen = HashMap<String, Int>()
    return usable.map { member ->
        val role = member.role.trim().ifEmpty { "member" }.let { base ->
            val n = seen.merge(base, 1, Int::plus) ?: 1
            if (n == 1) base else "$base-$n"
        }
        val model = when {
            member.modelId != null -> {
                require(pool.isEmpty() || member.modelId in pool) {
                    "model_id ${member.modelId} is not in the allowed subagent model pool"
                }
                member.modelId
            }
            pool.isEmpty() -> null
            strategy == SubagentModelStrategy.ORDERED -> pool.first()
            else -> pool[Math.floorMod(cursor.getAndIncrement(), pool.size)]
        }
        member.copy(role = role, prompt = member.prompt.trim(), modelId = model)
    }
}
