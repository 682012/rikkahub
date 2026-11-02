package me.rerere.ai.provider.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import kotlin.uuid.Uuid

/**
 * 网页聊天（纯文本框）场景下的提示词/响应适配层。
 *
 * 网页端没有官方 system / function calling 接口，所以这里只做两件事：
 * 1. 把完整的本地上下文（system / user / assistant / 已执行的工具结果）写成一份带明确 JSON 角色边界的文本，
 *    历史思考（[UIMessagePart.Reasoning]）一律不外传；
 * 2. 需要工具时，用一段**每次 exchange 都不一样**的 nonce 保护纯文本 JSON 信封，并严格校验模型回复。
 *
 * 这个类不执行任何工具、不申请审批、不改 [me.rerere.ai.agent.AgentLoop] 的执行边界：
 * 它只把一次完整且合法校验通过的回复翻译成 [StreamChunk] 或 [TextGenerationResult]，
 * 之后的真实执行、审批与串行/并行调度仍由现有 agent 流程负责。
 *
 * 协议不是 provider 原生能力，也不代表任何质量承诺：只是一种文本约定。
 *
 * 约束（违反即抛 [WebChatExchangeException]，绝不静默降级）：
 * - `toolsEnabled == false` 且 `params.tools` 非空：直接报错，不允许给模型宣称一个不可用的能力；
 * - 附件（image / audio / document / video）无法通过纯文本通道传递：明确报错，不静默丢弃；
 * - 回复必须是**单个** JSON 对象（不允许 markdown fence、外层引号、前后夹杂散文、重复字段、尾随内容）；
 * - protocol / nonce 必须匹配本次 exchange；工具名必须精确命中 `params.tools`；arguments 必须是 JSON 对象并满足输入 schema；
 * - 单次最多 [MAX_TOOL_CALLS] 个调用，整段回复最多 [MAX_RESPONSE_BYTES] 字节；
 * - 所有调用全部校验通过之后才会输出任何 tool chunk（all-or-nothing）。
 */
