package me.rerere.rikkahub.data.ai.mcp

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "McpSessionRegistry"
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val BASE_RECONNECT_DELAY_MS = 1000L
private const val MAX_RECONNECT_DELAY_MS = 30000L

/**
 * 快速重连预算耗尽后不再永久停死，转入等待网络事件或下一次调用；网络恢复（epoch 变化）会立刻重置预算。
 */

/** callTool 尚未发送时，允许短暂、可取消地等待一次合并重连。 */
private const val TOOL_CALL_RECONNECT_WAIT_MS = 15_000L
private const val TOOL_CALL_RECONNECT_POLL_MS = 200L

/** 单个 MCP Server 的全部运行时状态。 */
private class McpSession(initialConfig: McpServerConfig) {
    @Volatile
    var config: McpServerConfig = initialConfig

    @Volatile
    var client: Client? = null

    @Volatile
    var connectedConfig: McpServerConfig? = null

    /**
     * client 是否真的可用。
     *
     * 旧 client 在 reconnect 真正换上新 client 并完成工具同步之前一律为 false，
     * 调用方（工具列表、callTool）不会从它身上拿到「健康」假象。
     */
    @Volatile
    var healthy: Boolean = false

    val lifecycleMutex = Mutex()
    @Volatile
    var reconnectJob: Job? = null
    @Volatile
    var connectJob: Job? = null
    var connectedAtNanos: Long = 0L
    var reconnectAttempt: Int = 0

    /** 在途重连期间又收到「当前 client 已死」的通知：当前 job 收尾后再补一次合并重连。 */
    var reconnectPending: Boolean = false

    /** 只有 [healthy] 时才对调用方暴露 client。 */
    val healthyClient: Client?
        get() = if (healthy) client else null
}

private sealed interface ConnectResult {
    data object Success : ConnectResult
    data object Stale : ConnectResult
    data object NeedsAuthorization : ConnectResult
    data object Failed : ConnectResult
}

internal class McpClientUnavailableException(message: String) : IllegalStateException(message)

/**
 * 工具调用已经发出、但连接在拿到结果之前就断了：服务端可能已经执行。
 *
 * 这类失败绝不自动重放（避免重复写入 / 重复支付），消息里必须明确「结果不确定」。
 */
internal class McpToolCallOutcomeUnknownException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class McpStatusStore {
    private val _status = MutableStateFlow<Map<Uuid, McpStatus>>(emptyMap())
    val status: StateFlow<Map<Uuid, McpStatus>> = _status.asStateFlow()

    fun get(configId: Uuid): Flow<McpStatus> =
        status.map { it[configId] ?: McpStatus.Idle }.distinctUntilChanged()

    fun update(configId: Uuid, status: McpStatus) {
        _status.update { current -> current + (configId to status) }
    }

    fun remove(configId: Uuid) {
        _status.update { current -> current - configId }
    }
}

/**
 * 一次重连请求的准入输入。
 *
 * 抽成纯数据是为了让「已删除 / 已禁用 / 401 / 已有在途任务 / 离线」这些边界能被单测直接验证，
 * 而不是靠真实网络跑出来。
 */
internal data class ReconnectGateState(
    val serverExists: Boolean,
    val enabled: Boolean,
    val nameValid: Boolean,
    val needsAuthorization: Boolean,
    val hasActiveReconnectJob: Boolean,
    val networkAvailable: Boolean,
)

internal enum class ReconnectAdmission {
    /** 允许发起重连。 */
    ACCEPT,

    /** 已有在途重连任务：并入现有任务，绝不开第二个。 */
    JOIN_RUNNING,

    /** 配置里已经删掉了，不能复活。 */
    DROP_DELETED,

    /** 被禁用或名字为空，不能复活。 */
    DROP_DISABLED,

    /** 401 需要用户完成授权，不得自动循环。 */
    DROP_NEEDS_AUTH,

    /** 离线：停车等网络恢复，不消耗重连预算。 */
    PARK_OFFLINE,
}

internal fun admitReconnect(state: ReconnectGateState): ReconnectAdmission = when {
    !state.serverExists -> ReconnectAdmission.DROP_DELETED
    !state.enabled || !state.nameValid -> ReconnectAdmission.DROP_DISABLED
    state.needsAuthorization -> ReconnectAdmission.DROP_NEEDS_AUTH
    state.hasActiveReconnectJob -> ReconnectAdmission.JOIN_RUNNING
    !state.networkAvailable -> ReconnectAdmission.PARK_OFFLINE
    else -> ReconnectAdmission.ACCEPT
}

