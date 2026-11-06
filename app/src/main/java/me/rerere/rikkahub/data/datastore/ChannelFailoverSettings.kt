package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * 同模型多渠道优先级与故障切换（fork）。
 *
 * 语义只有一条：**用户显式列出的渠道**才可能被调用。默认关闭，关闭时主聊天完全走
 * 原来的单渠道路径（零行为变化、零额外请求）。
 *
 * - 一条 [ModelChannelRoute] 对应一个「模型组」，组的身份是模型的 `modelId` 字符串
 *   （同一个模型在不同 provider 实例里的 modelId 相同）。
 * - [ModelChannelRoute.channels] 的顺序就是优先级：索引 0 优先。
 * - 每个渠道由 provider 实例 UUID + modelId 精确定位，所以同一个模型可以同时出现在
 *   多个 provider 实例里，各自排序，不会互相串。
 *
 * 与上游 Settings 分文件维护，减少后续合并 3.0.x 时的冲突面。
 */
@Serializable
data class ChannelFailoverSettings(
    val enabled: Boolean = false,
    val routes: List<ModelChannelRoute> = emptyList(),
) {
    /** 该模型组的渠道优先级列表；没有配置时返回 null（= 不启用切换）。 */
    fun routeFor(modelId: String): ModelChannelRoute? =
        routes.firstOrNull { it.matches(modelId) }

    fun withRoute(route: ModelChannelRoute): ChannelFailoverSettings {
        val normalized = route.normalized()
        val existing = routes.indexOfFirst { it.matches(normalized.modelId) }
        val next = if (existing == -1) {
            routes + normalized
        } else {
            routes.toMutableList().apply { set(existing, normalized) }
        }
        return copy(routes = next)
    }

    fun withoutRoute(modelId: String): ChannelFailoverSettings =
        copy(routes = routes.filterNot { it.matches(modelId) })

    /** 追加一个渠道（已在组里的同一渠道不会重复追加）。 */
    fun withChannelAdded(modelId: String, slot: ModelChannelSlot): ChannelFailoverSettings {
        val route = routeFor(modelId)?.normalized() ?: ModelChannelRoute(modelId = modelId.trim())
        if (route.channels.any { it.sameAs(slot) }) return withRoute(route)
        return withRoute(route.copy(channels = route.channels + slot))
    }

    fun withChannelRemoved(modelId: String, slot: ModelChannelSlot): ChannelFailoverSettings {
        val route = routeFor(modelId) ?: return this
        return withRoute(route.copy(channels = route.channels.filterNot { it.sameAs(slot) }))
    }

    /** 把组内第 [from] 个渠道移动到第 [to] 位；越界或原地移动时原样返回。 */
    fun withChannelMoved(modelId: String, from: Int, to: Int): ChannelFailoverSettings {
        val route = routeFor(modelId) ?: return this
        val channels = route.channels
        if (from !in channels.indices || to !in channels.indices || from == to) return this
        val moved = channels.toMutableList().apply { add(to, removeAt(from)) }
        return withRoute(route.copy(channels = moved))
    }

    /** 去重、去空白、丢弃空组；读设置和写设置都用它，避免脏数据进 JSON。 */
    fun normalized(): ChannelFailoverSettings = copy(
        routes = routes.map { it.normalized() }
            .filter { it.modelId.isNotBlank() && it.channels.isNotEmpty() }
            .distinctBy { it.modelId.lowercase() },
    )
}

/** 一个模型组：同一个模型在多个 provider 实例里的渠道，按优先级排列。 */
@Serializable
data class ModelChannelRoute(
    /** 组的身份：模型的 modelId（忽略大小写比较） */
    val modelId: String = "",
    val channels: List<ModelChannelSlot> = emptyList(),
) {
    fun matches(id: String): Boolean = modelId.trim().equals(id.trim(), ignoreCase = true)

    fun normalized(): ModelChannelRoute = copy(
        modelId = modelId.trim(),
        channels = channels.map { ModelChannelSlot(it.providerId.trim(), it.modelId.trim()) }
            .distinctBy { it.providerId to it.modelId }
            .filter { it.providerId.isNotBlank() && it.modelId.isNotBlank() },
    )
}

/**
 * 组里的一个渠道：哪个 provider 实例（UUID）里的哪个模型（modelId）。
 *
 * 用 modelId 而不是模型实例 UUID 记录，是因为删掉再加回来会换 UUID；
 * 用 modelId 才能在设置里重新导入模型后仍然对得上，对不上时会被跳过。
 */
@Serializable
data class ModelChannelSlot(
    val providerId: String = "",
    val modelId: String = "",
) {
    fun sameAs(other: ModelChannelSlot): Boolean =
        providerId.trim().equals(other.providerId.trim(), ignoreCase = true) &&
            modelId.trim().equals(other.modelId.trim(), ignoreCase = true)
}