class WebChatExchange(
    private val messages: List<UIMessage>,
    private val params: TextGenerationParams,
    private val toolsEnabled: Boolean,
) {
    /**
     * nonce 来源，默认 128 bit 随机数（32 位十六进制）。
     * internal 只用于测试注入确定值；必须在读取 [prompt] 之前设置。
     */
    internal var nonceFactory: () -> String = { Uuid.random().toString() }

    private val exchangeId = Uuid.random().toString()
    private val activeTools: List<Tool> = params.tools
    private val toolsActive: Boolean = toolsEnabled && activeTools.isNotEmpty()

    private val plan: ExchangePlan by lazy { buildPlan() }

    init {
        if (!toolsEnabled && activeTools.isNotEmpty()) {
            throw WebChatExchangeException.ToolsDisabled(
                "toolsEnabled is false but ${activeTools.size} tool(s) were supplied; " +
                    "refusing to advertise tools that cannot be called through this exchange"
            )
        }
    }

    /** 完整提示词：协议说明（有工具时）+ 带 JSON 角色边界的对话历史。 */
    val prompt: String
        get() = plan.prompt

    /**
     * 校验模型的整段回复并转成流式 chunk。
     *
     * 没有任何一个 chunk 会在校验完成前产出：回复不合法时抛 [WebChatExchangeException]。
     */
    fun complete(text: String, responseId: String? = null): List<StreamChunk> {
        val reply = validateReply(text)
        return buildChunks(reply, responseId)
    }

    /** 校验模型的整段回复并组装成 assistant 消息；工具调用的 output 留空，等真实执行填充。 */
    fun result(text: String, responseId: String? = null): TextGenerationResult {
        val reply = validateReply(text)
        return buildResult(reply, responseId)
    }

    // ---------------------------------------------------------------- prompt

    private fun buildPlan(): ExchangePlan {
        val conversation = buildConversation()
        if (!toolsActive) {
            // 纯文本：不需要任何协议包装，只给角色边界。
            val prompt = buildString {
                append(PLAIN_HEADER)
                append("\n\n# Conversation\n")
                append(conversation)
                append('\n')
            }
            return ExchangePlan(prompt = prompt, nonce = null, toolsByName = emptyMap())
        }

        val nonce = generateNonce()
        val toolCatalog = activeTools.joinToString(separator = "\n\n") { tool ->
            buildString {
                append("## ").append(tool.name).append('\n')
                append(tool.description.trim()).append('\n')
                append("input schema: ")
                append(schemaText(tool))
            }
        }
        val prompt = buildString {
            append(TOOLS_HEADER)
            append("\n# Allowed tools (").append(activeTools.size).append(")\n")
            append(toolCatalog)
            append("\n\n# Conversation\n")
            append(conversation)
            append('\n')
            append("\n\n# Reply format\n")
            append("Reply with exactly one JSON object and nothing else: no prose before or after it, ")
            append("no markdown code fence, no backticks, no quotes around the object.\n")
            append("{\"protocol\":\"").append(PROTOCOL_ID)
            append("\",\"nonce\":\"").append(nonce)
            append("\",\"text\":\"<message for the user>\",")
            append("\"tool_calls\":[{\"name\":\"<tool name>\",\"arguments\":{}}]}\n")
            append("\nRules:\n")
            append("- Use exactly these fields. \"protocol\" and \"nonce\" must be copied character for character; ")
            append("a reply with a missing or different nonce is rejected.\n")
            append("- \"text\" is the only part the user sees; put all wording there (it may be empty when you only call tools).\n")
            append("- \"tool_calls\" may be omitted or empty when no tool is needed.\n")
            append("- Do not invent ids and do not add extra fields; the app assigns call ids itself.\n")
            append("- Call only the tools listed below, at most ").append(MAX_TOOL_CALLS).append(" calls per reply. ")
            append("\"arguments\" must be a JSON object with only declared properties and must satisfy all required fields.\n")
            append("- Nothing runs until the whole reply has been validated; an invalid reply is discarded entirely.\n")
            append("- Tool results come back in the next conversation turn as tool_result entries, in call order.\n")

        }
        return ExchangePlan(
            prompt = prompt,
            nonce = nonce,
            toolsByName = activeTools.associateBy { it.name },
        )
    }

    private fun schemaText(tool: Tool): String {
        val schema = tool.parameters() ?: InputSchema.Obj(properties = JsonObject(emptyMap()))
        return json.encodeToString(
            JsonElement.serializer(),
            json.encodeToJsonElement(schema),
        )
    }

    /**
     * 对话历史压成一条 JSON 数组，每个元素自带 role 边界。
     * 角色只有 system / user / assistant / tool_result 四种；已执行的工具结果按调用顺序跟随在请求它的 assistant 之后。
     */
    private fun buildConversation(): String {
        val entries = mutableListOf<JsonObject>()
        messages.forEach { message ->
            val textBlocks = mutableListOf<String>()
            val toolCalls = mutableListOf<JsonObject>()
            val toolResults = mutableListOf<JsonObject>()

            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> textBlocks += part.text
                    // 历史思考不传，避免把推理过程泄露给网页端
                    is UIMessagePart.Reasoning -> Unit
                    is UIMessagePart.Notice -> textBlocks += "[${part.title}] ${part.text}"
                    is UIMessagePart.Image,
                    is UIMessagePart.Video,
                    is UIMessagePart.Audio,
                    is UIMessagePart.Document,
                    -> throw unsupportedAttachment(message, part)

                    is UIMessagePart.Tool -> {
                        toolCalls += buildJsonObject {
                            put("name", part.toolName)
                            put("arguments", argumentsElement(part.input))
                        }
                        toolResults += buildJsonObject {
                            put("role", "tool_result")
                            put("name", part.toolName)
                            if (part.isExecuted) {
                                put("content", toolOutputText(part))
                            } else {
                                // 还没执行（等待审批/中断）：显式标注，不伪装成空结果
                                put("content", "")
                                put("status", "not_executed")
                            }
                        }
                    }

                    is UIMessagePart.ToolCall -> toolCalls += buildJsonObject {
                        put("name", part.toolName)
                        put("arguments", argumentsElement(part.arguments))
                    }

                    is UIMessagePart.ToolResult -> toolResults += buildJsonObject {
                        put("role", "tool_result")
                        put("name", part.toolName)
                        put("content", elementAsText(part.content))
                    }

                    is UIMessagePart.ServerTool -> toolResults += buildJsonObject {
                        put("role", "tool_result")
                        put("name", part.toolName)
                        put("server_tool", true)
                        put("content", elementAsText(part.output))
                    }

                    // 仅界面标记，本身不携带任何内容
                    is UIMessagePart.Search -> Unit
                }
            }

            val content = textBlocks.joinToString(separator = "\n")
            when (message.role) {
                MessageRole.SYSTEM,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                -> if (content.isNotBlank() || toolCalls.isNotEmpty()) {
                    entries += buildJsonObject {
                        put("role", message.role.name.lowercase())
                        put("content", content)
                        if (toolCalls.isNotEmpty()) {
                            put("tool_calls", JsonArray(toolCalls))
                        }
                    }
                }

                MessageRole.TOOL -> {
                    entries += buildJsonObject {
                        put("role", "tool_result")
                        put("name", "")
                        put("content", content)
                    }
                }
            }
            entries += toolResults
        }
        return json.encodeToString(JsonElement.serializer(), JsonArray(entries))
    }

    private fun toolOutputText(part: UIMessagePart.Tool): String {
        val blocks = part.output.mapNotNull { output ->
            when (output) {
                is UIMessagePart.Text -> output.text
                is UIMessagePart.Notice -> "[${output.title}] ${output.text}"
                is UIMessagePart.Reasoning -> null
                is UIMessagePart.Image,
                is UIMessagePart.Video,
                is UIMessagePart.Audio,
                is UIMessagePart.Document,
                -> throw WebChatExchangeException.UnsupportedContent(
                    "tool result of \"${part.toolName}\" contains a ${partKind(output)}, " +
                        "which the web chat text protocol cannot carry"
                )

                else -> null
            }
        }
        return blocks.joinToString(separator = "\n").ifBlank { "(no text output)" }
    }

    private fun argumentsElement(raw: String): JsonElement {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return runCatching { StrictJson.parse(trimmed) }.getOrElse { JsonPrimitive(raw) }
    }

    private fun elementAsText(element: JsonElement?): String = when {
        element == null || element is JsonNull -> ""
        element is JsonPrimitive && element.isString -> element.content
        else -> json.encodeToString(JsonElement.serializer(), element)
    }

    private fun unsupportedAttachment(
        message: UIMessage,
        part: UIMessagePart,
    ): WebChatExchangeException.UnsupportedContent = WebChatExchangeException.UnsupportedContent(
        "web chat text protocol cannot carry a ${partKind(part)} in a " +
            "${message.role.name.lowercase()} message; only text can be sent"
    )

    private fun partKind(part: UIMessagePart): String = when (part) {
        is UIMessagePart.Image -> "image attachment"
        is UIMessagePart.Video -> "video attachment"
        is UIMessagePart.Audio -> "audio attachment"
        is UIMessagePart.Document -> "document attachment (${part.fileName})"
        else -> "non-text part"
    }

    private fun generateNonce(): String {
        val normalized = nonceFactory().trim().lowercase().replace("-", "")
        if (!NONCE_PATTERN.matches(normalized)) {
            error("nonceFactory must return 128 random bits as 32 hex characters")
        }
        return normalized
    }

    // ------------------------------------------------------------- validation

    private fun validateReply(text: String): ParsedReply {

        if (text.isBlank()) {
            throw WebChatExchangeException.ProtocolViolation("reply is empty; expected the tool protocol envelope")
        }
        // 先按字符数快速排除，再按 UTF-8 字节数精确判断
        if (text.length > MAX_RESPONSE_BYTES || utf8Length(text) > MAX_RESPONSE_BYTES) {
            throw WebChatExchangeException.ProtocolViolation(
                "reply is larger than the $MAX_RESPONSE_BYTES byte limit; return less text or fewer tool calls"
            )
        }

        val current = plan
        val nonce = current.nonce
            // 纯文本模式没有任何协议，原样当文本
            ?: return ParsedReply(text = text, calls = emptyList(), finishReason = FINISH_STOP)


        // 整段回复必须就是一个 JSON 值：fence / 外层引号 / 前后散文 / 重复字段 / 尾随内容都在这里被拒
        val element = StrictJson.parse(text)
        val envelope = element as? JsonObject
            ?: throw WebChatExchangeException.ProtocolViolation(
                "reply must be a single JSON object, got ${element::class.simpleName}"
            )

        envelope.keys.firstOrNull { it !in ENVELOPE_FIELDS }?.let { unknown ->
            throw WebChatExchangeException.ProtocolViolation("reply has unexpected field \"$unknown\"")
        }

        val protocol = stringField(envelope, "protocol")
            ?: throw WebChatExchangeException.ProtocolViolation("reply is missing string field \"protocol\"")
        if (protocol != PROTOCOL_ID) {
            throw WebChatExchangeException.ProtocolViolation(
                "reply protocol is \"$protocol\", expected \"$PROTOCOL_ID\""
            )
        }

        val replyNonce = stringField(envelope, "nonce")
            ?: throw WebChatExchangeException.ProtocolViolation("reply is missing string field \"nonce\"")
        if (replyNonce != nonce) {
            throw WebChatExchangeException.ProtocolViolation(
                "reply nonce does not match this exchange; the reply was not produced for this request"
            )
        }

        val replyText = when (val field = envelope["text"]) {
            null, is JsonNull -> ""
            is JsonPrimitive -> if (field.isString) field.content else {
                throw WebChatExchangeException.ProtocolViolation("reply field \"text\" must be a string")
            }

            else -> throw WebChatExchangeException.ProtocolViolation("reply field \"text\" must be a string")
        }

        val rawCalls = when (val field = envelope["tool_calls"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> field.toList()
            else -> throw WebChatExchangeException.ProtocolViolation("reply field \"tool_calls\" must be an array")
        }
        if (rawCalls.size > MAX_TOOL_CALLS) {
            throw WebChatExchangeException.ProtocolViolation(
                "reply requests ${rawCalls.size} tool calls, limit is $MAX_TOOL_CALLS"
            )
        }

        // 全部校验完才返回；任何一条不合法都直接抛出，调用方永远拿不到部分结果
        val calls = rawCalls.mapIndexed { index, item -> validateCall(index, item, current.toolsByName) }
        return ParsedReply(
            text = replyText,
            calls = calls,
            finishReason = if (calls.isEmpty()) FINISH_STOP else FINISH_TOOL_CALLS,
        )
    }

    private fun stringField(envelope: JsonObject, name: String): String? {
        val value = envelope[name] ?: return null
        if (value !is JsonPrimitive || !value.isString) {
            throw WebChatExchangeException.ProtocolViolation("reply field \"$name\" must be a string")
        }
        return value.content
    }

    private fun validateCall(
        index: Int,
        element: JsonElement,
        toolsByName: Map<String, Tool>,
    ): ValidatedCall {
        val label = "tool_calls[$index]"
        val call = element as? JsonObject
            ?: throw WebChatExchangeException.ProtocolViolation("$label must be a JSON object")
        // 不接受模型自造 id 或任何多余字段
        call.keys.firstOrNull { it != "name" && it != "arguments" }?.let { unknown ->
            throw WebChatExchangeException.ProtocolViolation("$label has unsupported field \"$unknown\"")
        }
        val name = (call["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw WebChatExchangeException.ProtocolViolation("$label.name must be a string")
        val tool = toolsByName[name]
            ?: throw WebChatExchangeException.ProtocolViolation("$label.name \"$name\" is not an allowed tool")
        val argumentsElement = call["arguments"]
            ?: throw WebChatExchangeException.ProtocolViolation("$label is missing \"arguments\"")
        val arguments = argumentsElement as? JsonObject
            ?: throw WebChatExchangeException.ProtocolViolation("$label.arguments must be a JSON object")
        tool.parameters()?.let { schema ->
            SchemaValidator.validate(schema, arguments, label)?.let {
                throw WebChatExchangeException.ProtocolViolation(it)
            }
        }
        return ValidatedCall(name = name, arguments = arguments)
    }

    private fun utf8Length(text: String): Int {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            bytes += when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            index += if (codePoint >= 0x10000) 2 else 1
        }
        return bytes
    }

    // ---------------------------------------------------------------- output

    private fun buildChunks(reply: ParsedReply, responseId: String?): List<StreamChunk> = buildList<StreamChunk> {
        if (reply.text.isNotEmpty()) {
            val textId = "$exchangeId-text"
            add(StreamChunk.TextStart(textId))
            add(StreamChunk.TextDelta(id = textId, text = reply.text))
            add(StreamChunk.TextEnd(textId))
        }
        reply.calls.forEachIndexed { index, call ->
            // 调用 id 由客户端分配，不采用模型给出的任何标识
            val callId = "$exchangeId-tool-$index"
            add(StreamChunk.ToolCallStart(id = callId, toolName = call.name))
            add(
                StreamChunk.ToolCallDelta(
                    id = callId,
                    inputDelta = json.encodeToString(JsonElement.serializer(), call.arguments),
                )
            )
            add(StreamChunk.ToolCallEnd(callId))
        }
        add(
            StreamChunk.Finish(
                finishReason = reply.finishReason,
                responseId = responseId,
                model = params.model.modelId.ifBlank { null },
            )
        )
    }

    private fun buildResult(reply: ParsedReply, responseId: String?): TextGenerationResult {
        val parts = buildList<UIMessagePart> {
            if (reply.text.isNotEmpty()) add(UIMessagePart.Text(reply.text))
            reply.calls.forEachIndexed { index, call ->
                add(
                    UIMessagePart.Tool(
                        toolCallId = "$exchangeId-tool-$index",
                        toolName = call.name,
                        input = json.encodeToString(JsonElement.serializer(), call.arguments),
                        // 还没执行，输出留空
                        output = emptyList(),
                    )
                )
            }
        }
        return TextGenerationResult(
            id = responseId ?: exchangeId,
            model = params.model.modelId,
            message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
            finishReason = reply.finishReason,
        )
    }

    private class ExchangePlan(
        val prompt: String,
        val nonce: String?,
        val toolsByName: Map<String, Tool>,
    )

    private class ParsedReply(
        val text: String,
        val calls: List<ValidatedCall>,
        val finishReason: String,
    )

    private class ValidatedCall(
        val name: String,
        val arguments: JsonObject,
    )

    companion object {
        const val PROTOCOL_ID = "rikkahub-tools-v1"
        const val MAX_TOOL_CALLS = 16
        const val MAX_RESPONSE_BYTES = 1024 * 1024

        private const val FINISH_STOP = "stop"
        private const val FINISH_TOOL_CALLS = "tool_calls"

        private val NONCE_PATTERN = Regex("^[0-9a-f]{32}$")
        private val ENVELOPE_FIELDS = setOf("protocol", "nonce", "text", "tool_calls")

        private val PLAIN_HEADER = """
            |You are replying through a plain text web chat box.
            |There is no official system prompt API on this side: the conversation below is a single text block whose
            |JSON objects carry the role boundaries (system / user / assistant / tool_result). Read them in order and
            |treat them as the conversation history.
            |Reply with the assistant message only: no role prefix, no JSON wrapper, no markdown fence, no tool syntax.
        """.trimMargin().trim()

        private val TOOLS_HEADER = """
            |You are replying through a plain text web chat box.
            |There is no native function calling API here and none is claimed. Tools are requested through the plain
            |text JSON protocol below; it is a text convention, not a provider feature, and it promises nothing beyond
            |what is written here.
        """.trimMargin().trim()
    }
}

sealed class WebChatExchangeException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {

    /** 上下文里有纯文本通道承载不了的内容（图片/音频/文档/视频）。 */
    class UnsupportedContent(message: String) : WebChatExchangeException(message)

    /** 关闭了工具却仍传入了工具。 */
    class ToolsDisabled(message: String) : WebChatExchangeException(message)

    /** 模型回复不符合文本协议，或工具调用未通过校验。 */
    class ProtocolViolation(message: String) : WebChatExchangeException(message)
}

/**
 * 严格 JSON 解析：整段必须是单个合法 JSON 值，不允许重复字段、尾随内容、markdown fence 或外层引号。
 *
 * 项目里的 `Json.parseToJsonElement` 不会因为重复字段报错（后一个值直接覆盖），也不检查尾随内容，
 * 所以这里先做一遍 token 层的语法扫描，再交给 kotlinx 出树。
 */
private object StrictJson {
    private const val MAX_DEPTH = 64

    fun parse(text: String): JsonElement {
        StrictJsonScanner(text).scanDocument()
        return try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw WebChatExchangeException.ProtocolViolation(
                "reply could not be parsed as JSON: ${e.message ?: e::class.simpleName}"
            )
        }
    }

    private class StrictJsonScanner(private val src: String) {
        private var pos = 0

        fun scanDocument() {
            skipWhitespace()
            if (pos >= src.length) fail("reply is empty; expected a JSON object")
            scanValue(0)
            skipWhitespace()
            if (pos < src.length) {
                fail("unexpected content after the JSON value at offset $pos")
            }
        }

        private fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun scanValue(depth: Int) {
            if (depth > MAX_DEPTH) fail("JSON nesting deeper than $MAX_DEPTH levels")
            val c = peek() ?: fail("unexpected end of reply")
            when {
                c == '{' -> scanObject(depth)
                c == '[' -> scanArray(depth)
                c == '"' -> scanString()
                c == 't' -> expectLiteral("true")
                c == 'f' -> expectLiteral("false")
                c == 'n' -> expectLiteral("null")
                c == '-' || c in '0'..'9' -> scanNumber()
                else -> fail("unexpected character '$c' at offset $pos")
            }
        }

        private fun scanObject(depth: Int) {
            pos++ // {
            val keys = HashSet<String>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("object key must be a string at offset $pos")
                val key = scanString()
                if (!keys.add(key)) fail("duplicate object key \"$key\"")
                skipWhitespace()
                if (peek() != ':') fail("expected ':' after object key \"$key\"")
                pos++
                skipWhitespace()
                scanValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return
                    }

                    else -> fail("expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun scanArray(depth: Int) {
            pos++ // [
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return
            }
            while (true) {
                skipWhitespace()
                scanValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return
                    }

                    else -> fail("expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun scanString(): String {
            pos++ // 开引号
            val builder = StringBuilder()
            while (true) {
                if (pos >= src.length) fail("unterminated string literal")
                when (val c = src[pos]) {
                    '"' -> {
                        pos++
                        return builder.toString()
                    }

                    '\\' -> {
                        pos++
                        if (pos >= src.length) fail("unterminated escape sequence")
                        when (val escape = src[pos]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (pos + 4 >= src.length) fail("truncated \\u escape")
                                val hex = src.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16) ?: fail("invalid \\u escape \"$hex\"")
                                builder.append(code.toChar())
                                pos += 4
                            }

                            else -> fail("invalid escape sequence \\$escape")
                        }
                        pos++
                    }

                    else -> {
                        if (c < ' ') fail("unescaped control character in string literal")
                        builder.append(c)
                        pos++
                    }
                }
            }
        }

        private fun scanNumber() {
            if (peek() == '-') pos++
            val first = peek() ?: fail("invalid number literal")
            when {
                first == '0' -> pos++
                first in '1'..'9' -> {
                    pos++
                    while (peek()?.let { it in '0'..'9' } == true) pos++
                }

                else -> fail("invalid number literal at offset $pos")
            }
            if (peek() == '.') {
                pos++
                if (peek()?.let { it in '0'..'9' } != true) fail("invalid number literal at offset $pos")
                while (peek()?.let { it in '0'..'9' } == true) pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (peek()?.let { it in '0'..'9' } != true) fail("invalid number literal at offset $pos")
                while (peek()?.let { it in '0'..'9' } == true) pos++
            }
        }

        private fun expectLiteral(literal: String) {
            if (!src.startsWith(literal, pos)) fail("invalid literal at offset $pos")
            pos += literal.length
        }

        private fun peek(): Char? = if (pos < src.length) src[pos] else null

        private fun fail(message: String): Nothing =
            throw WebChatExchangeException.ProtocolViolation("invalid tool protocol reply: $message")
    }
}

