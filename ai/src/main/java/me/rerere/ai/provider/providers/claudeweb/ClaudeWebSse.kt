package me.rerere.ai.provider.providers.claudeweb

import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.stream.SseEvent
import java.io.Reader

/** 一次回答允许的 SSE 总量（按字符估算，客户端自保上限，不是服务端额度）。 */
internal const val CLAUDE_WEB_MAX_TOTAL_CHARS = 8 * 1024 * 1024

/** 单个 SSE 事件的数据上限。 */
internal const val CLAUDE_WEB_MAX_EVENT_CHARS = 1 * 1024 * 1024

/** 最终回答文本上限。 */
internal const val CLAUDE_WEB_MAX_TEXT_CHARS = 1 * 1024 * 1024

/**
 * 本地 SSE 读取器。
 *
 * 为什么不用 `okhttp-sse` 或 common 里的 `ServerSentEventReader`：那个是 internal 且对 EOF 很宽松
 * （末尾没有空行也会把半截事件当事件交出去）。这里必须严格——连接在行中间、或事件没以空行收尾就断开时，
 * 直接抛 [WebProtocolException]（IOException），绝不合成一个"正常结束"。
 *
 * 解码按字符进行（[Reader] 已按 UTF-8 解码），所以多字节字符跨 TCP 分段时不会被撕裂。
 */
internal class ClaudeWebSseReader(
    private val reader: Reader,
    private val maxEventChars: Int = CLAUDE_WEB_MAX_EVENT_CHARS,
    private val maxTotalChars: Int = CLAUDE_WEB_MAX_TOTAL_CHARS,
) {
    private var consumed = 0
    private var eventName: String? = null
    private var eventId: String? = null
    private val data = StringBuilder()

    /** 下一个事件；流正常结束返回 null。截断/超限抛异常。 */
    fun nextEvent(): SseEvent? {
        while (true) {
            val line = readLine() ?: run {
                if (data.isNotEmpty() || eventName != null) {
                    throw WebProtocolException("Claude Web 的 SSE 在事件结束前断开，回复可能被截断")
                }
                return null
            }
            if (line.isEmpty()) {
                if (data.isEmpty() && eventName == null) continue
                val payload = data.toString().trimEnd('\n')
                val event = SseEvent(id = eventId, event = eventName, data = payload)
                eventName = null
                eventId = null
                data.setLength(0)
                return event
            }
            if (line.startsWith(":")) continue // 注释 / 心跳
            val separator = line.indexOf(':')
            val field = if (separator < 0) line else line.substring(0, separator)
            val value = if (separator < 0) "" else line.substring(separator + 1).removePrefix(" ")
            when (field) {
                "event" -> eventName = value.trim()
                "id" -> eventId = value
                "data" -> {
                    data.append(value).append('\n')
                    if (data.length > maxEventChars) {
                        throw WebProtocolException("Claude Web 的 SSE 单个事件超过上限，已中止本次回答")
                    }
                }
                // retry / 未知字段按 SSE 规范忽略
                else -> Unit
            }
        }
    }

    /** 读一整行。EOF 出现在行中间（没有换行符）时抛异常：那是被切断的流，不是干净的结束。 */
    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val code = reader.read()
            if (code < 0) {
                if (line.isNotEmpty()) {
                    throw WebProtocolException("Claude Web 的 SSE 在行中间断开，回复可能被截断")
                }
                return null
            }
            consumed++
            if (consumed > maxTotalChars) {
                throw WebProtocolException("Claude Web 的 SSE 超过本次允许的总量上限，已中止本次回答")
            }
            when (code) {
                '\n'.code -> return line.toString()
                '\r'.code -> Unit // CRLF 的 CR 直接吞掉
                else -> {
                    line.append(code.toChar())
                    if (line.length > maxEventChars) {
                        throw WebProtocolException("Claude Web 的 SSE 单行超过上限，已中止本次回答")
                    }
                }
            }
        }
    }
}
