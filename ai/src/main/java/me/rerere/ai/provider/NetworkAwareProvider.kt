package me.rerere.ai.provider

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.http.NetworkHandover
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 当前没有可用的默认网络，请求**没有**发出去。
 *
 * 单独一个类型是为了让重试策略区分「根本没发」和「失败了」：前者不该消耗重试次数，
 * 也不该在界面上显示成一次真正的失败。
 */
class NetworkUnavailableException(
    message: String = "Network is unavailable; request was not sent",
) : IOException(message)

/**
 * 请求在途时默认网络换了路由：这条请求作废，但父 agent 不受影响，
 * 由重试策略决定要不要在新路由上重新请求。工具和图片永远不会在这里重放。
 */
class NetworkRouteChangedException(
    val epoch: Long,
    message: String = "Default network changed; reconnecting on the current route",
) : IOException(message)

/**
 * Retire a text request on an old Android default route without cancelling its parent agent.
 * The normal retry policy decides whether to issue a new request. Tools/images are never replayed here.
 * This wrapper sits OUTSIDE the model limiter: waiting for a network does not hold an API permit.
 */
class NetworkAwareProvider<T : ProviderSetting>(
    private val delegate: Provider<T>,
    private val network: NetworkHandover,
) : Provider<T> by delegate {

    override suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = onCurrentRoute(providerSetting) {
        delegate.generateText(providerSetting, messages, params)
    }

    override suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = channelFlow {
        onCurrentRoute(providerSetting) {
            delegate.streamText(providerSetting, messages, params).collect { send(it) }
        }
    }

    private suspend fun <R> onCurrentRoute(setting: T, block: suspend () -> R): R {
        // Loopback providers can remain reachable without an Internet route.
        if (setting.isLoopbackEndpoint()) return block()
        // 无网时快速失败，而不是在这里悄悄挂 120 秒：真正的等待交给调用方的重试策略
        // （用户配置的间隔，换网/恢复会立刻唤醒），界面也才能显示「无网」而不是「一直在转」。
        if (!network.awaitUsable(ROUTE_PROBE_MILLIS)) throw NetworkUnavailableException()
        return coroutineScope {
            val epoch = network.state.value.epoch
            val request = async { block() }
            val changed = async { network.state.first { it.epoch != epoch || !it.available } }
            try {
                select {
                    request.onAwait { it }
                    changed.onAwait {
                        request.cancel()
                        throw NetworkRouteChangedException(network.state.value.epoch)
                    }
                }
            } finally {
                changed.cancel()
                request.cancel()
            }
        }
    }

    private companion object {
        /**
         * 请求开跑前最多等这么久让新路由稳定。
         * 只是一次抖动确认，不是等待策略：用户设的是 30~60 秒，绝不能在这里变成两分钟。
         */
        const val ROUTE_PROBE_MILLIS: Long = 250L
    }
}

private fun ProviderSetting.isLoopbackEndpoint(): Boolean {
    val host = (this as? ProviderSetting.ApiKey)?.baseUrl?.toHttpUrlOrNull()?.host ?: return false
    return host.equals("localhost", ignoreCase = true) || host == "::1" || host.matches(Regex("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))
}
