package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.NetworkHandover
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * 网络换代的稳定期：手机刚切网时 DNS/路由还没就绪，等一下再重连，避免连上就断。
 *
 * 和 [NetworkHandover.STABILIZE_MILLIS] 保持一致：用户要的是换网后立刻恢复，
 * 这里不再单独留一个更长的 750ms。只恢复「连接」，在途工具调用既不取消也不重放。
 */
internal const val NETWORK_RECOVERY_DEBOUNCE_MS: Long = NetworkHandover.STABILIZE_MILLIS

/** [NetworkRecoveryCoordinator] 对一条网络状态回调给出的动作。 */
internal sealed interface NetworkRecoveryDirective {
    /** 什么都不做（基线首报、或完全重复的回调）。 */
    data object None : NetworkRecoveryDirective

    /** 当前不可用：只标记离线，绝不触发重连。 */
    data object MarkOffline : NetworkRecoveryDirective

    /** 代际已变化且可用：调用方（重新）启动唯一的 debounce 定时器。 */
    data class RestartDebounce(val epoch: Long) : NetworkRecoveryDirective
}

/**
 * 把 [NetworkHandover] 的原始状态回调收敛成「要不要恢复」的纯决策。
 *
 * - 首条状态只当基线：初始化时不能误伤已经连上的会话；
 * - 完全相同的重复回调直接丢弃：重复通知不会叠加成重连风暴；
 * - 离线只标记不恢复：离线期间不许空转消耗重连预算；
 * - 只有代际变化且可用才恢复，且 debounce 期间又来新 epoch 时只保留最新一次。
 */
internal class NetworkRecoveryCoordinator {
    private var hasBaseline = false
    private var lastEpoch: Long? = null
    private var lastAvailable = false
    private var pendingEpoch: Long? = null

    @Synchronized
    fun onRouteState(epoch: Long, available: Boolean): NetworkRecoveryDirective {
        if (!hasBaseline) {
            hasBaseline = true
            lastEpoch = epoch
            lastAvailable = available
            pendingEpoch = null
            return if (available) NetworkRecoveryDirective.None else NetworkRecoveryDirective.MarkOffline
        }
        if (epoch == lastEpoch && available == lastAvailable) {
            return NetworkRecoveryDirective.None
        }
        lastEpoch = epoch
        lastAvailable = available
        if (!available) {
            // 掉线：作废还在 debounce 的恢复，等真正恢复再算
            pendingEpoch = null
            return NetworkRecoveryDirective.MarkOffline
        }
        pendingEpoch = epoch
        return NetworkRecoveryDirective.RestartDebounce(epoch)
    }

    /** debounce 到点：只有 epoch 仍是最新的才真正恢复，且同一次恢复只会被消费一次。 */
    @Synchronized
    fun onDebounceElapsed(epoch: Long): Boolean {
        if (pendingEpoch != epoch) return false
        pendingEpoch = null
        return true
    }

    @Synchronized
    fun isAvailable(): Boolean = lastAvailable
}

/**
 * MCP 子系统的公共入口。
 *
 * 这里仅协调配置、OAuth、连接注册表与 UI 内容转换；单个服务器的连接状态机由
 * [McpSessionRegistry] 管理，OAuth 协议细节由 [McpOAuthCoordinator] 管理。
 *
 * @param networkHandover 默认网络的代际信号。为 null 时不监听网络，重连退化为纯有界 backoff；
 * 非 null 时代际变化会清空 idle 连接池并让注册表按 server 合并重连一次。
 */