/**
 * 下一次重连尝试的计划。
 *
 * @param attempt 展示用的尝试序号（1..maxAttempts）
 * @param attemptsAfter 执行完本次计划后 [McpSession.reconnectAttempt] 应该记成几
 * @param budgetExhausted 本轮快速预算已用完，转等待网络事件或下一次调用（不是永久停死）
 * @param deferred 离线，本次不消耗预算也不真正连接
 */
internal data class ReconnectPlan(
    val attempt: Int,
    val attemptsAfter: Int,
    val delayMillis: Long,
    val budgetExhausted: Boolean,
    val deferred: Boolean,
)

/**
 * 有界的重连节奏：
 * - 普通失败：1s / 2s / 4s / 8s / 16s，封顶 30s，最多 [maxAttempts] 次；
 * - 离线：不消耗预算（deferred），等网络恢复；
 * - 预算耗尽：不永久停死，进入等待网络事件或下一次调用，网络恢复会重置预算立刻再试。
 */
internal fun planReconnectAttempt(
    previousAttempts: Int,
    networkAvailable: Boolean,
    maxAttempts: Int = MAX_RECONNECT_ATTEMPTS,
    baseDelayMillis: Long = BASE_RECONNECT_DELAY_MS,
    maxDelayMillis: Long = MAX_RECONNECT_DELAY_MS,
): ReconnectPlan {
    val attempts = previousAttempts.coerceAtLeast(0)
    if (!networkAvailable) {
        return ReconnectPlan(
            attempt = attempts.coerceAtMost(maxAttempts),
            attemptsAfter = attempts,
            delayMillis = 0L,
            budgetExhausted = false,
            deferred = true,
        )
    }
    if (attempts >= maxAttempts) {
        // 等待网络事件或下一次调用：保持 attempts 不回落，避免「慢速失败后又白送一轮快速重试」
        return ReconnectPlan(
            attempt = maxAttempts,
            attemptsAfter = maxAttempts,
            delayMillis = 0L,
            budgetExhausted = true,
            deferred = true,
        )
    }
    val attempt = attempts + 1
    val exponential = baseDelayMillis * (1L shl (attempt - 1).coerceAtMost(10))
    return ReconnectPlan(
        attempt = attempt,
        attemptsAfter = attempt,
        delayMillis = exponential.coerceAtMost(maxDelayMillis),
        budgetExhausted = false,
        deferred = false,
    )
}

/**
 * 工具调用失败后的处理计划。
 *
 * [replayToolCall] 恒为 false：已发出的工具调用可能已经写库/扣款，重放会重复产生副作用。
 */
internal data class ToolCallFailurePlan(
    val markClientUnhealthy: Boolean,
    val scheduleReconnect: Boolean,
    val markNeedsAuthorization: Boolean,
    val outcomeUnknown: Boolean,
    val replayToolCall: Boolean = false,
)

internal fun planToolCallFailure(
    sent: Boolean,
    needsAuthorization: Boolean,
    transportFailure: Boolean,
    networkAvailable: Boolean,
): ToolCallFailurePlan {
    if (needsAuthorization) {
        // 401：交给用户授权，既不自动重连也不重放
        return ToolCallFailurePlan(
            markClientUnhealthy = false,
            scheduleReconnect = false,
            markNeedsAuthorization = true,
            outcomeUnknown = false,
        )
    }
    if (!transportFailure) {
        // 服务器已给出明确错误（参数非法 / 工具内部异常），重连解决不了问题
        return ToolCallFailurePlan(
            markClientUnhealthy = false,
            scheduleReconnect = false,
            markNeedsAuthorization = false,
            outcomeUnknown = false,
        )
    }
    return ToolCallFailurePlan(
        markClientUnhealthy = true,
        // 离线时不空转重连，等网络恢复通知
        scheduleReconnect = networkAvailable,
        markNeedsAuthorization = false,
        // 只有「已发出」的调用才存在结果不确定的问题
        outcomeUnknown = sent,
    )
}

private val TRANSPORT_FAILURE_CLASS_NAMES = setOf(
    "SocketTimeoutException",
    "ConnectException",
    "UnknownHostException",
    "SSLException",
    "SSLHandshakeException",
    "SocketException",
    "EOFException",
    "ClosedChannelException",
    "AsynchronousCloseException",
    "InterruptedIOException",
    "ChannelException",
)

/**
 * 判断异常是否属于「传输层断了」。
 *
 * 包括 SSE 重连彻底放弃（`Maximum reconnection attempts exceeded`）：这正是 r2 里被刻意忽略、
 * 导致会话卡在 Connected 的那一类，必须交接给 registry 重连。
 */
