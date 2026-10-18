package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.RemoteExecutionSettings
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceJobInfo
import me.rerere.workspace.WorkspaceJobStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 远程执行后端客户端。
 *
 * 协议刻意保持最小：POST /exec，JSON 进 JSON 出。服务端只需要一个带 token 的
 * 执行器，命令在服务端的工作目录里跑，因此不受 Android 后台限制。
 */
class RemoteExecutionClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun execute(
        config: RemoteExecutionSettings,
        command: String,
        cwd: String,
        timeoutMillis: Long,
    ): WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trimEnd('/')
        require(base.isNotBlank()) { "Remote execution base URL is empty" }
        val payload = buildJsonObject {
            put("command", command)
            put("cwd", cwd)
            put("timeout", if (timeoutMillis <= 0) 0 else (timeoutMillis / 1000).toInt())
        }.toString()
        val request = Request.Builder()
            .url("$base/exec")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .apply {
                if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}")
            }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "Remote execution failed: HTTP ${response.code} ${body.take(500)}"
            }
            val obj = json.parseToJsonElement(body).jsonObject
            WorkspaceCommandResult(
                exitCode = obj["exitCode"]?.jsonPrimitive?.intOrNull ?: -1,
                stdout = obj["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                stderr = obj["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                timedOut = obj["timedOut"]?.jsonPrimitive?.booleanOrNull ?: false,
                truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    suspend fun startJob(
        config: RemoteExecutionSettings,
        command: String,
        cwd: String,
    ): WorkspaceJobInfo = withContext(Dispatchers.IO) {
        val obj = postJson(config, "/job/start", buildJsonObject {
            put("command", command)
            put("cwd", cwd)
        })
        WorkspaceJobInfo(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: error("Remote job id missing"),
            command = command,
            cwd = cwd,
            pid = null,
            logPath = obj["logPath"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            startedAt = System.currentTimeMillis(),
        )
    }

    suspend fun jobStatus(
        config: RemoteExecutionSettings,
        jobId: String,
        tailLines: Int,
    ): WorkspaceJobStatus = withContext(Dispatchers.IO) {
        val obj = postJson(config, "/job/status", buildJsonObject {
            put("id", jobId)
            put("tail", tailLines)
        })
        val info = WorkspaceJobInfo(
            id = jobId,
            command = obj["command"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            pid = null,
            logPath = obj["logPath"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            startedAt = obj["startedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
        WorkspaceJobStatus(
            info = info,
            running = obj["running"]?.jsonPrimitive?.booleanOrNull ?: false,
            exitCode = obj["exitCode"]?.jsonPrimitive?.intOrNull,
            logTail = obj["logTail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            logTruncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    suspend fun killJob(config: RemoteExecutionSettings, jobId: String): Boolean = withContext(Dispatchers.IO) {
        val obj = postJson(config, "/job/kill", buildJsonObject { put("id", jobId) })
        obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    private fun postJson(
        config: RemoteExecutionSettings,
        path: String,
        payload: kotlinx.serialization.json.JsonObject,
    ): kotlinx.serialization.json.JsonObject {
        val base = config.baseUrl.trimEnd('/')
        require(base.isNotBlank()) { "Remote execution base URL is empty" }
        val request = Request.Builder()
            .url(base + path)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}")
            }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "Remote request $path failed: HTTP ${response.code} ${body.take(300)}"
            }
            return json.parseToJsonElement(body).jsonObject
        }
    }
}
