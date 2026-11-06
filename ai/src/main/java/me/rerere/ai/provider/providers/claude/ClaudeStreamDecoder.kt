package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail

internal class ClaudeStreamDecoder : StreamChunkDecoder {
    private val blocks = mutableMapOf<Int, ClaudeStreamBlock>()
    private var responseId: String? = null
    private var responseModel: String? = null
    private var finishReason: String? = null
    private var finished = false

    /**
     * 本次响应的 usage 累计快照：只被显式上报的字段覆盖。
     *
     * 每个解码器实例只服务一次请求，实例初建时为空，不会带上一次请求的数字。
     */
    private var usageSnapshot: ClaudeUsageSnapshot? = null

    /**
     * 是否已收到协议终态：`[DONE]`、`message_stop`，或明确的非空 `stop_reason`。
     *
     * 只用于 [onClosed] 判断正常收尾还是断流；收到终态后不提前 finish，
     * 让 `message_stop` 之前/之后的 usage 帧照常解析。
     */
    private var terminalReceived = false

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") {
            terminalReceived = true
            return DecodeResult(finish(), completed = true)
        }

        val dataJson = json.parseToJsonElement(event.data).jsonObject
        if (event.event == "error") {
            throw (dataJson["error"] ?: dataJson).parseErrorDetail()
        }

        dataJson["message"]?.jsonObject?.let { message ->
            responseId = message["id"]?.jsonPrimitive?.contentOrNull ?: responseId
            responseModel = message["model"]?.jsonPrimitive?.contentOrNull ?: responseModel
            // 非流式的 message 也会带 stop_reason，同样算明确的协议终态
            message["stop_reason"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                if (finishReason == null) finishReason = it
                terminalReceived = true
            }
        }
        dataJson["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
            finishReason = it
            terminalReceived = true
        }

        val chunks = buildList {
            parseTokenUsage(dataJson)?.let { add(StreamChunk.Usage(it, completeSnapshot = true)) }
            val index = dataJson["index"]?.jsonPrimitive?.intOrNull
            val contentBlock = dataJson["content_block"]?.jsonObject

            if (event.event == "content_block_start" && index != null && contentBlock != null) {
                val kind = contentBlock["type"]?.jsonPrimitive?.contentOrNull ?: ""
                val blockId = contentBlock["id"]?.jsonPrimitive?.contentOrNull
                    ?: contentBlock["tool_use_id"]?.jsonPrimitive?.contentOrNull
                    ?: "${responseId ?: event.id ?: "response"}:block-$index"
                val metadata = when (kind) {
                    "thinking" -> contentBlock["signature"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                        ClaudeReasoningMetadata(signature = it).toMetadata()
                    }
                    else -> null
                }
                blocks[index] = ClaudeStreamBlock(kind, blockId, metadata)
                when (kind) {
                    "text" -> add(StreamChunk.TextStart(blockId))
                    "thinking", "redacted_thinking" -> add(StreamChunk.ReasoningStart(blockId, metadata))
                    "tool_use" -> {
                        add(StreamChunk.ToolCallStart(
                            id = blockId,
                            toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        ))
                        val input = contentBlock["input"]?.jsonObject
                        if (input != null && input.isNotEmpty()) {
                            add(StreamChunk.ToolCallDelta(
                                id = blockId,
                                inputDelta = json.encodeToString(input),
                            ))
                        }
                    }
                    else -> if (kind.isClaudeServerToolUseType()) {
                        add(StreamChunk.ServerToolStart(
                            id = blockId,
                            toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            input = contentBlock["input"],
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                call = contentBlock,
                                callIndex = index,
                            ).toMetadata(),
                        ))
                    } else if (kind.isClaudeServerToolResultType()) {
                        val output = contentBlock["content"]
                        add(StreamChunk.ServerToolEnd(
                            id = blockId,
                            output = output,
                            status = if (output.isClaudeServerToolError()) {
                                ServerToolStatus.FAILED
                            } else {
                                ServerToolStatus.COMPLETED
                            },
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                result = contentBlock,
                                resultIndex = index,
                            ).toMetadata(),
                        ))
                    }
                }
            }

            if (event.event == "content_block_delta" && index != null) {
                val block = blocks[index] ?: error("Unknown content block index: $index")
                val delta = dataJson["delta"]?.jsonObject ?: JsonObject(emptyMap())
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> add(StreamChunk.TextDelta(
                        block.id,
                        delta["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    ))
                    "thinking_delta" -> add(StreamChunk.ReasoningDelta(
                        block.id,
                        delta["thinking"]?.jsonPrimitive?.contentOrNull ?: "",
                        block.metadata,
                    ))
                    "signature_delta" -> {
                        val metadata = delta["signature"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                            ClaudeReasoningMetadata(signature = it).toMetadata()
                        }
                        blocks[index] = block.copy(metadata = metadata ?: block.metadata)
                        add(StreamChunk.ReasoningDelta(block.id, "", metadata))
                    }
                    "input_json_delta" -> {
                        val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (block.kind.isClaudeServerToolUseType()) {
                            add(StreamChunk.ServerToolInputDelta(block.id, partialJson))
                        } else {
                            add(StreamChunk.ToolCallDelta(id = block.id, inputDelta = partialJson))
                        }
                    }
                }
            }

            if (event.event == "content_block_stop" && index != null) {
                val block = blocks.remove(index) ?: error("Unknown content block index: $index")
                endBlock(block)?.let(::add)
            }
        }

        return if (event.event == "message_stop") {
            terminalReceived = true
            DecodeResult(chunks + finish(), completed = true)
        } else {
            DecodeResult(chunks)
        }
    }

    override fun onClosed(): List<StreamChunk> {
        if (finished) return emptyList()
        if (!terminalReceived) {
            throw StreamTruncatedException(
                "Claude 流在 message_stop/stop_reason 之前关闭，回复可能被截断",
            )
        }
        return finish()
    }

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return buildList {
            blocks.values.mapNotNull(::endBlock).forEach(::add)
            blocks.clear()
            add(StreamChunk.Finish(finishReason, responseId, responseModel))
        }
    }

    private fun endBlock(block: ClaudeStreamBlock): StreamChunk? = when (block.kind) {
        "text" -> StreamChunk.TextEnd(block.id)
        "thinking", "redacted_thinking" -> StreamChunk.ReasoningEnd(block.id, block.metadata)
        "tool_use" -> StreamChunk.ToolCallEnd(block.id)
        else -> if (block.kind.isClaudeServerToolUseType()) {
            StreamChunk.ServerToolInputEnd(block.id)
        } else {
            null
        }
    }

    /**
     * 解析 usage 并返回**累计快照**。
     *
     * Anthropic 的 usage 分散在两帧里：`message_start` 的 `message.usage` 带输入侧字段
     * （`input_tokens` / `cache_read_input_tokens` / `cache_creation_input_tokens`），
     * `message_delta` 的 `usage` 往往只有累计的 `output_tokens`。把"本帧没带这个字段"当成 0
     * 会让后面的帧把前面已经确认的输入侧数字清掉：prompt 从 1200 掉成 100，而下游
     * [TokenUsage.merge] 又保留旧的 cached(900)，于是缓存命中率算出 900%。
     *
     * 因此解码器按字段维护快照：只覆盖本帧**显式出现**的字段（显式上报的 0 是真实值，要覆盖），
     * 未出现的字段沿用已确认的值。每次 emit 都是完整快照，缺字段不等于 0。
     *
     * 完全没有 usage 对象的帧不发空快照，避免把"没数"写成"0 用量"。
     */
    private fun parseTokenUsage(bodyJson: JsonObject): TokenUsage? {
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson.explicitInt("input_tokens")
        val cacheReadTokens = usageJson.explicitInt("cache_read_input_tokens")
        val cacheCreationTokens = usageJson.explicitInt("cache_creation_input_tokens")
        val outputTokens = usageJson.explicitInt("output_tokens")
        // 一个已知字段都没有的 usage 对象（例如只有 server_tool_use 之类扩展字段）
        // 不构成新的用量事实，不能覆盖成空快照。
        if (inputTokens == null && cacheReadTokens == null &&
            cacheCreationTokens == null && outputTokens == null
        ) {
            return null
        }

        val previous = usageSnapshot
        val snapshot = ClaudeUsageSnapshot(
            inputTokens = inputTokens ?: previous?.inputTokens,
            cacheReadTokens = cacheReadTokens ?: previous?.cacheReadTokens,
            cacheCreationTokens = cacheCreationTokens ?: previous?.cacheCreationTokens,
            outputTokens = outputTokens ?: previous?.outputTokens,
        )
        usageSnapshot = snapshot

        // cache_creation 是本次新写入缓存的输入 token，按输入计费，绝不能算进 cachedTokens
        // （cachedTokens 只表示真正命中的 cache_read）。
        val promptTokens = snapshot.promptTokens
        val completionTokens = snapshot.completionTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = snapshot.cacheReadTokens ?: 0,
            cachedReported = snapshot.cacheReadTokens != null,
            cacheCreationTokens = snapshot.cacheCreationTokens,
        )
    }

    private fun JsonObject.explicitInt(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    /** 本响应里各 usage 字段已确认的值；null 表示该字段至今没有显式上报过。 */
    private data class ClaudeUsageSnapshot(
        val inputTokens: Int? = null,
        val cacheReadTokens: Int? = null,
        val cacheCreationTokens: Int? = null,
        val outputTokens: Int? = null,
    ) {
        /** prompt 包含普通输入 + 命中缓存 + 新写入缓存三部分，三者都是计费输入。 */
        val promptTokens: Int get() = (inputTokens ?: 0) + (cacheReadTokens ?: 0) + (cacheCreationTokens ?: 0)
        val completionTokens: Int get() = outputTokens ?: 0
    }

    private data class ClaudeStreamBlock(
        val kind: String,
        val id: String,
        val metadata: JsonObject? = null,
    )
}
