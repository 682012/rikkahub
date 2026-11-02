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
        if (!network.awaitUsable()) throw IOException("Network is unavailable; request was not sent")
        return coroutineScope {
            val epoch = network.state.value.epoch
            val request = async { block() }
            val changed = async { network.state.first { it.epoch != epoch || !it.available } }
            try {
                select {
                    request.onAwait { it }
                    changed.onAwait {
                        request.cancel()
                        throw IOException("Default network changed; reconnecting on the current route")
                    }
                }
            } finally {
                changed.cancel()
                request.cancel()
            }
        }
    }
}

private fun ProviderSetting.isLoopbackEndpoint(): Boolean {
    val host = (this as? ProviderSetting.ApiKey)?.baseUrl?.toHttpUrlOrNull()?.host ?: return false
    return host.equals("localhost", ignoreCase = true) || host == "::1" || host.matches(Regex("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))
}
