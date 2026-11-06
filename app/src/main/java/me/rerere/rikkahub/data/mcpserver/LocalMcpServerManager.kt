package me.rerere.rikkahub.data.mcpserver

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TAG = "LocalMcpServer"

/** 只绑回环地址：本机 MCP 是给同机客户端用的，不做局域网发现，也不监听 0.0.0.0。 */
private const val LOOPBACK_HOST = "127.0.0.1"

private val SUPPORTED_PROTOCOL_VERSIONS = setOf(
    "2025-11-25",
    "2025-06-18",
    "2025-03-26",
    "2024-11-05",
)

enum class LocalMcpServerStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/**
 * [status] 是**真实运行状态**，[LocalMcpSettings.enabled] 只是用户意图；两者分开显示，
 * 不允许"设了开"就当运行成功。
 */
data class LocalMcpRuntimeState(
    val status: LocalMcpServerStatus = LocalMcpServerStatus.STOPPED,
    val port: Int = LOCAL_MCP_DEFAULT_PORT,
    val endpoint: String? = null,
    val error: String? = null,
    val startedAt: Long? = null,
) {
    val isRunning: Boolean get() = status == LocalMcpServerStatus.RUNNING
}

/**
 * 本机 MCP 的 HTTP 服务生命周期。
 *
 * - 直接用 `embeddedServer(CIO)` 绑 127.0.0.1，不走旧的 `startWebServer`（那个是 anyHost + CORS + 静态 SPA）；
 * - start / stop / restart 用一把 Mutex 串行，避免并发启停留下重复端口；
 * - 协议解析交给 [LocalMcpProtocol]，鉴权交给 `authorizeLocalMcp`，这里只管 HTTP 边界。
 */
