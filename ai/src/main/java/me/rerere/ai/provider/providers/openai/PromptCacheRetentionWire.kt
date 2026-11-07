package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.PromptCacheRetention

/**
 * 一个渠道对提示词缓存保留档位的实际支持能力。
 *
 * 只由 provider API 决定，不看模型名字：官方文档说 30m/12h 属于 GPT-5.6 及以后的新协议，
 * 但客户端无法可靠判断某个网关转发的模型走哪套协议，所以能力交给调用方声明，
 * 用户选了不支持的档位时明确报错，绝不静默降级成别的时长。
 */
data class ModelCacheRetentionCapabilities(
    /** 旧协议 `prompt_cache_retention`（in_memory / 24h）。 */
    val supportsLegacyRetention: Boolean = false,
    /** 新协议 `prompt_cache_options.ttl` 的 30m 档。 */
    val supportsThirtyMinutes: Boolean = false,
    /** 新协议 12h 档，目前只有 Responses API 接受。 */
    val supportsTwelveHours: Boolean = false,
) {
    /** 该渠道是否显示/接受任意保留档位。 */
    val supportsAny: Boolean
        get() = supportsLegacyRetention || supportsThirtyMinutes || supportsTwelveHours

    fun supports(retention: PromptCacheRetention): Boolean = when (retention) {
        PromptCacheRetention.DEFAULT -> true
        PromptCacheRetention.THIRTY_MINUTES -> supportsThirtyMinutes
        PromptCacheRetention.TWELVE_HOURS -> supportsTwelveHours
        PromptCacheRetention.ONE_DAY -> supportsLegacyRetention
    }

    /** UI 只列当前渠道真能发出去的档位，DEFAULT 永远可选。 */
    fun selectable(): List<PromptCacheRetention> =
        PromptCacheRetention.entries.filter { supports(it) }

    companion object {
        /** Chat Completions：旧字段 24h 仍可用；30m 用新协议；12h 不提供。 */
        val CHAT_COMPLETIONS = ModelCacheRetentionCapabilities(
            supportsLegacyRetention = true,
            supportsThirtyMinutes = true,
            supportsTwelveHours = false,
        )

        /** Responses API：支持新协议的 30m / 12h 与旧字段 24h。 */
        val RESPONSES = ModelCacheRetentionCapabilities(
            supportsLegacyRetention = true,
            supportsThirtyMinutes = true,
            supportsTwelveHours = true,
        )
    }
}

/**
 * 把保留档位校验成可写入请求体的形态；不支持的组合返回 [IllegalArgumentException]，
 * 而不是退回到默认档——静默缩短会把用户明确选的 12h 悄悄变成 30m。
 */
fun resolvePromptCacheRetention(
    retention: PromptCacheRetention,
    capabilities: ModelCacheRetentionCapabilities,
): PromptCacheRetention {
    require(capabilities.supports(retention)) {
        "该渠道不支持提示词缓存保留档位 ${retention.serialNameOrName()}；" +
            "可用档位：${capabilities.selectable().joinToString { it.serialNameOrName() }}"
    }
    return retention
}

/** 只写保留字段，别的一个字节都不碰；默认档不产生任何 key。 */
fun JsonObjectBuilder.putPromptCacheRetention(retention: PromptCacheRetention) {
    retention.apiValue?.let { put("prompt_cache_retention", it) }
    retention.optionsTtl?.let { ttl ->
        put("prompt_cache_options", buildJsonObject { put("ttl", ttl) })
    }
}

/**
 * 校验 + 写入的组合入口，两个 API 共用：
 * [PromptCacheRetention.DEFAULT] 一律不发，其余档位先按渠道能力校验再写。
 */
fun JsonObjectBuilder.putValidatedPromptCacheRetention(
    retention: PromptCacheRetention,
    capabilities: ModelCacheRetentionCapabilities,
) {
    if (retention == PromptCacheRetention.DEFAULT) return
    putPromptCacheRetention(resolvePromptCacheRetention(retention, capabilities))
}

internal fun PromptCacheRetention.serialNameOrName(): String = when (this) {
    PromptCacheRetention.DEFAULT -> "default"
    PromptCacheRetention.THIRTY_MINUTES -> "30m"
    PromptCacheRetention.TWELVE_HOURS -> "12h"
    PromptCacheRetention.ONE_DAY -> "24h"
}

/** 便于测试与 UI 复用：给定档位与能力，直接产出只含缓存字段的 JsonObject。 */
fun promptCacheRetentionFields(
    retention: PromptCacheRetention,
    capabilities: ModelCacheRetentionCapabilities,
): JsonObject = buildJsonObject { putValidatedPromptCacheRetention(retention, capabilities) }
