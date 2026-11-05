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
)

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
