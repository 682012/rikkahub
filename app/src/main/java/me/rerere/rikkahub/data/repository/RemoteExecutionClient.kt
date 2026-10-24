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
                            check(it.isSuccessful) { "Remote request $path failed: HTTP ${it.code}" }
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