internal fun isTransportFailure(error: Throwable): Boolean {
    val classNames = TRANSPORT_FAILURE_CLASS_NAMES
    return generateSequence(error) { it.cause }.any { cause ->
        cause is IOException ||
            cause.javaClass.simpleName in classNames ||
            cause.message.orEmpty().let { message ->
                message.contains("Maximum reconnection attempts exceeded", ignoreCase = true) ||
                    message.contains("connection reset", ignoreCase = true) ||
                    message.contains("connection closed", ignoreCase = true) ||
                    message.contains("connection refused", ignoreCase = true) ||
                    message.contains("socket closed", ignoreCase = true) ||
                    message.contains("stream closed", ignoreCase = true) ||
                    message.contains("broken pipe", ignoreCase = true) ||
                    message.contains("unexpected end of stream", ignoreCase = true) ||
                    message.contains("timed out", ignoreCase = true) ||
                    message.contains("network is unreachable", ignoreCase = true) ||
                    message.contains("unable to resolve host", ignoreCase = true)
            }
    }
}

/**
 * MCP 连接运行时注册表。
 *
 * 每个 serverId 对应一个 [McpSession]，该 Session 的连接、同步、关闭和重连通过同一把 Mutex 串行执行，
 * 同一时刻最多只有一个合并后的重连 job。Client 只有在 connect 与首次工具同步都成功后才对外可见。
 */
