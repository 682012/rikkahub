package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
sealed class ToolApprovalState {
    @Serializable
    @SerialName("auto")
    data object Auto : ToolApprovalState()

    @Serializable
    @SerialName("pending")
    data object Pending : ToolApprovalState()

    @Serializable
    @SerialName("approved")
    data object Approved : ToolApprovalState()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String = "") : ToolApprovalState()

    @Serializable
    @SerialName("answered")
    data class Answered(val answer: String) : ToolApprovalState()
}

fun ToolApprovalState.canResumeToolExecution(): Boolean {
    return when (this) {
        ToolApprovalState.Approved -> true
        is ToolApprovalState.Denied -> true
        is ToolApprovalState.Answered -> true
        ToolApprovalState.Auto,
        ToolApprovalState.Pending,
        -> false
    }
}

/**
 * 服务端工具调用的通用生命周期状态。
 *
 * Provider 返回的更细粒度状态应保存在 [UIMessagePart.ServerTool.metadata] 中，避免在通用层绑定具体协议。
 */
@Serializable
enum class ServerToolStatus {
    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,
}

/** The kind of text carried by a reasoning part. */
@Serializable
enum class ReasoningType {
    @SerialName("reasoning_text")
    REASONING_TEXT,

    @SerialName("summary_text")
    SUMMARY_TEXT,
}

/**
 * [UIMessagePart.Tool.input] 这段**原始参数文本**的完整度。
 *
 * 工具的入参是流式拼出来的 JSON，Provider 在参数还没发完时断线、EOF、被 max_tokens 截断或用户取消，
 * 都可能在任意字符位置停下。这时已经收到的原文必须留着（用户要看到模型究竟发了什么，恢复时也不能
 * 凭它执行），但又绝不能当成"参数就长这样"去跑工具。因此参数原文和它的完整度分开记：
 *
 * - [STREAMING]：还在流式接收，后续还会有 delta。
 * - [INTERRUPTED]：流已经结束（断线/取消/截断/解析不出来），参数确定不完整。**永不执行**。
 * - [COMPLETE]：Provider 明确结束了这个调用（或这是一条非流式的完整响应），参数可以进入审批/执行。
 *
 * 旧记录里没有这个字段，缺省按 [COMPLETE] 解码，保持向后兼容：老消息里已经执行完的工具、
 * 以及非流式响应生成的完整调用都照旧工作。
 */
@Serializable
enum class ToolInputState {
    @SerialName("streaming")
    STREAMING,

    @SerialName("interrupted")
    INTERRUPTED,

    @SerialName("complete")
    COMPLETE,
}

/** 参数是否已经收完整，可以进入审批/执行流程。 */
val ToolInputState.isComplete: Boolean get() = this == ToolInputState.COMPLETE

/**
 * 判断 Provider 的 finish reason 是否表示"被截断"（而不是模型正常说完了）。
 *
 * 截断时流仍然会正常结束并给出 Finish，所以不能靠"流结束了"推断参数收完了。比较前先去掉
 * 下划线/大小写差异：OpenAI 是 `length`，Claude 是 `max_tokens`，Google 是 `MAX_TOKENS`，
 * Responses API 是 `max_output_tokens` / `incomplete`。
 */
fun String?.isTruncatingFinishReason(): Boolean {
    val normalized = this?.filter(Char::isLetterOrDigit)?.lowercase() ?: return false
    return normalized == "length" ||
        normalized == "incomplete" ||
        normalized.contains("maxtoken") ||
        normalized.contains("maxoutputtoken")
}