class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
    private val networkHandover: NetworkHandover? = null,
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Tool POSTs can have side effects: recovery must create a new session, never replay a sent POST.
        .retryOnConnectionFailure(false)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val httpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE)
    }

    private val statusStore = McpStatusStore()
    private val oauthCallbackServer = OAuthLoopbackCallbackServer(
        port = MCP_OAUTH_CALLBACK_PORT,
        callbackPath = MCP_OAUTH_CALLBACK_PATH,
    )
    private val oauthCoordinator = McpOAuthCoordinator(
        settingsStore = settingsStore,
        appScope = appScope,
        oauthClient = OAuthHttpClient(okHttpClient),
        discoveryClient = McpOAuthDiscoveryClient(okHttpClient),
        callbackServer = oauthCallbackServer,
        authorizationLauncher = CustomTabsOAuthAuthorizationLauncher,
        updateStatus = statusStore::update,
    )
    private val sessionRegistry = McpSessionRegistry(
        settingsStore = settingsStore,
        appScope = appScope,
        httpClient = httpClient,
        oauthCoordinator = oauthCoordinator,
        statusStore = statusStore,
    )
    private val networkRecovery = NetworkRecoveryCoordinator()

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .distinctUntilChanged()
                .collect(sessionRegistry::reconcile)
        }
        networkHandover?.let { handover ->
            appScope.launch { observeNetworkHandover(handover) }
        }
    }

    val syncingStatus: StateFlow<Map<Uuid, McpStatus>>
        get() = statusStore.status

    fun getClient(config: McpServerConfig): Client? = sessionRegistry.getClient(config.id)

    fun getStatus(config: McpServerConfig): Flow<McpStatus> = sessionRegistry.getStatus(config.id)

    fun getAllAvailableTools(assistant: Assistant): List<Triple<Uuid, String, McpTool>> {
        val settings = settingsStore.settingsFlow.value
        return settings.mcpServers
            // Cached tool definitions from a failed server are stale and must not enter the prompt.
            .filter { it.commonOptions.enable && it.id in assistant.mcpServers && sessionRegistry.getClient(it.id) != null }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { tool -> tool.enable }
                    .map { tool -> Triple(server.id, server.commonOptions.name, tool) }
            }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): List<UIMessagePart> {
        val result = try {
            sessionRegistry.callTool(serverId, toolName, args)
        } catch (e: McpToolCallOutcomeUnknownException) {
            // 已经发出去了，结果不确定：原样告诉模型「别自动重试」，绝不重放
            return listOf(UIMessagePart.Text("MCP tool call outcome unknown: ${e.message}"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpClientUnavailableException) {
            return listOf(UIMessagePart.Text("Failed to execute MCP tool: ${e.message ?: e.javaClass.name}"))
        }
        return result.content.map { content ->
            when (content) {
                is TextContent -> UIMessagePart.Text(content.text)
                is ImageContent -> convertImageContentToFilePart(content)
                else -> UIMessagePart.Text(JsonInstant.encodeToString(content))
            }
        }
    }

    suspend fun addClient(config: McpServerConfig) = sessionRegistry.addClient(config)

    suspend fun removeClient(config: McpServerConfig) = sessionRegistry.removeClient(config)

    suspend fun syncAll() = sessionRegistry.syncAll()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        oauthCoordinator.startAuthorization(config, context)
    }

    fun cancelAuthorization(config: McpServerConfig) {
        oauthCoordinator.cancelAuthorization(config.id)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        val freshConfig = oauthCoordinator.clearAuthorization(config)
        sessionRegistry.addClient(freshConfig)
    }

    /**
     * 监听默认网络的代际信号。
     *
     * 只有「代际变化 + 可用」才恢复；同一时刻只保留一个 250ms debounce 任务，
     * 重复回调 / 连续 flap 只会留下最新一次。工具 POST 不会因换网被重放。
     */
    private suspend fun observeNetworkHandover(handover: NetworkHandover) {
        var debounceJob: Job? = null
        handover.state.collect { route ->
            when (val directive = networkRecovery.onRouteState(route.epoch, route.available)) {
                NetworkRecoveryDirective.None -> Unit

                NetworkRecoveryDirective.MarkOffline -> {
                    debounceJob?.cancel()
                    debounceJob = null
                    sessionRegistry.onNetworkUnavailable()
                }

                is NetworkRecoveryDirective.RestartDebounce -> {
                    debounceJob?.cancel()
                    debounceJob = appScope.launch {
                        delay(NETWORK_RECOVERY_DEBOUNCE_MS)
                        // epoch 已经不是最新的（或中途掉线）就让位给新的 debounce
                        if (!networkRecovery.onDebounceElapsed(directive.epoch)) return@launch
                        // 旧网络的 idle 连接不能带到新网络上
                        okHttpClient.connectionPool.evictAll()
                        sessionRegistry.onNetworkAvailable()
                    }
                }
            }
        }
    }

    private suspend fun convertImageContentToFilePart(image: ImageContent): UIMessagePart.Image {
        val bytes = Base64.decode(image.data)
        val extension = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(image.mimeType) ?: "bin"
        val entity = filesManager.saveUploadFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$extension",
            mimeType = image.mimeType,
        )
        return UIMessagePart.Image(url = filesManager.getFile(entity).toUri().toString())
    }
}