internal class McpSessionRegistry(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val httpClient: HttpClient,
    private val oauthCoordinator: McpOAuthCoordinator,
    private val statusStore: McpStatusStore,
) {
    private val sessions = ConcurrentHashMap<Uuid, McpSession>()

    /** 由 [McpManager] 的网络监听驱动；未知时保守认为在线，交给有界 backoff 兜底。 */
    @Volatile
    private var networkAvailable: Boolean = true

    fun getClient(configId: Uuid): Client? = sessions[configId]?.healthyClient

    fun getStatus(configId: Uuid): Flow<McpStatus> = statusStore.get(configId)

    fun reconcile(configs: List<McpServerConfig>) {
        val activeConfigs = configs
            .filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
            .associateBy { it.id }

        (sessions.keys - activeConfigs.keys).forEach { configId ->
            val detached = sessions.remove(configId) ?: return@forEach
            oauthCoordinator.forget(configId)
            statusStore.remove(configId)
            detached.connectJob?.cancel()
            detached.reconnectJob?.cancel()
            appScope.launch { closeSession(detached) }
        }

        activeConfigs.values.forEach { newConfig ->
            val existing = sessions[newConfig.id]
            if (existing == null) {
                val session = McpSession(newConfig)
                if (sessions.putIfAbsent(newConfig.id, session) == null) {
                    // addClient 内部会把首次失败也接进有界退避，不会停在 Error 里不动
                    appScope.launch { addClient(newConfig) }
                }
                return@forEach
            }

            val mustReconnect = !hasSameConnectionParameters(existing.config, newConfig)
            existing.config = newConfig
            if (mustReconnect) {
                appScope.launch { addClient(newConfig) }
            }
        }
    }

    /**
     * 网络恢复（epoch 变化且可用）后由 [McpManager] 调用。
     *
     * 每个 server 只开一个合并重连：预算清零、等待网络事件或下一次调用任务被立刻替换，
     * 已删除 / 已禁用 / 401 的服务器不会被复活。
     */
    fun onNetworkAvailable() {
        networkAvailable = true
        sessions.keys.toList().forEach { configId ->
            val session = sessions[configId]
            // Loopback services retain their route through VPN changes. Only repair one if it is already unhealthy.
            if (session != null && (!session.config.isLoopbackEndpoint() || !session.healthy)) {
                session.connectJob?.cancel()
                requestReconnect(configId, sourceClient = null, resetBudget = true)
            }
        }
    }

    /** 网络掉线：只做标记，避免重连任务空转把 5 次预算烧完。 */
    fun onNetworkUnavailable() {
        networkAvailable = false
        sessions.values.forEach {
            if (!it.config.isLoopbackEndpoint()) {
                it.healthy = false
                // Cancel without waiting for the lifecycle mutex held by connect/listTools.
                it.connectJob?.cancel()
                it.reconnectJob?.cancel()
            }
        }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): CallToolResult {
        val session = sessions[serverId]
            ?: throw McpClientUnavailableException("No MCP session for server $serverId")
        val freshConfig = oauthCoordinator.ensureFreshToken(session.config)
        if (!hasSameConnectionParameters(session.connectedConfig, freshConfig)) {
            session.config = freshConfig
            addClient(freshConfig)
        }

        // 尚未发送：允许短暂、可取消地等一次合并重连，等不到就明确报「没发出去」
        val sdkClient = session.healthyClient ?: awaitReconnectedClient(serverId, session)
        val config = session.connectedConfig ?: session.config
        if (sdkClient == null) {
            val plan = planToolCallFailure(
                sent = false,
                needsAuthorization = false,
                transportFailure = true,
                networkAvailable = networkAvailable,
            )
            if (plan.scheduleReconnect) requestReconnect(serverId, sourceClient = null)
            throw McpClientUnavailableException(
                "MCP server '${config.commonOptions.name}' is not connected. " +
                    "Tool '$toolName' was NOT sent and nothing was executed on the server."
            )
        }

        if (session.healthyClient !== sdkClient) {
            throw McpClientUnavailableException("Connection changed before sending '$toolName'; the call was NOT sent")
        }
        Log.i(TAG, "Calling tool $toolName on $serverId (${config.commonOptions.name})")
        return try {
            sdkClient.callTool(
                request = CallToolRequest(
                    params = CallToolRequestParams(name = toolName, arguments = args),
                ),
                options = RequestOptions(timeout = 120.seconds),
            )
        } catch (e: CancellationException) {
            // Only parent/user cancellation stops the agent. The SDK can cancel an outstanding
            // response when its transport closes, even though our caller remains active.
            if (!currentCoroutineContext().isActive) throw e
            Log.e(TAG, "Tool $toolName on $serverId timed out after being sent", e)
            throw outcomeUnknown(config, sdkClient, toolName, e)
        } catch (e: Exception) {
            val needsAuthorization = oauthCoordinator.needsAuthorization(config, e)
            val transportFailure = !needsAuthorization && isTransportFailure(e)
            val plan = planToolCallFailure(
                sent = true,
                needsAuthorization = needsAuthorization,
                transportFailure = transportFailure,
                networkAvailable = networkAvailable,
            )
            if (plan.markNeedsAuthorization) {
                markClientUnhealthy(config.id, sdkClient)
                statusStore.update(config.id, McpStatus.NeedsAuthorization)
            }
            if (plan.outcomeUnknown) {
                Log.e(TAG, "Tool $toolName on $serverId lost its result", e)
                throw outcomeUnknown(config, sdkClient, toolName, e)
            }
            throw e
        }
    }

    suspend fun addClient(configInput: McpServerConfig) {
        // SettingsStore 是配置真源。旧任务排队后可能晚于新配置执行，不能再写回旧快照。
        val desiredConfig = settingsStore.settingsFlow.value.mcpServers.find { it.id == configInput.id }
        if (desiredConfig == null) {
            removeClient(configInput)
            return
        }
        if (!desiredConfig.commonOptions.enable || desiredConfig.commonOptions.name.isBlank()) {
            removeClient(desiredConfig)
            return
        }

        val session = sessions.computeIfAbsent(desiredConfig.id) { McpSession(desiredConfig) }
        session.config = desiredConfig
        if (!networkAvailable && !desiredConfig.isLoopbackEndpoint()) {
            session.healthy = false
            statusStore.update(desiredConfig.id, McpStatus.Error(OFFLINE_MESSAGE))
            return
        }
        val result = try {
            connectSession(
                session = session,
                requestedConfig = desiredConfig,
                cancelPendingReconnect = true,
                forceReconnect = false,
            )
        } catch (cancelled: CancellationException) {
            // Route observer can cancel only the initialization child. Do not cancel an active caller.
            currentCoroutineContext().ensureActive()
            ConnectResult.Failed
        }
        if (result == ConnectResult.Failed) {
            // r2 bug：首次连接失败就被丢在 Error 里不再重排，只能等用户手动点。
            requestReconnect(desiredConfig.id, sourceClient = null)
        }
    }

    suspend fun removeClient(config: McpServerConfig) {
        val session = sessions.remove(config.id)
        oauthCoordinator.forget(config.id)
        if (session != null) {
            session.connectJob?.cancel()
            session.reconnectJob?.cancel()
            closeSession(session)
        }
        statusStore.remove(config.id)
    }

    suspend fun syncAll() {
        sessions.values.toList().forEach { session -> syncSession(session) }
    }

    private suspend fun connectSession(
        session: McpSession,
        requestedConfig: McpServerConfig,
        cancelPendingReconnect: Boolean,
        forceReconnect: Boolean,
    ): ConnectResult = withContext(Dispatchers.IO) {
        session.lifecycleMutex.withLock {
            if (sessions[requestedConfig.id] !== session) return@withLock ConnectResult.Stale
            if (!hasSameConnectionParameters(session.config, requestedConfig)) {
                return@withLock ConnectResult.Stale
            }

            if (cancelPendingReconnect) {
                session.reconnectJob?.cancel()
                session.reconnectJob = null
                session.reconnectAttempt = 0
            }

            val config = oauthCoordinator.ensureFreshToken(session.config)
            session.config = config
            if (!forceReconnect &&
                session.healthy &&
                session.client != null &&
                hasSameConnectionParameters(session.connectedConfig, config)
            ) {
                return@withLock ConnectResult.Success
            }

            statusStore.update(config.id, McpStatus.Connecting)
            val oldClient = session.client
            // 旧 client 从这一刻起不再被视为健康，直到新 client 连上并同步完工具
            session.healthy = false
            session.client = null
            session.connectedConfig = null
            oldClient?.let { closeClient(it, config.commonOptions.name) }

            val sdkClient = createSdkClient(config)
            val transport = createTransport(config)
            installTransportCallbacks(config, sdkClient, transport)
            // 先挂上引用，好让 transport 回调能认出「死掉的是不是我现在这个」；
            // healthy 仍然是 false，同步完成前对调用方不可见。
            session.client = sdkClient
            session.healthy = false

            val connectingJob = currentCoroutineContext().job
            session.connectJob = connectingJob
            try {
                val syncedConfig = withTimeoutOrNull(20_000L) {
                    sdkClient.connect(transport)
                    syncTools(session, sdkClient, config)
                } ?: throw IOException("MCP initialization timed out")
                if (sessions[config.id] !== session ||
                    !hasSameConnectionParameters(config, syncedConfig)
                ) {
                    session.client = null
                    closeClient(sdkClient, config.commonOptions.name)
                    return@withLock ConnectResult.Stale
                }

                session.config = syncedConfig
                session.connectedConfig = syncedConfig
                session.client = sdkClient
                session.healthy = true
                session.connectedAtNanos = System.nanoTime()
                statusStore.update(config.id, McpStatus.Connected)
                Log.i(TAG, "Connected MCP server ${config.id} (${config.commonOptions.name})")
                ConnectResult.Success
            } catch (e: CancellationException) {
                session.client = null
                session.connectedConfig = null
                closeClient(sdkClient, config.commonOptions.name)
                throw e
            } catch (e: Exception) {
                session.client = null
                session.connectedConfig = null
                closeClient(sdkClient, config.commonOptions.name)
                Log.e(TAG, "Failed to connect MCP server ${config.id}", e)
                if (oauthCoordinator.needsAuthorization(config, e)) {
                    statusStore.update(config.id, McpStatus.NeedsAuthorization)
                    ConnectResult.NeedsAuthorization
                } else {
                    statusStore.update(config.id, McpStatus.Error.from(e))
                    ConnectResult.Failed
                }
            } finally {
                if (session.connectJob === connectingJob) session.connectJob = null
            }
        }
    }

    private suspend fun syncSession(session: McpSession) {
        val config = session.config
        if (session.client == null) {
            addClient(config)
            return
        }

        var reconnectConfig: McpServerConfig? = null
        var reconnectAfterTransportFailure = false
        withContext(Dispatchers.IO) {
            session.lifecycleMutex.withLock {
                val sdkClient = session.client ?: return@withLock
                val connectedConfig = session.connectedConfig ?: return@withLock
                statusStore.update(config.id, McpStatus.Connecting)
                try {
                    val syncedConfig = withTimeoutOrNull(20_000L) {
                        syncTools(session, sdkClient, session.config)
                    } ?: throw IOException("MCP tool-list synchronization timed out")
                    session.config = syncedConfig
                    if (hasSameConnectionParameters(connectedConfig, syncedConfig)) {
                        session.connectedConfig = syncedConfig
                        statusStore.update(config.id, McpStatus.Connected)
                    } else {
                        reconnectConfig = syncedConfig
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (oauthCoordinator.needsAuthorization(config, e)) {
                        statusStore.update(config.id, McpStatus.NeedsAuthorization)
                    } else {
                        statusStore.update(config.id, McpStatus.Error.from(e))
                        if (isTransportFailure(e)) {
                            session.healthy = false
                            reconnectAfterTransportFailure = true
                        }
                    }
                }
            }
        }
        if (reconnectAfterTransportFailure) {
            requestReconnect(config.id, sourceClient = null)
            return
        }
        reconnectConfig?.let { addClient(it) }
    }

    private suspend fun syncTools(
        session: McpSession,
        sdkClient: Client,
        connectionConfig: McpServerConfig,
    ): McpServerConfig {
        val serverTools = sdkClient.listTools().tools
        Log.i(TAG, "Synced ${serverTools.size} tools from ${connectionConfig.id}")
        var updatedConfig = connectionConfig
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { storedConfig ->
                    if (storedConfig.id != connectionConfig.id) return@map storedConfig
                    val tools = mergeTools(storedConfig.commonOptions.tools, serverTools)
                    storedConfig.clone(commonOptions = storedConfig.commonOptions.copy(tools = tools))
                        .also { updatedConfig = it }
                }
            )
        }
        session.config = updatedConfig
        return updatedConfig
    }

    private fun installTransportCallbacks(
        config: McpServerConfig,
        sdkClient: Client,
        transport: AbstractTransport,
    ) {
        transport.onClose {
            Log.i(TAG, "Transport closed for ${config.id} (${config.commonOptions.name})")
            markClientUnhealthy(config.id, sdkClient)
            requestReconnect(config.id, sdkClient)
        }
        transport.onError { error ->
            Log.e(TAG, "Transport error for ${config.id}: ${error.message}")
            // 包括 SSE「Maximum reconnection attempts exceeded」：传输层自己放弃了，
            // 正好是 registry 该接手重连的时候，不能像 r2 那样刻意忽略。
            markClientUnhealthy(config.id, sdkClient)
            requestReconnect(config.id, sdkClient)
        }
    }

    /**
     * 合并重复的 onError/onClose 通知：同一 server 复用一个 reconnectJob，不会产生重连风暴。
     */
    private fun requestReconnect(
        configId: Uuid,
        sourceClient: Client?,
        resetBudget: Boolean = false,
    ) {
        appScope.launch {
            val session = sessions[configId] ?: return@launch
            session.lifecycleMutex.withLock {
                if (sessions[configId] !== session) return@withLock
                // 旧 client 的迟到通知：当前 client 已经换过了，直接丢弃
                if (sourceClient != null && session.client !== sourceClient) return@withLock
                if (!resetBudget && session.healthyClient != null) return@withLock
                if (session.connectedAtNanos != 0L && System.nanoTime() - session.connectedAtNanos >= 60_000_000_000L) {
                    session.reconnectAttempt = 0
                    session.connectedAtNanos = 0L
                }

                if (resetBudget) {
                    session.reconnectPending = false
                    session.reconnectAttempt = 0
                    // 网络恢复：把还在等长退避（含等待网络事件或下一次调用）的任务换成立刻重试，槽位还是同一个
                    session.reconnectJob?.cancel()
                    session.reconnectJob = null
                }

                val desiredConfig = settingsStore.settingsFlow.value.mcpServers.find { it.id == configId }
                val admission = admitReconnect(
                    ReconnectGateState(
                        serverExists = desiredConfig != null,
                        enabled = desiredConfig?.commonOptions?.enable == true,
                        nameValid = desiredConfig?.commonOptions?.name?.isNotBlank() == true,
                        needsAuthorization = statusStore.status.value[configId] == McpStatus.NeedsAuthorization,
                        hasActiveReconnectJob = session.reconnectJob?.isActive == true,
                        networkAvailable = networkAvailable || desiredConfig?.isLoopbackEndpoint() == true,
                    )
                )
                when (admission) {
                    ReconnectAdmission.ACCEPT -> Unit
                    ReconnectAdmission.JOIN_RUNNING -> {
                        // 合并进现有 job；但如果这次通知说明当前 client 已经死了，
                        // 等这个 job 收尾后要再补一次（否则新 client 一上来就是死的却没人管）
                        if (sourceClient != null) session.reconnectPending = true
                        return@withLock
                    }

                    ReconnectAdmission.DROP_DELETED,
                    ReconnectAdmission.DROP_DISABLED,
                    ReconnectAdmission.DROP_NEEDS_AUTH -> return@withLock

                    ReconnectAdmission.PARK_OFFLINE -> {
                        // 离线：旧连接肯定不可用了，但不要空转消耗预算
                        session.healthy = false
                        statusStore.update(configId, McpStatus.Error(OFFLINE_MESSAGE))
                        return@withLock
                    }
                }

                // 旧 client 在真正换新前不得被视为健康
                session.healthy = false
                val previousAttempts = session.reconnectAttempt
                val plan = planReconnectAttempt(
                    previousAttempts = previousAttempts,
                    networkAvailable = networkAvailable || desiredConfig?.isLoopbackEndpoint() == true,
                )
                if (plan.deferred) {
                    statusStore.update(configId, McpStatus.Error(
                        if (plan.budgetExhausted) "连接断开；网络恢复或下次调用时重新连接" else OFFLINE_MESSAGE
                    ))
                    return@withLock
                }
                session.reconnectAttempt = plan.attemptsAfter
                statusStore.update(
                    configId,
                    if (plan.budgetExhausted) {
                        McpStatus.Error("连接断开，已达最大重连次数，稍后自动重试（网络恢复会立刻重试）")
                    } else {
                        McpStatus.Reconnecting(plan.attempt, MAX_RECONNECT_ATTEMPTS)
                    },
                )
                session.reconnectJob = appScope.launch {
                    reconnectAfterDelay(session, if (resetBudget) plan.copy(delayMillis = 0L) else plan, rollbackToAttempts = previousAttempts)
                }
            }
        }
    }

    private suspend fun reconnectAfterDelay(
        session: McpSession,
        plan: ReconnectPlan,
        rollbackToAttempts: Int,
    ) {
        val runningJob = currentCoroutineContext().job
        var retry = false
        var rollbackBudget = false
        var pendingFollowUp = false
        try {
            if (plan.delayMillis > 0L) delay(plan.delayMillis)
            val configId = session.config.id
            if (!networkAvailable && !session.config.isLoopbackEndpoint()) {
                // 退避期间掉线：这次不算数，预算回滚，等 onNetworkAvailable 重置后再试
                rollbackBudget = true
                statusStore.update(session.config.id, McpStatus.Error(OFFLINE_MESSAGE))
                return
            }
            val latestConfig = settingsStore.settingsFlow.value.mcpServers.find {
                it.id == configId &&
                    it.commonOptions.enable &&
                    it.commonOptions.name.isNotBlank()
            } ?: return
            session.config = latestConfig
            retry = connectSession(
                session = session,
                requestedConfig = latestConfig,
                cancelPendingReconnect = false,
                forceReconnect = true,
            ) == ConnectResult.Failed
        } catch (e: CancellationException) {
            throw e
        } finally {
            withContext(NonCancellable) {
                session.lifecycleMutex.withLock {
                    if (rollbackBudget && session.reconnectJob === runningJob) {
                        session.reconnectAttempt = rollbackToAttempts
                    }
                    if (session.reconnectJob === runningJob) {
                        session.reconnectJob = null
                        if (session.reconnectPending) {
                            session.reconnectPending = false
                            pendingFollowUp = true
                        }
                    }
                }
            }
        }

        if (retry || pendingFollowUp) requestReconnect(session.config.id, sourceClient = null)
    }

    private suspend fun closeSession(session: McpSession) = withContext(Dispatchers.IO) {
        session.lifecycleMutex.withLock {
            session.reconnectJob?.cancel()
            session.reconnectJob = null
            session.reconnectAttempt = 0
            session.reconnectPending = false
            val sdkClient = session.client
            session.healthy = false
            session.client = null
            session.connectedConfig = null
            sdkClient?.let { closeClient(it, session.config.commonOptions.name) }
        }
    }

    private suspend fun closeClient(client: Client, serverName: String) {
        runCatching {
            withContext(NonCancellable) { withTimeoutOrNull(3_000L) { client.close() } }
        }.onFailure { Log.w(TAG, "Failed to close MCP client $serverName", it) }
    }

    /**
     * 未发送前的短暂等待：只等已有的那一个合并重连（<= 15s，可取消），不自己再开一个新连接。
     */
    private suspend fun awaitReconnectedClient(serverId: Uuid, session: McpSession): Client? {
        session.healthyClient?.let { return it }
        requestReconnect(serverId, sourceClient = null, resetBudget = session.reconnectJob?.isActive != true)
        return withTimeoutOrNull(TOOL_CALL_RECONNECT_WAIT_MS) {
            var waited = 0L
            var client = session.healthyClient
            while (client == null && waited < TOOL_CALL_RECONNECT_WAIT_MS) {
                delay(TOOL_CALL_RECONNECT_POLL_MS)
                waited += TOOL_CALL_RECONNECT_POLL_MS
                client = session.healthyClient
                if (client == null) {
                    val status = statusStore.status.value[serverId]
                    val stillTrying = session.reconnectJob?.isActive == true ||
                        status is McpStatus.Reconnecting ||
                        status is McpStatus.Connecting
                    if (!stillTrying) break
                }
            }
            client
        }
    }

    /** 结果不确定的失败：标记不可用 + 安排重连，但绝不重放这次调用。 */
    private fun outcomeUnknown(
        config: McpServerConfig,
        sdkClient: Client,
        toolName: String,
        cause: Throwable,
    ): McpToolCallOutcomeUnknownException {
        val plan = planToolCallFailure(
            sent = true,
            needsAuthorization = false,
            transportFailure = true,
            networkAvailable = networkAvailable,
        )
        if (plan.markClientUnhealthy) markClientUnhealthy(config.id, sdkClient)
        if (plan.scheduleReconnect) requestReconnect(config.id, sourceClient = sdkClient)
        check(!plan.replayToolCall) {
            "An already sent MCP tool call must never be replayed"
        }
        return McpToolCallOutcomeUnknownException(
            buildString {
                append("MCP tool '")
                append(toolName)
                append("' on '")
                append(config.commonOptions.name)
                append("' was already sent, but the connection failed before a result arrived (")
                append(cause.javaClass.simpleName)
                cause.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
                append("). The outcome is UNKNOWN: the server may or may not have executed it. ")
                append("It was NOT retried automatically to avoid duplicate side effects ")
                append("(double writes / double charges). Verify the server state before calling it again.")
            },
            cause,
        )
    }

    private fun markClientUnhealthy(configId: Uuid, sourceClient: Client) {
        val session = sessions[configId] ?: return
        if (session.client === sourceClient) session.healthy = false
    }

    private fun createSdkClient(config: McpServerConfig): Client = Client(
        clientInfo = Implementation(name = config.commonOptions.name, version = "1.0")
    )

    private fun createTransport(config: McpServerConfig): AbstractTransport = when (config) {
        is McpServerConfig.SseTransportServer -> SseClientTransport(
            urlString = config.url,
            client = httpClient,
            requestBuilder = { appendResolvedHeaders(config) },
        )

        is McpServerConfig.StreamableHTTPServer -> StreamableHttpClientTransport(
            url = config.url,
            client = httpClient,
            requestBuilder = { appendResolvedHeaders(config) },
        )
    }

    private fun HttpRequestBuilder.appendResolvedHeaders(config: McpServerConfig) {
        headers.appendAll(StringValues.build {
            config.resolvedHeaders().forEach { (name, value) -> append(name, value) }
        })
    }

    private companion object {
        const val OFFLINE_MESSAGE = "网络不可用，等待网络恢复后重连"
    }
}