@Serializable
sealed class UIMessagePart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        /**
         * 是否只是“需要鉴权才能取到内容”的 Provider 引用，尚未落地为本地/可公开访问的文件。
         *
         * 这类引用不能交给外部应用或图片加载器直接打开，也不应发给模型以外的下载器；
         * 判定见 [parseResponsesFileReference]。
         */
        val requiresAuthenticatedFetch: Boolean
            get() = parseResponsesFileReference(url) != null

        /** 还没落地内容的 Provider 引用不参与上传判定，避免把占位句柄当成附件发出去。 */
        fun isPendingProviderReference(): Boolean = requiresAuthenticatedFetch
    }

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override var metadata: JsonObject? = null,
        val reasoningType: ReasoningType = ReasoningType.REASONING_TEXT,
    ) : UIMessagePart()

    @Deprecated("Deprecated")
    @Serializable
    @SerialName("search")
    data object Search : UIMessagePart() {
        override var metadata: JsonObject? = null
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        fun merge(other: ToolCall): ToolCall {
            return ToolCall(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                arguments = arguments + other.arguments,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: JsonElement,
        val arguments: JsonElement,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    /**
     * 由 Provider 在服务端执行的工具调用，例如网页搜索、文件检索或代码执行。
     *
     * 与 [Tool] 不同，此类型只追踪调用及结果，不参与客户端审批或执行流程。[input] 和 [output]
     * 使用通用 JSON，以容纳不同 Provider、不同工具的协议结构；需要原样回传的 Provider 数据也应保留在其中。
     */
    @Serializable
    @SerialName("server_tool")
    data class ServerTool(
        val toolCallId: String,
        val toolName: String,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val status: ServerToolStatus,
        override var metadata: JsonObject? = null,
    ) : UIMessagePart() {
        val isFinished: Boolean
            get() = status == ServerToolStatus.COMPLETED || status == ServerToolStatus.FAILED

        /**
         * 该调用引出的文件产物（Responses `code_interpreter_call.outputs[].url` 等）。
         *
         * 只在 Provider 明确给了文件地址时存在；这些地址可能需要鉴权，App 侧按 [url] 自行校验后再落地，
         * 不在这里假设可公开访问。
         */
        fun fileOutputUrls(): List<String> = buildList {
            val outputs = (output as? kotlinx.serialization.json.JsonArray) ?: return@buildList
            outputs.forEach { item ->
                val obj = item as? kotlinx.serialization.json.JsonObject ?: return@forEach
                if (obj["type"]?.toString()?.trim('"') != "image") return@forEach
                val url = obj["url"]?.toString()?.trim('"') ?: return@forEach
                if (url.isNotBlank()) add(url)
            }
        }
    }

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<UIMessagePart> = emptyList(),
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null,
        // 本地执行的起止时间，只统计真正运行工具的耗时；拒绝、问答和旧消息都为空
        val startedAt: Instant? = null,
        val finishedAt: Instant? = null,
        /**
         * [input] 的完整度。旧记录没有这个字段，缺省 [ToolInputState.COMPLETE]：
         * 历史消息与非流式响应里的调用本来就是完整的，行为不变。
         */
        val inputState: ToolInputState = ToolInputState.COMPLETE,
    ) : UIMessagePart() {
        /** Whether the tool has been executed (has output) */
        val isExecuted: Boolean get() = output.isNotEmpty()

        /** 参数是否已经收完整（收到 Provider 的调用结束事件，或这本来就是完整响应） */
        val isInputComplete: Boolean get() = inputState.isComplete

        /** 是否只收到半截参数（还在流式接收，或流已断）——界面不能把它显示成可执行/待审批 */
        val hasIncompleteInput: Boolean get() = !isInputComplete

        /**
         * 参数能不能真的交给工具执行：状态完整**并且**原文能解析成 JSON。
         *
         * 旧记录（`inputState` 缺省为 COMPLETE 但原文是半截 JSON）在这里返回 false，调用方据此
         * 跳过执行，而不是把解析失败的参数当成 `{}` 跑一遍。
         */
        val isInputRunnable: Boolean get() = isInputComplete && parseInputOrNull() != null

        /** Whether the tool is pending user approval */
        val isPending: Boolean get() = !isExecuted && isInputComplete && approvalState is ToolApprovalState.Pending

        /**
         * Whether generation can resume and handle this tool immediately.
         *
         * 参数没收到完整的调用不能"恢复执行"：它的入参根本不存在，跑起来只会是拿残缺/空参数去调工具。
         */
        val canResumeExecution: Boolean
            get() = !isExecuted && isInputRunnable && approvalState.canResumeToolExecution()

        /**
         * Parse input string as JsonElement.
         *
         * 解析失败时返回空对象，**这个回退值只适合展示**：判断参数是否完整/能否执行请用
         * [parseInputOrNull] 或 [isInputRunnable]，否则半截 JSON 会被当成"没有参数"传给工具。
         */
        fun inputAsJson(): JsonElement = parseInputOrNull() ?: JsonObject(emptyMap())

        /** 参数原文能解析成 JSON 时返回它；半截 JSON 返回 null。空白入参等于无参调用，返回 `{}`。 */
        fun parseInputOrNull(): JsonElement? = runCatching {
            json.parseToJsonElement(input.ifBlank { "{}" })
        }.getOrNull()

        fun merge(other: Tool): Tool {
            return Tool(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                input = input + other.input,
                output = output + other.output,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
                startedAt = startedAt,
                finishedAt = finishedAt,
                // 任何一段没到完整状态，合并结果就不算完整：宁可停在"不可执行"，也不能拼出半截就当完整
                inputState = if (inputState.isComplete) other.inputState else inputState,
            )
        }
    }

    /**
     * 运行过程中要告诉模型的事件，例如后台命令结束。
     *
     * 由 agent 循环在轮到模型回复时追加到助手消息里，和其它 part 一样保存在会话中；
     * 请求时由 [expandNotices] 在所在位置拆成一条 user 消息，前缀固定，可以命中提示词缓存。
     * [title] 只用于界面展示，[text] 是发给模型的内容。
     */
    @Serializable
    @SerialName("notice")
    data class Notice(
        val title: String,
        val text: String,
        override var metadata: JsonObject? = null,
    ) : UIMessagePart()
}

/**
 * Provider 明确结束了这一个工具调用（`ToolCallEnd` / `content_block_stop`）时收尾参数状态。
 *
 * 结束时参数原文已经是终态：能解析成 JSON（或无参调用）就是 [ToolInputState.COMPLETE]，可以进入
 * 审批/执行；解析不出来说明拼出来的不是合法 JSON（中转缺事件、参数被截、Provider bug），
 * 标成 [ToolInputState.INTERRUPTED]，原文保留但永不执行。
 */
fun UIMessagePart.Tool.resolveInputAfterToolCallEnd(): UIMessagePart.Tool =
    if (inputState.isComplete) {
        this
    } else {
        copy(
            inputState = if (parseInputOrNull() != null) ToolInputState.COMPLETE else ToolInputState.INTERRUPTED
        )
    }

/**
 * 一次响应流结束时收尾**本步**还没显式结束的工具参数状态。
 *
 * 两种"流结束"要分开看：
 * - 正常结束（含 Provider 没发 `ToolCallEnd`、只到 EOF 就收流的实现）：参数解析得出来就算完整，
 *   合法无参工具也走这条，不能因为"没见到 End 事件"就一律不执行；
 * - 被截断的结束（[finishReason] 是 `length` / `max_tokens` / `MAX_TOKENS` 等）：即使半截参数
 *   恰好能解析（比如正好断在某个已闭合的子对象后面），也一律 [ToolInputState.INTERRUPTED]，
 *   按"完整参数"执行它会用默认值悄悄改掉用户的东西。
 */
fun UIMessagePart.Tool.resolveInputAfterStreamEnd(finishReason: String? = null): UIMessagePart.Tool {
    if (inputState.isComplete) return this
    val resolved = when {
        finishReason.isTruncatingFinishReason() -> ToolInputState.INTERRUPTED
        parseInputOrNull() != null -> ToolInputState.COMPLETE
        else -> ToolInputState.INTERRUPTED
    }
    return copy(inputState = resolved)
}

/**
 * 一次响应流**正常**结束时，收尾这条消息里从第 [from] 个 part 开始、还停在
 * [ToolInputState.STREAMING] 的工具参数。
 *
 * 只处理本步新增的 part：消息里之前步骤留下的调用（包括上一次中断留下的半截参数）不会被这次
 * 响应"顺手"改成完整状态——那等于替一条不完整的调用担保，恢复时就会被执行。
 */
fun UIMessage.resolveStreamToolInputs(from: Int, finishReason: String? = null): UIMessage {
    var changed = false
    val resolved = parts.mapIndexed { index, part ->
        if (index >= from && part is UIMessagePart.Tool && part.inputState == ToolInputState.STREAMING) {
            changed = true
            part.resolveInputAfterStreamEnd(finishReason)
        } else {
            part
        }
    }
    return if (changed) copy(parts = resolved) else this
}
