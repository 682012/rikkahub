package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * 同模型多渠道优先级与故障切换（fork）。
 *
 * 语义只有一条：**用户显式列出的渠道**才可能被调用。默认关闭，关闭时主聊天完全走
 * 原来的单渠道路径（零行为变化、零额外请求）。
 *
 * 两种互斥的配置模式：
 *
 * 1. 分类组模式（[enabled] + [routes]，默认）：一条 [ModelChannelRoute] 对应一个「模型组」，
 *    组的身份是模型的 `modelId` 字符串，只能放同一个模型；[ModelChannelRoute.channels] 的顺序即优先级。
 * 2. 自建排序池模式（[customPoolEnabled] + [orderedPool]，独立模式）：从所有已配置聊天模型里
 *    自己挑成员、自己排序，不限 modelId、可跨 provider；启用时严格按 [orderedPool] 顺序调用，
 *    池首项就是首选，**不再看会话当前选中的模型**。池为空或全部失效时明确报错，绝不回退池外渠道。
 *    启用自建池时分类组不参与（[enabled]/[routes] 保留，切回去立刻生效）。
 *
 * 每个渠道由 provider 实例 UUID + 模型精确定位（自建池优先用模型实例 UUID，见
 * [ModelChannelSlot.modelUuid]），所以同一个模型可以同时出现在多个 provider 实例里，各自排序，不会互相串。
 *
 * 与上游 Settings 分文件维护，减少后续合并 3.0.x 时的冲突面。
 * 新增字段全部带默认值，旧 JSON 反序列化行为不变（不改变既有渠道故障切换语义）。
 */
@Serializable
data class ChannelFailoverSettings(
    val enabled: Boolean = false,
    val routes: List<ModelChannelRoute> = emptyList(),
    /** 自建排序池开关：默认关闭，关闭时只有分类组模式生效。 */
    val customPoolEnabled: Boolean = false,
    /** 自建排序池的成员，顺序即优先级（索引 0 = 首选），可跨 modelId / 跨 provider。 */
    val orderedPool: List<ModelChannelSlot> = emptyList(),
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

    // ---- 自建排序池 ----

    /** 往池尾追加一个成员；同一个 channel（provider + 模型）不会重复加入。 */
    fun withPoolSlotAdded(slot: ModelChannelSlot): ChannelFailoverSettings {
        if (orderedPool.any { it.sameAs(slot) }) return this
        return copy(orderedPool = orderedPool + slot)
    }

    /** 追加多个成员，保持传入顺序，自动去重（界面多选添加用）。 */
    fun withPoolSlotsAdded(slots: List<ModelChannelSlot>): ChannelFailoverSettings =
        slots.fold(this) { acc, slot -> acc.withPoolSlotAdded(slot) }

    fun withPoolSlotRemoved(slot: ModelChannelSlot): ChannelFailoverSettings =
        copy(orderedPool = orderedPool.filterNot { it.sameAs(slot) })

    /** 池内上移/下移；越界或原地移动时原样返回。 */
    fun withPoolSlotMoved(from: Int, to: Int): ChannelFailoverSettings {
        if (from !in orderedPool.indices || to !in orderedPool.indices || from == to) return this
        val moved = orderedPool.toMutableList().apply { add(to, removeAt(from)) }
        return copy(orderedPool = moved)
    }

    /** 清空池（不改开关，界面用来一条条重排前的清空）。 */
    fun withPoolCleared(): ChannelFailoverSettings = copy(orderedPool = emptyList())

    /** 去重、去空白、丢弃空组；读设置和写设置都用它，避免脏数据进 JSON。 */
    fun normalized(): ChannelFailoverSettings = copy(
        routes = routes.map { it.normalized() }
            .filter { it.modelId.isNotBlank() && it.channels.isNotEmpty() }
            .distinctBy { it.modelId.lowercase() },
        orderedPool = orderedPool.map { it.normalized() }
            .filter { it.isIdentifiable }
            .distinctBy { it.identityKey },
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
        channels = channels.map { it.normalized() }
            .distinctBy { it.identityKey }
            .filter { it.isIdentifiable },
    )
}

/**
 * 一个渠道：哪个 provider 实例（UUID）里的哪个模型。
 *
 * 用 modelId 而不是模型实例 UUID 记录，是因为删掉再加回来会换 UUID；
 * 用 modelId 才能在设置里重新导入模型后仍然对得上，对不上时会被跳过。
 *
 * 自建排序池额外记录模型实例 UUID（[modelUuid]）：池允许跨 modelId，名称可能重名，
 * 按 UUID 才能精确指到用户挑的那一个模型。为空时退回 providerId + modelId 定位，
 * 所以旧 JSON（没有该字段）照旧可用。
 */
@Serializable
data class ModelChannelSlot(
    val providerId: String = "",
    val modelId: String = "",
    val modelUuid: String = "",
) {
    /** providerId + modelId 都填了才算旧式可定位；只填 UUID 也能定位。 */
    val isIdentifiable: Boolean
        get() = (providerId.trim().isNotBlank() && modelId.trim().isNotBlank()) ||
            modelUuid.trim().isNotBlank()

    /** 去重用的身份键：优先模型实例 UUID，其次 provider + modelId。 */
    val identityKey: String
        get() = modelUuid.trim().lowercase().takeIf { it.isNotBlank() }
            ?: "${providerId.trim().lowercase()}#${modelId.trim().lowercase()}"

    fun normalized(): ModelChannelSlot = copy(
        providerId = providerId.trim(),
        modelId = modelId.trim(),
        modelUuid = modelUuid.trim(),
    )

    fun sameAs(other: ModelChannelSlot): Boolean {
        val uuid = modelUuid.trim()
        val otherUuid = other.modelUuid.trim()
        if (uuid.isNotBlank() && otherUuid.isNotBlank()) {
            return uuid.equals(otherUuid, ignoreCase = true)
        }
        val sameProvider = providerId.trim().equals(other.providerId.trim(), ignoreCase = true)
        val sameModel = modelId.trim().isNotBlank() &&
            modelId.trim().equals(other.modelId.trim(), ignoreCase = true)
        return sameProvider && sameModel
    }
}
