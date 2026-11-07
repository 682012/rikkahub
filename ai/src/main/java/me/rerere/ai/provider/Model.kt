package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // 上下文窗口大小(token)，仅为客户端容量/压缩依据，不修改服务端模型上限。
    val contextLength: Int = 1_000_000,
    /** null follows the global automatic-compression setting. */
    val autoCompressEnabled: Boolean? = null,
    /** null follows the global threshold; valid custom values are 10..100. */
    val autoCompressThresholdPercent: Int? = null,
    /** null adds no per-model cap; otherwise 1..64 simultaneous API requests. */
    val maxConcurrentRequests: Int? = null,
    /**
     * 提示词缓存保留时长（仅 OpenAI 系渠道的 `prompt_cache_retention`）。
     *
     * 默认 [PromptCacheRetention.DEFAULT] = 一个字节都不发，兼容所有 OpenAI 兼容渠道；
     * 只有用户在模型设置里显式 opt-in 才发 `"prompt_cache_retention":"24h"`，
     * 因为该字段只被 OpenAI 官方（及少数实现了它的网关）接受，其余渠道会 400。
     * Claude 的 5m/1h 由 [ProviderSetting.Claude.promptCacheTtl] 走 cache_control，不走这里。
     */
    val promptCacheRetention: PromptCacheRetention = PromptCacheRetention.DEFAULT,
)

/**
 * 提示词缓存（prompt cache）的保留策略，与「应用层图片/网络缓存」无关。
 *
 * 只描述用户显式选的时长，不按模型名猜哪档可用：能力由调用方按 provider API 判断后传入。
 *
 * - [DEFAULT]：不发送任何缓存保留参数，服务端按各家默认（OpenAI 为 30m/in_memory）。
 * - [THIRTY_MINUTES] / [TWELVE_HOURS]：GPT-5.6 及以后（含 6.x）的新协议，
 *   发 `prompt_cache_options: { "ttl": "30m" | "12h" }`；12h 目前只有 Responses API 接受。
 * - [ONE_DAY]：旧协议字段 `prompt_cache_retention = "24h"`，旧模型/网关兼容用。
 *
 * 写费率随厂商与模型不同（例如 GPT-5.6 及以后 30m 约 1.25x、12h 约 2.5x 的 prompt 单价），
 * 旧缓存写入通常无额外费用，所以不能笼统认为「更长一定更贵」。
 *
 * 官方字段：https://developers.openai.com/api/docs/guides/prompt-caching
 * 上下文同时返回 `ModelCacheRetentionCapabilities`，路由/校验统一走 [cacheRetentionWire]。
 */
@Serializable
enum class PromptCacheRetention(
    /** 旧协议 `prompt_cache_retention` 的取值；null 表示不发该字段。 */
    val apiValue: String?,
    /** 新协议 `prompt_cache_options.ttl` 的取值；null 表示不发该字段。 */
    val optionsTtl: String? = null,
) {
    @SerialName("default")
    DEFAULT(null, null),

    @SerialName("30m")
    THIRTY_MINUTES(null, "30m"),

    @SerialName("12h")
    TWELVE_HOURS(null, "12h"),

    @SerialName("24h")
    ONE_DAY("24h", null),

}

/** 使用新协议 `prompt_cache_options.ttl` 的档位。 */
val PromptCacheRetention.usesPromptCacheOptions: Boolean
    get() = optionsTtl != null

/** 是否要在请求体里写任何缓存保留字段。 */
val PromptCacheRetention.sendsAnyWireField: Boolean
    get() = apiValue != null || optionsTtl != null

/**
 * 按空格分隔的关键词筛选模型：忽略大小写，每个关键词都要命中模型 ID 或显示名称的一部分，
 * 关键词之间不要求顺序。没有关键词时返回原列表。
 */
fun List<Model>.filterByKeywords(query: String): List<Model> {
    val keywords = query.split(" ").filter { it.isNotBlank() }
    if (keywords.isEmpty()) return this
    return filter { model ->
        keywords.all { keyword ->
            model.modelId.contains(keyword, ignoreCase = true) ||
                model.displayName.contains(keyword, ignoreCase = true)
        }
    }
}

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()

    /**
     * OpenAI Responses 的 `code_interpreter`：模型在服务端容器里跑代码，生成的图表/文件以
     * `container_file_citation` 返回。
     *
     * 请求侧只负责发送 `{"type":"code_interpreter","container":{"type":"auto"}}`；能否真正取回产物取决于
     * 网关是否提供 `/containers/{id}/files/{id}/content`，需要实际探活，解析侧只按“识别到的形状”处理。
     */
    @Serializable
    @SerialName("code_interpreter")
    data object CodeInterpreter : BuiltInTools()
}
