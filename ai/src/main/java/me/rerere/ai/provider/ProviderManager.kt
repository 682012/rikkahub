package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.grok.GrokProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 *
 * 注册时统一套上每模型并发闸门：全进程只有这一个 [ModelRequestLimiter]，
 * 不按每次 [getProvider] 重建，否则每个调用方各拿一套名额，等于没有限制。
 *
 * @param requestLimit 取某个模型的并发上限，默认读 [Model.maxConcurrentRequests]。
 * 传进来的模型可能是旧快照，Settings 里同 id 的最新模型优先，见 [LiveModelLimits]。
 */
class ProviderManager(
    client: OkHttpClient,
    context: Context,
    accountCredentials: AccountCredentialProvider,
    requestLimit: (Model) -> Int? = { it.maxConcurrentRequests },
) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    private val limits = LiveModelLimits(requestLimit)

    /** 全局共享的并发闸门 */
    private val limiter = ModelRequestLimiter(limits::limitFor)

    init {
        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))
        registerProvider("chatgpt", ChatGPTProvider(client, accountCredentials))
        registerProvider("grok", GrokProvider(client, accountCredentials))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        @Suppress("UNCHECKED_CAST")
        providers[name] = RequestLimitedProvider(provider as Provider<ProviderSetting>, limiter)
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
            is ProviderSetting.ChatGPT -> getProvider("chatgpt")
            is ProviderSetting.Grok -> getProvider("grok")
        } as Provider<T>
    }

    /**
     * 用 Settings 里当前所有模型刷新限额来源。
     *
     * 只换快照不打断请求：已发出的请求照常跑完，之后进来的请求按新限额算。
     */
    fun updateModelLimits(models: List<Model>) {
        limits.update(models)
    }

    /**
     * 限额可能变了（含排队期间改设置）：唤醒排队请求重新判断。
     *
     * 限额调小时被唤醒的请求会重新排队，所以不会出现新名额 + 旧名额双池超发。
     */
    fun notifyLimitsChanged() {
        limiter.notifyChanged()
    }
}