/**
 * 校验 tool arguments 是否满足项目 [InputSchema.Obj] 声明的结构。
 *
 * 支持 JSON Schema 子集：type（含联合类型）、enum、number/integer 的 minimum/maximum/exclusiveMinimum/exclusiveMaximum、
 * string 的 minLength/maxLength、array 的 items/minItems/maxItems、object 的 properties/required/additionalProperties。
 * 不支持的约束拒绝调用，不默默跳过；根参数仅接受已声明属性。
 */
private object SchemaValidator {

    fun validate(schema: InputSchema, value: JsonObject, path: String): String? = when (schema) {
        is InputSchema.Obj -> validateObject(schema, value, path)
    }

    private fun validateObject(schema: InputSchema.Obj, value: JsonObject, path: String): String? {
        schema.required?.forEach { required ->
            if (!value.containsKey(required)) {
                return "$path is missing required property \"$required\""
            }
        }
        if (value.keys.any { it !in schema.properties }) return "$path has undeclared arguments"
        schema.properties.forEach { (key, propertySchema) ->
            val propertyValue = value[key] ?: return@forEach
            validateElement(propertySchema, propertyValue, "$path.$key")?.let { return it }
        }
        return null
    }

    private fun validateElement(schemaElement: JsonElement, value: JsonElement, path: String): String? {
        if (schemaElement is JsonPrimitive && !schemaElement.isString) {
            return if (schemaElement.content == "true") null else "$path is rejected by its schema"
        }
        val schema = schemaElement as? JsonObject ?: return "$path has an unsupported schema"
        val unsupported = setOf("\$ref", "pattern", "format", "multipleOf", "uniqueItems", "dependentRequired", "if", "contains", "propertyNames", "patternProperties")
        if (schema.keys.any { it in unsupported }) return "$path uses a constraint unsupported by this experimental bridge"
        for (keyword in listOf("anyOf", "oneOf", "allOf")) {
            val branches = schema[keyword] ?: continue
            if (branches !is JsonArray || branches.isEmpty()) return "$path has invalid $keyword"
            val matched = branches.count { validateElement(it, value, path) == null }
            if ((keyword == "anyOf" && matched == 0) || (keyword == "oneOf" && matched != 1) ||
                (keyword == "allOf" && matched != branches.size)) return "$path does not satisfy $keyword"
        }
        schema["not"]?.let { if (validateElement(it, value, path) == null) return "$path violates not" }
        schema["const"]?.let { if (value != it) return "$path must match const" }

        val types = when (val type = schema["type"]) {
            null -> null
            is JsonPrimitive -> listOf(type.content)
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> null
        }
        if (types != null && types.isNotEmpty() && types.none { matchesType(it, value) }) {
            return "$path must be of type ${types.joinToString(" or ")}"
        }

        (schema["enum"] as? JsonArray)?.let { options ->
            if (options.none { it == value }) {
                return "$path must be one of the declared enum values"
            }
        }

        return when {
            value is JsonObject -> validateNestedObject(schema, value, path)
            value is JsonArray -> validateArray(schema, value, path)
            value is JsonPrimitive && value.isString -> validateString(schema, value.content, path)
            value is JsonPrimitive && value !is JsonNull && value.content !in setOf("true", "false") -> validateNumber(schema, value.content, path)
            else -> null
        }
    }

