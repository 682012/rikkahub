package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 服务端返回的错误。
 *
 * [statusCode] 只允许填**真实的 HTTP 响应码**（OkHttp `Response.code`）。从错误文本里正则猜出来的
 * 数字一概不进这里：猜错一个码，重试层会把「余额不足」当成「服务端抖动」反复重发。
 *
 * 因此解析错误文本得到的 [HttpException]（[parseErrorDetail]）状态码是 null，表示「不知道码」，
 * 自动重试层见到 null 一律按不可重试处理，不盲试。
 */
class HttpException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 给已经解析好消息的错误补上真实响应码，消息与 cause 原样保留。
 *
 * 只用于「body 已经解析出人话，但响应码在另一处」的场合（SSE 的 `onFailure` 回调）：
 * 那里消息来自 body，`Response.code` 来自回调参数，两边必须合成同一个异常，否则重试层只能看到一半事实。
 */
fun HttpException.withStatusCode(code: Int?, cause: Throwable? = null): HttpException {
    if (code == null) return this
    val keepCause = cause?.takeIf { it !== this } ?: this.cause
    return HttpException(message.orEmpty(), statusCode = code, cause = keepCause)
}

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail()
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this))
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this))
        }
    }
}
