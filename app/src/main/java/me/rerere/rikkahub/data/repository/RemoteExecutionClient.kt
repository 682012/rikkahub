package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.RemoteExecutionSettings
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceJobInfo
import me.rerere.workspace.WorkspaceJobStatus
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellable HTTP requests. Foreground shell commands use the job protocol so cancellation kills the remote job. */
class RemoteExecutionClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * requestTimeoutSec = 0（不限）时用的客户端。
     *
     * OkHttp 里 callTimeout / readTimeout 都是 0 表示"不设上限"，但 readTimeout 在 client 级别
     * 固定 30s，只把 callTimeout 置 0，仍会在 30s 收不到数据时被掐断，所以两个都要清。
     * newBuilder() 复用同一个连接池和 Dispatcher，不会多养一套线程。
     */
    private val unboundedClient by lazy { buildUnboundedClient(client) }

    suspend fun execute(config: RemoteExecutionSettings, command: String, cwd: String,
        timeoutMillis: Long): WorkspaceCommandResult {
        var info: WorkspaceJobInfo? = null
        var finished = false
        var last: WorkspaceJobStatus? = null
        try {
            // Once created, obtain the id even if the parent is cancelled during creation.
            withContext(NonCancellable) { info = startJob(config, command, cwd) }
            suspend fun waitForResult(): WorkspaceJobStatus {
                while (true) {
                    val status = jobStatus(config, checkNotNull(info).id, 400)
                    last = status
                    if (!status.running) return status
                    delay(500)
                }
            }
            val result = if (timeoutMillis <= 0) waitForResult()
                else withTimeoutOrNull(timeoutMillis) { waitForResult() }
            finished = result != null
            return WorkspaceCommandResult(
                exitCode = result?.exitCode ?: -1,
                stdout = (result ?: last)?.logTail.orEmpty(),
                stderr = "",
                timedOut = result == null,
                truncated = (result ?: last)?.logTruncated ?: false,
            )
        } finally {
            if (!finished) info?.let { job ->
                withContext(NonCancellable) {
                    // The HTTP call itself has a bounded deadline; do not strand the local generation job.
                    withTimeoutOrNull(10_000) { runCatching { killJob(config, job.id) } }
                }
            }
        }
    }

    suspend fun startJob(config: RemoteExecutionSettings, command: String, cwd: String): WorkspaceJobInfo {
        val obj = postJson(config, "/job/start", buildJsonObject {
            put("command", command); put("cwd", cwd)
        })
        return WorkspaceJobInfo(
            id = obj.string("id") ?: error("Remote job id missing"), command = command, cwd = cwd,
            pid = null, logPath = obj.string("logPath").orEmpty(), startedAt = System.currentTimeMillis(),
        )
    }

    suspend fun jobStatus(config: RemoteExecutionSettings, jobId: String, tailLines: Int): WorkspaceJobStatus {
        val obj = postJson(config, "/job/status", buildJsonObject {
            put("id", jobId); put("tail", tailLines.coerceIn(1, 400))
        })
        return WorkspaceJobStatus(
            info = WorkspaceJobInfo(jobId, obj.string("command").orEmpty(), obj.string("cwd").orEmpty(),
                null, obj.string("logPath").orEmpty(), obj.primitive("startedAt")?.longOrNull ?: 0),
            running = obj.primitive("running")?.booleanOrNull ?: error("Remote status missing running flag"),
            exitCode = obj.primitive("exitCode")?.intOrNull,
            logTail = obj.string("logTail").orEmpty(),
            logTruncated = obj.primitive("truncated")?.booleanOrNull ?: false,
        )
    }

    suspend fun killJob(config: RemoteExecutionSettings, jobId: String): Boolean =
        postJson(config, "/job/kill", buildJsonObject { put("id", jobId) })
            .primitive("ok")?.booleanOrNull ?: false

    private suspend fun postJson(config: RemoteExecutionSettings, path: String, payload: JsonObject): JsonObject {
        require(config.token.isNotBlank()) { "Remote execution requires an access token" }
        val url = (config.baseUrl.trimEnd('/') + path).toHttpUrl()
        require(url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1")) {
            "Remote execution requires HTTPS (HTTP is allowed only for loopback)"
        }
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${config.token}")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        // 这是单次 HTTP 请求的上限，不是命令总预算（总预算见 execute 的 timeoutMillis）。
        val timeoutSec = resolveRemoteCallTimeoutSec(config.requestTimeoutSec)
        val call = (if (timeoutSec > 0) client else unboundedClient).newCall(request)
        if (timeoutSec > 0) call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
        val body = suspendCancellableCoroutine<String> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                // 先读有限的一小段 body，否则"目录不存在""槽位已满"全被压成 HTTP 4xx（review P4）。
                                error(remoteHttpFailure(path, it.code, readErrorSnippet(it.body), config.token))
                            }
                            val stream = it.body?.byteStream() ?: error("Empty remote response")
                            val sink = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8_192)
                            while (sink.size() <= 1_048_576) {
                                val n = stream.read(buffer, 0, minOf(buffer.size, 1_048_577 - sink.size()))
                                if (n < 0) break
                                sink.write(buffer, 0, n)
                            }
                            val bytes = sink.toByteArray()
                            check(bytes.size <= 1_048_576) { "Remote response exceeds 1 MiB" }
                            if (!continuation.isCancelled) continuation.resume(bytes.toString(Charsets.UTF_8))
                        }
                    } catch (e: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                }
            })
        }
        return json.parseToJsonElement(body).jsonObject
    }
}

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull

/** 错误响应最多读取/回显的字节数。错误信息只用于模型自纠，不需要整页 body。 */
internal const val REMOTE_ERROR_BODY_LIMIT = 2 * 1024

/** 单条错误原因的最大字符数（远小于 body 上限，给 HTTP 码等前缀留余量）。 */
private const val REMOTE_ERROR_REASON_LIMIT = 200

// 错误信息可能被模型原样转述给用户，所以凭据、完整 URL 一律不回显。
private val REMOTE_BEARER_PATTERN = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")
private val REMOTE_URL_PATTERN = Regex("(?i)\\bhttps?://[^\\s\"'<>]+")
private val REMOTE_HTML_TAG = Regex("<[^<>]{0,400}>")
private val REMOTE_WHITESPACE = Regex("\\s+")
private val REMOTE_JSON_REASON_FRAGMENT =
    Regex("\"(?:error|message)\"\\s*:\\s*\"([^\"]{1,400})\"")

/**
 * 从错误响应 body 里取一段可读原因。
 *
 * 顺序：JSON `error` → JSON `message` → 截断/非 JSON 时的字段片段 → 安全摘要。
 * 都取不到返回 null（调用方只报 HTTP 码）。服务端 rk-exec-server.py 的错误统一是
 * `{"error": ...}`，反代/网关那边则常是 HTML 错误页。
 */
internal fun remoteErrorReason(body: String?, secret: String? = null): String? {
    val text = body?.trim().orEmpty()
    if (text.isEmpty()) return null
    val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    val fromJson = json?.let { obj -> obj.string("error") ?: obj.string("message") }
    // body 被 2KiB 上限截断时 JSON 解析必然失败，退一步从片段里捞字段，别把 '{' 回显给模型。
    val fromFragment = REMOTE_JSON_REASON_FRAGMENT.find(text)?.groupValues?.get(1)
    return sanitizeRemoteReason(fromJson ?: fromFragment ?: text.replace(REMOTE_HTML_TAG, " "), secret)
}

/** 折叠空白、屏蔽凭据/URL、按上限截断（超长只留开头，避免把整页 HTML 塞进上下文）。 */
private fun sanitizeRemoteReason(raw: String, secret: String?): String? {
    val redacted = if (secret.isNullOrEmpty()) raw else raw.replace(secret, "[redacted]")
    val text = redacted.replace(REMOTE_BEARER_PATTERN, "Bearer [redacted]")
        .replace(REMOTE_URL_PATTERN, "[url]")
        .replace(REMOTE_WHITESPACE, " ")
        .trim()
    if (text.isEmpty()) return null
    return if (text.length <= REMOTE_ERROR_REASON_LIMIT) text
    else text.take(REMOTE_ERROR_REASON_LIMIT - 1).trimEnd() + "…"
}