/** 只包含会影响实际连接的字段；工具开关和 Schema 变化不会触发重连。 */
internal data class McpConnectionKey(
    val transportType: String,
    val serverUrl: String,
    val clientName: String,
    val headers: List<Pair<String, String>>,
)

internal fun McpServerConfig.connectionKey(): McpConnectionKey = McpConnectionKey(
    transportType = when (this) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    },
    serverUrl = serverUrl,
    clientName = commonOptions.name,
    headers = resolvedHeaders(),
)

internal fun hasSameConnectionParameters(
    left: McpServerConfig?,
    right: McpServerConfig?,
): Boolean = left != null && right != null && left.connectionKey() == right.connectionKey()

private fun McpServerConfig.resolvedHeaders(): List<Pair<String, String>> {
    // 设置页“添加请求头”后未填写会留下空名称，OkHttp 会直接抛出 "name is empty"
    val base = commonOptions.headers.filter { it.first.isNotBlank() }
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthorization = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthorization) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}

private fun mergeTools(storedTools: List<McpTool>, serverTools: List<Tool>): List<McpTool> {
    val toolsByName = storedTools.associateBy { it.name }
    return serverTools.map { serverTool ->
        toolsByName[serverTool.name]?.copy(
            description = serverTool.description,
            inputSchema = serverTool.inputSchema.toSchema(),
            readOnlyHint = serverTool.annotations?.readOnlyHint,
            idempotentHint = serverTool.annotations?.idempotentHint,
        ) ?: McpTool(
            name = serverTool.name,
            description = serverTool.description,
            enable = true,
            inputSchema = serverTool.inputSchema.toSchema(),
            readOnlyHint = serverTool.annotations?.readOnlyHint,
            idempotentHint = serverTool.annotations?.idempotentHint,
        )
    }
}

private fun McpServerConfig.isLoopbackEndpoint(): Boolean {
    val host = serverUrl.toHttpUrlOrNull()?.host ?: return false
    return host == "localhost" || host == "::1" || host.matches(Regex("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))
}
