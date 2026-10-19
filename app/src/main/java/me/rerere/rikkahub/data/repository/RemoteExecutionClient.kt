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
        val call = client.newCall(request)
        call.timeout().timeout((config.requestTimeoutSec.takeIf { it > 0 } ?: 30).toLong(), TimeUnit.SECONDS)
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
