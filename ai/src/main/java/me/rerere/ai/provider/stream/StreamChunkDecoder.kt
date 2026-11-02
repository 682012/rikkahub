package me.rerere.ai.provider.stream

import me.rerere.ai.ui.StreamChunk

/**
 * 一次 Provider 响应流所使用的有状态、传输无关解码器。
 * 每条响应流必须创建独立实例。
 */
interface StreamChunkDecoder {
    /** 将单个原始 SSE 事件转换为通用流事件。解析失败时直接抛出异常。 */
    fun accept(event: SseEvent): DecodeResult

    /**
     * SSE 关闭时收尾。实现必须保证该方法及显式终止事件产生的 Finish 幂等。
     *
     * 协议终态（`[DONE]`、finish_reason、message_stop、response.completed 等）已经到达时返回收尾 chunk；
     * 没有终态就抛 [StreamTruncatedException]，禁止把断流当成正常结束。调用方必须捕获并把这些异常
     * 传给下游，而不是让它在传输层回调里冒泡。
     */
    fun onClosed(): List<StreamChunk>
}

data class DecodeResult(
    val chunks: List<StreamChunk> = emptyList(),
    /** Provider 协议已经明确结束，传输层可以主动关闭连接。 */
    val completed: Boolean = false,
)
