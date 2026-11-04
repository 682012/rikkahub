package me.rerere.rikkahub.data.ai

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * 逐张识别结果的文字缓存（进程内 LRU + 磁盘 JSON）。
 *
 * 只存说明文本，**不存图片**：原图还在原处，缓存只是一段中文描述。容量和 TTL 都很小，
 * 复用现成的 [LruCache]/[SingleFileCacheStore]，不新增依赖。
 *
 * key 由 [sequentialImageCacheKey] 生成：conversationId + 模型指纹 + 图片 url（本地图含 size/mtime）+ 问题。
 * 命中即跳过网络请求，所以同一轮里多个步骤、以及失败后的重试都不会重复发图重新计费。
 */
class SequentialImageCache(
    cacheFile: File,
    capacity: Int = 32,
    ttl: Duration = 3.days,
) {
    private val cache = LruCache<String, String>(
        capacity = capacity,
        store = SingleFileCacheStore(
            file = cacheFile,
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = Json { ignoreUnknownKeys = true; allowStructuredMapKeys = true },
        ),
        deleteOnEvict = true,
        preloadFromStore = true,
        expireAfterWriteMillis = ttl.inWholeMilliseconds,
    )

    fun get(cacheKey: String): String? = cache.get(cacheKey)?.takeIf { it.isNotBlank() }

    /** 只缓存真正成功的说明；空白结果当失败处理，绝不写入缓存。 */
    fun put(cacheKey: String, description: String) {
        if (description.isBlank()) return
        cache.put(cacheKey, description)
    }

    fun clear() = cache.clear()
}