    private fun validateNestedObject(schema: JsonObject, value: JsonObject, path: String): String? {
        (schema["required"] as? JsonArray)?.forEach { required ->
            val key = (required as? JsonPrimitive)?.content ?: return@forEach
            if (!value.containsKey(key)) {
                return "$path is missing required property \"$key\""
            }
        }
        val properties = schema["properties"] as? JsonObject
        properties?.forEach { (key, propertySchema) ->
            val propertyValue = value[key] ?: return@forEach
            validateElement(propertySchema, propertyValue, "$path.$key")?.let { return it }
        }
        when (val additional = schema["additionalProperties"]) {
            null, is JsonNull -> Unit
            is JsonObject -> value.forEach { (key, extraValue) ->
                if (properties?.containsKey(key) != true) {
                    validateElement(additional, extraValue, "$path.$key")?.let { return it }
                }
            }

            is JsonPrimitive -> if (!additional.isString && additional.content == "false") {
                val known = properties?.keys ?: emptySet()
                value.keys.firstOrNull { it !in known }?.let {
                    return "$path has unexpected property \"$it\"; additionalProperties is false"
                }
            }

            else -> Unit
        }
        return null
    }

    private fun validateArray(schema: JsonObject, value: JsonArray, path: String): String? {
        numericValue(schema["minItems"])?.let { minimum ->
            if (value.size < minimum) return "$path needs at least ${minimum.toInt()} items"
        }
        numericValue(schema["maxItems"])?.let { maximum ->
            if (value.size > maximum) return "$path allows at most ${maximum.toInt()} items"
        }
        when (val items = schema["items"]) {
            is JsonObject -> value.forEachIndexed { index, item ->
                validateElement(items, item, "$path[$index]")?.let { return it }
            }

            is JsonArray -> value.forEachIndexed { index, item ->
                items.getOrNull(index)?.let { itemSchema ->
                    validateElement(itemSchema, item, "$path[$index]")?.let { return it }
                }
            }

            else -> Unit
        }
        return null
    }