/** 统一的 HTTP 失败信息：只带 endpoint 路径，不带 baseUrl/query，避免泄露主机与凭据。 */
internal fun remoteHttpFailure(path: String, httpCode: Int, body: String?, secret: String? = null): String {
    val reason = remoteErrorReason(body, secret)
    return if (reason == null) "Remote request $path failed: HTTP $httpCode"
    else "Remote request $path failed: HTTP $httpCode: $reason"
}

/** Error details are optional: cap both their byte count and total network waiting time. */
internal const val REMOTE_ERROR_SNIPPET_TIMEOUT_MILLIS = 2_000L

/**
 * Read a bounded error preview even when successful requests allow unlimited time. Preserve bytes
 * already received on a stalled body, then restore the response source's original timeout state.
 */
private fun readErrorSnippet(body: ResponseBody?): String {
    if (body == null) return ""
    val buffer = ByteArray(REMOTE_ERROR_BODY_LIMIT)
    val source = body.source()
    val timeout = source.timeout()
    val oldTimeout = timeout.timeoutNanos()
    val oldDeadline = if (timeout.hasDeadline()) timeout.deadlineNanoTime() else null
    val previewTimeout = TimeUnit.MILLISECONDS.toNanos(REMOTE_ERROR_SNIPPET_TIMEOUT_MILLIS)
    val previewDeadline = System.nanoTime() + previewTimeout
    timeout.timeout(if (oldTimeout == 0L) previewTimeout else minOf(oldTimeout, previewTimeout), TimeUnit.NANOSECONDS)
    timeout.deadlineNanoTime(oldDeadline?.let { minOf(it, previewDeadline) } ?: previewDeadline)
    var filled = 0
    try {
        runCatching {
            while (filled < buffer.size) {
                val read = source.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
        }
    } finally {
        timeout.timeout(oldTimeout, TimeUnit.NANOSECONDS)
        if (oldDeadline == null) timeout.clearDeadline() else timeout.deadlineNanoTime(oldDeadline)
    }
    return String(buffer, 0, filled, Charsets.UTF_8)
}

/**
 * RemoteExecutionSettings.requestTimeoutSec -> 单次 HTTP 请求的超时（秒）。
 *
 * 0 原样返回 0：OkHttp 里 callTimeout / readTimeout 为 0 就是"不限"。旧实现写成
 * `takeIf { it > 0 } ?: 30`，把 0 悄悄改成了 30s，与设置里"0 表示不限制"的说明不符
 * （服务端慢一点时 /job/status 或 /job/start 会被 30s 掐断，命令本身其实没超时）。
 *
 * 负值没有意义，直接拒绝：静默按 0 处理会变成"永不超时"，把协程挂死更糟。
 *
 * 注意与命令总预算的区别：命令能跑多久看 WorkspaceRuntimeSettings.commandTimeoutSec /
 * maxTimeoutSec（WorkspaceTools.resolveTimeoutMillis），由 RemoteExecutionClient.execute 的
 * timeoutMillis + withTimeoutOrNull 控制，轮询期间每个 HTTP 请求各算各的。
 */
internal fun resolveRemoteCallTimeoutSec(requestTimeoutSec: Int): Long {
    require(requestTimeoutSec >= 0) {
        "Remote requestTimeoutSec must be >= 0 (0 means no limit), got $requestTimeoutSec"
    }
    return requestTimeoutSec.toLong()
}

/** 把 [base] 的超时全部放开（0 = 不设上限）；连接超时保留，避免连不上时一直等。 */
internal fun buildUnboundedClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .callTimeout(0, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    .writeTimeout(0, TimeUnit.SECONDS)
    .build()