class LocalMcpServerManager(
    private val context: Context,
    private val host: LocalMcpToolHost,
    val settingsStore: LocalMcpSettingsStore = LocalMcpSettingsStore(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    val settings: StateFlow<LocalMcpSettings> = settingsStore.state

    private val _runtime = MutableStateFlow(
        LocalMcpRuntimeState(port = settingsStore.current().port)
    )
    val runtime: StateFlow<LocalMcpRuntimeState> = _runtime.asStateFlow()

    private val lifecycleMutex = Mutex()
    private val requestSemaphore = Semaphore(LOCAL_MCP_MAX_CONCURRENT_REQUESTS)

    /**
     * 唯一的"此刻还接不接连接"闸门。关闭/重启/启动失败时立刻置 false，**先于**任何引擎操作，
     * 所以路由里第一时间就能回 503，绝对不会再读 body、更不会执行 host。
     */
    private val accepting = AtomicBoolean(false)

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var protocol: LocalMcpProtocol? = null

    /** 正在后台收尾的引擎；下一次 start 绑端口前先等它把端口放开。 */
    @Volatile
    private var stoppingThread: Thread? = null

    // ---------------- 生命周期 ----------------

    suspend fun start(): Boolean = lifecycleMutex.withLock { startLocked() }

    suspend fun stop() = lifecycleMutex.withLock { stopLocked() }

    suspend fun restart(): Boolean = lifecycleMutex.withLock {
        stopLocked()
        startLocked()
    }

    /**
     * 设置改动立即生效：关总开关 → 停；打开 → 起；只改端口 → 重启；改 token/授权 → 下一次请求就生效。
     */
    suspend fun applySettings(
        transform: (LocalMcpSettings) -> LocalMcpSettings,
    ): LocalMcpSettings {
        val before = settingsStore.current()
        val after = settingsStore.update(transform)
        if (before != after) syncWithSettings()
        return after
    }

    /** 把真实运行状态拉回与用户意图一致（幂等）。UI / FGS / 恢复入口都可以反复调。 */
    suspend fun syncWithSettings(): Boolean {
        val current = settingsStore.current()
        val running = server != null && accepting.get()
        return when {
            !current.enabled -> {
                if (server != null) stop()
                false
            }

            !running -> {
                // 引擎还在、但已经不接连接（半死状态）时先收干净再起，避免端口被自己占着。
                if (server != null) stop()
                start()
            }

            _runtime.value.port != current.port -> restart()
            else -> true
        }
    }

    /** App 正常前台启动时恢复用户此前显式打开的服务；没有 enabled 就什么都不做。 */
    suspend fun restoreIfEnabled(): Boolean =
        if (settingsStore.current().enabled) start() else false

    /**
     * FGS 被系统杀掉 / onDestroy 时调用：同步兜底停服，保证不留"还在接请求"的状态、不留 RUNNING。
     *
     * 这里刻意**不**阻塞调用线程去做引擎 `stop()`：那段会等待在途请求，最长几百毫秒，放在主线程上就是
     * ANR 候选。顺序是：先把 [accepting] 置 false（后续请求立刻 503、绝不执行 host）→ 立即把运行状态
     * 落成 STOPPED（UI/通知不会显示"在跑"）→ 把引擎收尾交给独立线程，最多同步等
     * [LOCAL_MCP_STOP_JOIN_MS] 毫秒。进程真被杀掉时 socket 也随之释放，不会留端口。
     */
    fun stopBlocking() {
        accepting.set(false)
        val current = server
        server = null
        protocol = null
        _runtime.value = _runtime.value.copy(
            status = LocalMcpServerStatus.STOPPED,
            endpoint = null,
            startedAt = null,
        )
        if (current == null) return
        val stopper = Thread(
            {
                runCatching { current.stop(LOCAL_MCP_STOP_GRACE_MS, LOCAL_MCP_STOP_TIMEOUT_MS) }
                    .onFailure { Log.w(TAG, "stop local mcp server failed: ${it::class.simpleName}") }
            },
            "local-mcp-stop",
        ).apply { isDaemon = true }
        stoppingThread = stopper
        stopper.start()
        runCatching { stopper.join(LOCAL_MCP_STOP_JOIN_MS) }
    }

    /** 前台服务启动失败等错误，交给 UI 如实显示，不假装在跑。 */
    fun reportError(message: String) {
        accepting.set(false)
        _runtime.value = _runtime.value.copy(
            status = LocalMcpServerStatus.ERROR,
            endpoint = null,
            error = message,
        )
    }

    fun shutdown() {
        stopBlocking()
        scope.cancel()
    }

    // ---------------- start / stop 内部实现 ----------------

    private suspend fun startLocked(): Boolean = withContext(Dispatchers.IO) {
        // 上一次的引擎可能还在后台收尾：先等它把端口放开（有界），再去探测端口。
        stoppingThread?.let { thread ->
            runCatching { thread.join(LOCAL_MCP_STOP_JOIN_MS * 6) }
            if (!thread.isAlive) stoppingThread = null
        }

        val current = settingsStore.current()
        server?.let {
            // 已经在跑：状态对齐，不重复绑端口
            accepting.set(true)
            _runtime.value = _runtime.value.copy(
                status = LocalMcpServerStatus.RUNNING,
                port = current.port,
                endpoint = current.endpoint,
                error = null,
            )
            return@withContext true
        }

        val problems = validateLocalMcpSettings(current)
        if (problems.isNotEmpty()) {
            accepting.set(false)
            _runtime.value = LocalMcpRuntimeState(
                status = LocalMcpServerStatus.ERROR,
                port = current.port,
                error = problems.joinToString(",") { it.name },
            )
            return@withContext false
        }

        _runtime.value = LocalMcpRuntimeState(
            status = LocalMcpServerStatus.STARTING,
            port = current.port,
            endpoint = current.endpoint,
        )

        if (!isLoopbackPortAvailable(current.port)) {
            accepting.set(false)
            _runtime.value = _runtime.value.copy(
                status = LocalMcpServerStatus.ERROR,
                endpoint = null,
                error = "port_in_use:${current.port}",
            )
            return@withContext false
        }

        return@withContext try {
            val created = embeddedServer(CIO, port = current.port, host = LOOPBACK_HOST) {
                installLocalMcpModule()
            }
            created.start(wait = false)
            server = created
            protocol = LocalMcpProtocol(
                tools = { settingsStore.current().visibleTools(host.tools()) },
                invoke = { name, arguments -> host.call(name, arguments) },
            )
            if (!awaitLoopbackListening(current.port)) {
                // 绑不上的情况在部分 ROM 上是异步失败的：再核一次，失败就如实报错
                stopBlocking()
                _runtime.value = LocalMcpRuntimeState(
                    status = LocalMcpServerStatus.ERROR,
                    port = current.port,
                    error = "bind_failed:${current.port}",
                )
                false
            } else {
                // 只有真的在监听时才打开闸门：在这之前的任何请求都拿 503，不会执行 host。
                accepting.set(true)
                _runtime.value = LocalMcpRuntimeState(
                    status = LocalMcpServerStatus.RUNNING,
                    port = current.port,
                    endpoint = current.endpoint,
                    startedAt = System.currentTimeMillis(),
                )
                true
            }
        } catch (cancelled: CancellationException) {
            // 取消不是"启动失败"：如实收尾并把取消继续抛出去，别被下面的 catch 吞掉。
            accepting.set(false)
            ctxStopAfterFailure()
            throw cancelled
        } catch (fatal: Error) {
            accepting.set(false)
            ctxStopAfterFailure()
            throw fatal
        } catch (error: Exception) {
            Log.e(TAG, "start local mcp server failed: ${error::class.simpleName}: ${error.message}")
            ctxStopAfterFailure()
            _runtime.value = LocalMcpRuntimeState(
                status = LocalMcpServerStatus.ERROR,
                port = current.port,
                error = error.message ?: error::class.simpleName ?: "start_failed",
            )
            false
        }
    }

    private fun ctxStopAfterFailure() {
        accepting.set(false)
        val current = server ?: return
        server = null
        protocol = null
        runCatching { current.stop(0, 500) }
    }

    private suspend fun stopLocked() {
        // 先关闸门：从这里开始所有请求 503，不会再有新的 host 调用。
        accepting.set(false)
        val current = server
        _runtime.value = _runtime.value.copy(status = LocalMcpServerStatus.STOPPING)
        withContext(Dispatchers.IO) {
            server = null
            protocol = null
            runCatching { current?.stop(200, 800) }
                .onFailure { Log.w(TAG, "stop local mcp server failed: ${it::class.simpleName}") }
        }
        _runtime.value = LocalMcpRuntimeState(
            status = LocalMcpServerStatus.STOPPED,
            port = settingsStore.current().port,
        )
    }

    // ---------------- HTTP 路由 ----------------

    private fun Application.installLocalMcpModule() {
        routing {
            post("/mcp") { handleMcpPost(call) }
            // GET/DELETE 也要先过连接级校验：MCP 规范要求所有连接都鉴权，405 不是"免检通道"。
            get("/mcp") { handleNonPostMethod(call) }
            delete("/mcp") { handleNonPostMethod(call) }
        }
    }

    /** GET / DELETE：先过连接级判定（503 / 401 / 403），都过关才轮到 405。 */
    private suspend fun handleNonPostMethod(call: ApplicationCall) {
        if (call.rejectIfNotAllowed()) return
        call.respondMethodNotAllowed()
    }

    private suspend fun ApplicationCall.respondMethodNotAllowed() {
        response.header(HttpHeaders.Allow, ALLOWED_METHODS)
        respondText(
            text = "method_not_allowed",
            contentType = ContentType.Text.Plain,
            status = HttpStatusCode.MethodNotAllowed,
        )
    }

    private suspend fun ApplicationCall.respondRejection(rejection: LocalMcpHttpDecision.Reject) {
        rejection.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it) }
        if (rejection.wwwAuthenticate) response.header(HttpHeaders.WWWAuthenticate, "Bearer")
        rejection.allowHeader?.let { response.header(HttpHeaders.Allow, it) }
        respondJsonRpcError(
            status = HttpStatusCode.fromValue(rejection.status),
            code = rejection.code,
            message = rejection.message,
        )
    }

    /**
     * 连接级校验，返回 `true` 表示已经回了拒绝响应。
     *
     * 每次调用都现读设置：token 被轮换、总开关被关掉之后，已经进来的 handler 也拿不到授权
     * （不放行 = 不会执行 host）。
     */
    private suspend fun ApplicationCall.rejectIfNotAllowed(): Boolean {
        val current = settingsStore.current()
        val decision = decideLocalMcpConnection(
            accepting = accepting.get(),
            enabled = current.enabled,
            method = request.httpMethod.value,
            authorization = request.headers[HttpHeaders.Authorization],
            token = current.token,
            origin = request.headers[HttpHeaders.Origin],
            host = request.headers[HttpHeaders.Host],
            // 用真实监听端口校验 Host，而不是设置里刚改、还没重启的端口
            port = _runtime.value.port,
            allowHeader = ALLOWED_METHODS,
        )
        return when (decision) {
            is LocalMcpHttpDecision.Proceed -> false
            is LocalMcpHttpDecision.Reject -> {
                respondRejection(decision)
                true
            }
        }
    }

    private suspend fun ApplicationCall.respondJsonRpcError(
        status: HttpStatusCode,
        code: Int,
        message: String,
    ) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
        respondText(body.toString(), ContentType.Application.Json, status)
    }

    private suspend fun handleMcpPost(call: ApplicationCall) {
        // 1) 连接级校验：503（没在接连接 / 被关掉）先于 401/403（token、Origin、Host）
        if (call.rejectIfNotAllowed()) return

        // 2) Content-Type 必须是 JSON（带 charset 也可以）
        if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
            call.respondJsonRpcError(HttpStatusCode.UnsupportedMediaType, -32600, "unsupported_content_type")
            return
        }

        // 3) Accept：只做 JSON 模式，客户端必须能接受 JSON
        val accept = call.request.headers[HttpHeaders.Accept]
        if (accept != null && accept.isNotBlank() && !acceptAcceptsJson(accept)) {
            call.respondJsonRpcError(HttpStatusCode.NotAcceptable, -32600, "accept_must_include_json")
            return
        }

        // 4) MCP 协议版本：只认已知版本，未来版本明确拒绝而不是装作支持
        val protocolVersion = call.request.headers["MCP-Protocol-Version"]
        if (protocolVersion != null && protocolVersion !in SUPPORTED_PROTOCOL_VERSIONS) {
            call.response.header("MCP-Protocol-Version", "2025-06-18")
            call.respondJsonRpcError(
                HttpStatusCode.BadRequest,
                -32600,
                "unsupported_protocol_version",
            )
            return
        }

        // 5) 体积：Content-Length 先拦，真正的块读在下面带硬超时
        val declaredLength = call.request.contentLength()
        if (declaredLength != null && declaredLength > LOCAL_MCP_MAX_REQUEST_BYTES) {
            call.respondJsonRpcError(HttpStatusCode.PayloadTooLarge, -32600, "request_too_large")
            return
        }

        // 6) 并发闸门必须在读 body **之前**：否则 N 个"发完头就慢慢滴流 8KiB"的连接能各自无限期
        //    读下去，既吃连接也吃内存；拿不到许可的直接 429，连 body 都不读。
        if (!requestSemaphore.tryAcquire()) {
            call.response.header(HttpHeaders.RetryAfter, "1")
            call.respondJsonRpcError(HttpStatusCode.TooManyRequests, -32002, "too_many_requests")
            return
        }

        try {
            val body = try {
                // 读 body 有硬超时：slowloris 拿不到无限时间，超时就 408 并放弃这条请求。
                withTimeoutOrNull(LOCAL_MCP_BODY_READ_TIMEOUT_MS) { readBody(call) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (tooLarge: RequestBodyTooLarge) {
                call.respondJsonRpcError(HttpStatusCode.PayloadTooLarge, -32600, "request_too_large")
                return
            } catch (failed: Exception) {
                call.respondJsonRpcError(HttpStatusCode.BadRequest, -32700, "body_read_failed")
                return
            }
            if (body == null) {
                call.respondJsonRpcError(HttpStatusCode.RequestTimeout, -32000, "request_timeout")
                return
            }
            if (body.isBlank()) {
                call.respondJsonRpcError(HttpStatusCode.BadRequest, -32700, "empty_body")
                return
            }

            // 7) 执行前再核一次：等 body 的这段时间里 token 可能被轮换、服务可能被关掉，
            //    此时必须拒绝——不能拿一个"过去合法"的授权去执行写操作。
            if (call.rejectIfNotAllowed()) return

            val activeProtocol = protocol ?: LocalMcpProtocol(
                tools = { settingsStore.current().visibleTools(host.tools()) },
                invoke = { name, arguments -> host.call(name, arguments) },
            )
            val result: JsonObject? = try {
                activeProtocol.handleText(body)
            } catch (cancelled: CancellationException) {
                // 取消不是"内部错误"：原样抛回，让协程取消语义保持完整。
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (error: Exception) {
                // 只记类型和 message，不把原始堆栈/异常对象回给客户端（响应固定 internal_error）。
                Log.w(TAG, "handle mcp request failed: ${error::class.simpleName}: ${error.message}")
                call.respondJsonRpcError(HttpStatusCode.InternalServerError, -32603, "internal_error")
                return
            }
            if (result == null) {
                // 通知：202 无 body，按 MCP Streamable HTTP 的 JSON 模式
                call.respondText("", ContentType.Text.Plain, HttpStatusCode.Accepted)
            } else {
                call.respondText(result.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            }
        } finally {
            requestSemaphore.release()
        }
    }

    /** body 超过 [LOCAL_MCP_MAX_REQUEST_BYTES]：立刻中止，不再继续读流。 */
    private class RequestBodyTooLarge : Exception("request_too_large")

    /** 按块读 body；超限抛 [RequestBodyTooLarge]，读超时由调用方用 withTimeoutOrNull 控制。 */
    private suspend fun readBody(call: ApplicationCall): String {
        val channel = call.receiveChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read <= 0) break
            total += read
            if (total > LOCAL_MCP_MAX_REQUEST_BYTES) throw RequestBodyTooLarge()
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

/** 本服务只提供 `POST`（JSON 响应模式）；非 POST 的响应必须如实写明允许什么。 */
private const val ALLOWED_METHODS = "POST"

/** 纯函数版的连接级判定结果；单测直接对着它断言顺序（503 → 鉴权 → 405）。 */
sealed interface LocalMcpHttpDecision {
    data object Proceed : LocalMcpHttpDecision

    data class Reject(
        val status: Int,
        val code: Int,
        val message: String,
        val retryAfterSeconds: String? = null,
        val wwwAuthenticate: Boolean = false,
        val allowHeader: String? = null,
    ) : LocalMcpHttpDecision
}

/**
 * 连接级判定的**唯一**实现（纯函数，可单测）。顺序本身是安全语义：
 *
 * 1. 服务没在接连接（未启动 / 正在停 / 已关闭 / 启动失败）→ 503：不读 body、不执行任何工具；
 * 2. 用户把总开关关掉 → 同样 503（先于鉴权：这时不存在"合法 token"）；
 * 3. token、`Origin`、`Host` 全部照 [authorizeLocalMcp] 校验，**所有方法**都要过，GET/DELETE 也不例外；
 * 4. 最后才是方法：本服务只做 POST，`GET` / `DELETE` 一律 405（带 `Allow: POST`）。
 */
fun decideLocalMcpConnection(
    accepting: Boolean,
    enabled: Boolean,
    method: String,
    authorization: String?,
    token: String,
    origin: String?,
    host: String?,
    port: Int,
    allowHeader: String = "POST",
): LocalMcpHttpDecision {
    if (!accepting || !enabled) {
        return LocalMcpHttpDecision.Reject(
            status = HttpStatusCode.ServiceUnavailable.value,
            code = -32003,
            message = "server_unavailable",
            retryAfterSeconds = "1",
        )
    }
    when (authorizeLocalMcp(authorization, token, origin, host, port)) {
        HttpStatusCode.Unauthorized.value -> return LocalMcpHttpDecision.Reject(
            status = HttpStatusCode.Unauthorized.value,
            code = -32001,
            message = "unauthorized",
            wwwAuthenticate = true,
        )

        HttpStatusCode.Forbidden.value -> return LocalMcpHttpDecision.Reject(
            status = HttpStatusCode.Forbidden.value,
            code = -32001,
            message = "forbidden",
        )
    }
    if (!method.equals("POST", ignoreCase = true)) {
        return LocalMcpHttpDecision.Reject(
            status = HttpStatusCode.MethodNotAllowed.value,
            code = -32600,
            message = "method_not_allowed",
            allowHeader = allowHeader,
        )
    }
    return LocalMcpHttpDecision.Proceed
}

/** Accept 头是否接受 `application/json`（含通配）。 */
fun acceptAcceptsJson(accept: String): Boolean =
    accept.split(',').any { part ->
        when (part.trim().substringBefore(';').trim().lowercase()) {
            "application/json", "application/*", "*/*" -> true
            else -> false
        }
    }

/** 只检查回环端口能不能绑：预先拦掉端口占用，别等异步 bind 失败才发现。 */
private fun isLoopbackPortAvailable(port: Int): Boolean = runCatching {
    ServerSocket().use { socket ->
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(LOOPBACK_HOST, port))
    }
    true
}.getOrElse { false }

/** 真实确认服务在监听了才算 RUNNING（避免"设好了"当"跑起来了"）。 */
private fun awaitLoopbackListening(port: Int, attempts: Int = 12, delayMs: Long = 60L): Boolean {
    repeat(attempts) {
        val connected = runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(LOOPBACK_HOST, port), 150) }
            true
        }.getOrElse { false }
        if (connected) return true
        try {
            Thread.sleep(delayMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }
    return false
}
