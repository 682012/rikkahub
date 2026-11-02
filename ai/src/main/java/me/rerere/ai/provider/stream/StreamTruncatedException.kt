package me.rerere.ai.provider.stream

import java.io.IOException

/**
 * 传输层在没有收到协议终态信号的情况下关闭了响应流。
 *
 * 典型场景是 VPN/代理断流：连接被 EOF 关闭，但 [StreamChunkDecoder] 还没看到该协议的结束帧
 * （[DONE]、finish_reason、message_stop、response.completed、finishReason 等）。
 * 此时调用方应把异常抛给上层，而不是合成一个 Finish 把半截回复当成成功。
 *
 * 继承 [IOException]，与 OkHttp 传输层的错误语义保持一致。
 */
class StreamTruncatedException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