    private fun validateString(schema: JsonObject, value: String, path: String): String? {
        val length = value.codePointCount(0, value.length)
        numericValue(schema["minLength"])?.let { minimum ->
            if (length < minimum) return "$path needs at least ${minimum.toInt()} characters"
        }
        numericValue(schema["maxLength"])?.let { maximum ->
            if (length > maximum) return "$path allows at most ${maximum.toInt()} characters"
        }
        return null
    }

    private fun validateNumber(schema: JsonObject, raw: String, path: String): String? {
        val number = raw.toDoubleOrNull() ?: return "$path must be a number"
        numericValue(schema["minimum"])?.let { if (number < it) return "$path must be >= $it" }
        numericValue(schema["maximum"])?.let { if (number > it) return "$path must be <= $it" }
        numericValue(schema["exclusiveMinimum"])?.let { if (number <= it) return "$path must be > $it" }
        numericValue(schema["exclusiveMaximum"])?.let { if (number >= it) return "$path must be < $it" }
        return null
    }

    private fun matchesType(type: String, value: JsonElement): Boolean = when (type) {
        "string" -> value is JsonPrimitive && value.isString
        "number" -> numericContent(value) != null
        "integer" -> numericContent(value)?.let { it % 1.0 == 0.0 } == true
        "boolean" -> numericContent(value) == null &&
            value is JsonPrimitive && value !is JsonNull && !value.isString &&
            (value.content == "true" || value.content == "false")

        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value is JsonNull
        else -> false
    }

    private fun numericContent(value: JsonElement): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        return primitive.content.toDoubleOrNull()
    }

    private fun numericValue(element: JsonElement?): Double? {
        if (element == null) return null
        return numericContent(element)
    }
}
