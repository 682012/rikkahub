package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 一次 agent 运行的输入。
 *
 * [messages] 的最后一条是助手消息时，从它继续：先处理它上面还没执行的工具调用（比如审批之后），
 * 再请求模型；否则新建一条助手消息。一次运行只产出这一条助手消息，其余消息只读，不会被改写。
 */
data class AgentRequest(
    val provider: Provider<ProviderSetting>,
    val providerSetting: ProviderSetting,
    val messages: List<UIMessage>,
    val tools: List<Tool> = emptyList(),
    val stream: Boolean = true,
    /** 最多请求模型的次数 */
    val maxSteps: Int = 256,
    val retry: RetryPolicy = RetryPolicy.NONE,
)

/** 调用方在循环各个节点上的介入点 */
interface AgentHooks {
    /**
     * 每次请求模型之前调用，返回这一步实际发出的消息和参数，系统提示词、上下文裁剪、输入转换都在这里做。
     *
     * [messages] 是历史加上本轮的助手消息；第一步还没有内容时不包含它。
     * 返回的 [StepRequest.params] 里的 tools 会被换成 [AgentRequest.tools]，声明给模型的工具和能执行的工具始终是同一份。
     */
    suspend fun prepareStep(messages: List<UIMessage>): StepRequest

    /**
     * 生成过程中每次发出更新之前调用，返回值只用于这一次 [AgentEvent.MessageUpdated]，不会写回循环内部。
     * 适合 think 标签这类要边生成边展示、等这一步结束才真正处理的转换；[newPartsFrom] 的含义同 [finishStep]。
     */
    suspend fun previewStep(message: UIMessage, newPartsFrom: Int): UIMessage = message

    /**
     * 每一步生成结束、执行工具之前调用，返回值作为这一步的结果。
     *
     * 下标 [newPartsFrom] 及之后的 part 是这一步新生成的；之前的来自更早的步骤，已经处理过一次。
     */
    suspend fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage = message

    /**
     * 请求模型之前、[prepareStep] 之前调用，返回要追加到本轮助手消息末尾的 part，通常是 [UIMessagePart.Notice]。
     *
     * 只在轮到模型回复时调用：新的一轮开始时，一批工具执行完之后，或消息以通知结尾时；续写已有的回复时不调用，
     * 否则追加的内容会变成新的一轮。返回的 part 会立即写回消息，调用方取出后应当不再重复返回。
     */
    suspend fun pendingParts(): List<UIMessagePart> = emptyList()

    /**
     * 运行中用户插话：用户在模型生成期间发来的输入，返回的 part 会作为新的一轮用户内容
     * 追加到本轮助手消息末尾，让下个步骤立刻看到。
     *
     * 和 [pendingParts] 分开是因为语义不同：[pendingParts] 是后台事件，不能自己催动模型多跑一步；
     * 这里返回了内容就说明用户要求模型现在回应，所以模型给出最终回复后仍会再走一步（见 [runAgent]）。
     * 只在安全点调用：没有待执行的工具、不在等审批时。返回的内容会立即写回消息，调用方取出后
     * 应当不再重复返回（主代理负责 ack 收件箱）。
     *
     * 默认空实现：既有调用方行为完全不变。
     */
    suspend fun pendingUserParts(): List<UIMessagePart> = emptyList()

    /**
     * m3「会话鞭策」：一次运行**正常结束之前**的最后一次机会，返回一句要追加给模型的推进语，
     * 返回 null / 空 = 不干预，本轮照常结束。
     *
     * 只在真正的安全点调用（见 [me.rerere.ai.agent.AgentLoop]）：没有待执行的工具、没有在等审批、
     * 也没有半截回复要续写。调用方给的是当前这条助手消息（含本步刚生成的内容），
     * 判定必须是**基于可见证据的保守启发式**，不能声称能可靠识别"偷懒"。
     *
     * 返回内容时循环把它作为 [UIMessagePart.Notice] 追加到同一条助手消息末尾，然后**继续用同一个
     * 正常步数预算**再走一步：不新增用户"继续"消息、不新建会话、不重放任何工具、不改权限，
     * 也不会突破 [AgentRequest.maxSteps]。返回 null 时行为与没有这个钩子完全一致。
     *
     * 默认 null：既有调用方与既有测试的路径逐字节不变（不追加 part、不打额外模型请求）。
     */
    suspend fun completionNudge(message: UIMessage): NudgePrompt? = null
}

/**
 * 一次「会话鞭策」要追加给模型的内容。
 *
 * [noticeText] 是**发给模型**的那句话（会被 [me.rerere.ai.ui.expandNotices] 拆成一条合成 user 消息）；
 * [noticeTitle] 只用于界面展示；[marker] 写进 [UIMessagePart.Notice.metadata]，
 * 用来在同一条回复里给已用次数计数（恢复/落库之后再读到也不会重置）。
 */
data class NudgePrompt(
    val noticeTitle: String,
    val noticeText: String,
    val marker: String,
)

data class StepRequest(
    val messages: List<UIMessage>,
    val params: TextGenerationParams,
)

sealed interface AgentEvent {
    /** 本轮助手消息的最新内容，整次运行 id 不变 */
    data class MessageUpdated(val message: UIMessage) : AgentEvent

    /** 请求失败，即将重试；之后再收到 [MessageUpdated] 说明重试的请求已经返回内容 */
    data class Retrying(val attempt: RetryAttempt) : AgentEvent

    /** 运行结束，总是最后一个事件；出错时直接抛出异常，不会发出这个事件 */
    data class Finished(val message: UIMessage, val reason: AgentFinishReason) : AgentEvent
}

sealed interface AgentFinishReason {
    /** 模型最后一步没有再调用工具 */
    data object Completed : AgentFinishReason

    /** 有工具在等待用户审批；写入审批结果后，用这条消息再运行一次即可继续 */
    data class AwaitingApproval(val toolCallIds: Set<String>) : AgentFinishReason

    /** 达到 [AgentRequest.maxSteps]，最后一步调用的工具已经执行，但模型还没有回复 */
    data object StepLimitReached : AgentFinishReason
}

/**
 * 运行 agent 循环：请求模型 → 执行工具 → 再请求模型，直到模型不再调用工具、有工具需要审批或达到步数上限。
 *
 * 显式声明可并发的相邻工具最多 8 路并发，其余工具是顺序屏障；同一只读批次内相同调用合并。
 * 有任意一个需要审批时，同一批工具都等审批后再执行，未知/写入工具不会被猜测为只读。
 * 返回的 Flow 不切换调度器，Provider 请求和工具执行都在收集方的上下文里运行，需要时由调用方 flowOn。
 * 取消时直接抛出，最后一个 [AgentEvent.MessageUpdated] 就是取消前的状态，已经执行完的工具结果都在里面。
 */
fun runAgent(
    request: AgentRequest,
    hooks: AgentHooks,
    // 计时用的单调时钟；默认生产时钟，测试可注入固定时钟来验证窗口/重试口径
    nanoTime: () -> Long = System::nanoTime,
): Flow<AgentEvent> = flow {
    require(request.maxSteps > 0) { "maxSteps must be positive" }
    AgentLoop(request, hooks, this, nanoTime).run()
}
