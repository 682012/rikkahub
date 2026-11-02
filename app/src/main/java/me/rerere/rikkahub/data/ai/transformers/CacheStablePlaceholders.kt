package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 客户端提示词前缀稳定：易变占位符改成"稳定引用 + 本轮绑定"
 *
 * 背景：原先 [PlaceholderTransformer] 每一步都把 `{battery_level}` / `{cur_date}` 换成此刻的值，
 * 于是 system 提示和历史消息文本每个 step 都可能变，客户端这条请求的公共前缀就跟着漂移。
 * （至于供应商侧缓存究竟怎么算、命中不了是不是这个原因，这里不下结论。）
 *
 * 这里只改"值从哪来"，不改占位符语义：
 * - system 提示里的易变占位符换成明确引用 `<runtime value: battery_level>`，system 逐字节稳定；
 * - 真实值只在请求最后一条真实 user 消息末尾的 `<runtime_bindings>` 块里给一次；
 * - 每条消息注入的值按 message.id 冻结在 [RuntimeBindingCache] 里（最多 256 条、每条只存两个短值），
 *   因此同一个 user 消息在后续工具 steps 重建时逐字节相同；出现新的 user 消息才更新；
 * - 历史消息里的 `{cur_date}` 用该消息 createdAt 的本地日期，不再用 now；
 * - 历史消息里的 `{battery_level}` 优先用已冻结的值，从没见过的旧消息写 `unknown`，不伪造历史电量。
 *
 * 本文件是纯 Kotlin（不碰 Android Context、不自己读时钟/电量），可用假时钟、假 resolver 单测。
 */

/** 易变占位符的 key、稳定引用文本与绑定块格式集中在这里 */
internal object RuntimeBindings {
    const val BATTERY_LEVEL = "battery_level"
    const val CUR_DATE = "cur_date"

    /** 绑定 key，按字典序排列，渲染绑定块时也用这个顺序 */
    val KEYS: List<String> = listOf(BATTERY_LEVEL, CUR_DATE)

    /** 历史消息没有冻结值时的明确取值：表示"不知道"，不编造 */
    const val UNKNOWN = "unknown"

    const val BLOCK_TAG = "runtime_bindings"

    /** system 提示里代替实时值的稳定引用 */
    fun reference(key: String): String = "<runtime value: $key>"
}

/** 该文本里是否引用了易变占位符（单花括号 `{key}` 与双花括号 `{{key}}`，大小写不敏感） */
internal fun String.containsRuntimePlaceholder(): Boolean = RuntimeBindings.KEYS.any { key ->
    contains("{$key}", ignoreCase = true)
}

/**
 * 计算一条消息注入的绑定值，key 固定为 [RuntimeBindings.KEYS]，值只有两个短字符串。
 *
 * @param isCurrentTurn 是否是本轮最后一条真实 user 消息
 * @param createdAt 消息自身的本地日期
 * @param now 当前本地日期（只在 [isCurrentTurn] 为真时使用）
 * @param batteryLevel 当前电量（只在 [isCurrentTurn] 为真时使用，null 表示读不到）
 */
internal fun runtimeBindingValues(
    isCurrentTurn: Boolean,
    createdAt: LocalDate,
    now: LocalDate,
    batteryLevel: String?,
    formatDate: (LocalDate) -> String,
): Map<String, String> = buildMap {
    put(
        RuntimeBindings.BATTERY_LEVEL,
        if (isCurrentTurn) batteryLevel?.takeIf { it.isNotBlank() } ?: RuntimeBindings.UNKNOWN
        else RuntimeBindings.UNKNOWN,
    )
    put(RuntimeBindings.CUR_DATE, formatDate(if (isCurrentTurn) now else createdAt))
}

/** 绑定块文本，key 排序，同样的值必然得到逐字节相同的结果 */
internal fun renderRuntimeBindingsBlock(values: Map<String, String>): String = buildString {
    append("\n\n<").append(RuntimeBindings.BLOCK_TAG).append(">\n")
    values.filterKeys { it in RuntimeBindings.KEYS }.toSortedMap().forEach { (key, value) ->
        append(key).append(": ").append(value).append('\n')
    }
    append("</").append(RuntimeBindings.BLOCK_TAG).append('>')
}

/**
 * 按消息 id 缓存已注入的绑定值。
 *
 * 只保存每条的注入值（两个短字符串），不保存整段 prompt；容量有界（默认 256），
 * 超出后按最近最少使用淘汰，避免长会话无限增长。
 */
internal class RuntimeBindingCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()

    private val entries = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>): Boolean =
            size > maxEntries
    }

    fun get(messageId: String): Map<String, String>? = synchronized(lock) { entries[messageId] }

    fun getOrPut(messageId: String, compute: () -> Map<String, String>): Map<String, String> =
        synchronized(lock) { entries[messageId] ?: compute().also { entries[messageId] = it } }

    fun markInjected(messageId: String) = synchronized(lock) {
        entries[messageId]?.let { entries[messageId] = it + ("__injected" to "true") }
    }

    val size: Int get() = synchronized(lock) { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 256
    }
}

/**
 * 一次请求的占位符渲染：稳定引用 + 本轮绑定 + 其余占位符原样替换。
 *
 * @param staticKeys 除易变 key 之外的占位符 key（语义不变，仍由 [resolveStatic] 求值）
 * @param resolveStatic 非易变占位符求值（例如 nickname、model_name）
 * @param batteryLevel 当前电量；只在某条消息第一次被当作"本轮"消息时调用一次
 */
internal class CacheStablePlaceholders(
    private val cache: RuntimeBindingCache = RuntimeBindingCache(),
    private val today: () -> LocalDate = { LocalDate.now() },
    private val formatDate: (LocalDate) -> String = ::defaultRuntimeDateFormat,
) {
    fun render(
        messages: List<UIMessage>,
        staticKeys: Collection<String> = emptyList(),
        resolveStatic: (String) -> String? = { null },
        batteryLevel: () -> String? = { null },
    ): List<UIMessage> {
        if (messages.isEmpty()) return messages

        // 只有请求里真的出现了易变 system 占位符，才追加绑定段；否则不新增任何 prompt
        val bindsRuntime = messages.any { message ->
            message.role == MessageRole.SYSTEM && message.textParts().any { it.containsRuntimePlaceholder() }
        }
        val currentTurnIndex = lastRealUserIndex(messages)
        val now = today()

        return messages.mapIndexed { index, message ->
            val isCurrentTurn = index == currentTurnIndex
            val texts = message.textParts()
            val needsRuntime = texts.any { it.containsRuntimePlaceholder() }
            val needsBindings = bindsRuntime && message.role == MessageRole.USER && !message.isSynthetic &&
                (isCurrentTurn || cache.get(message.id.toString())?.get("__injected") == "true")
            val needsStatic = texts.any { text -> staticKeys.any { key -> text.contains("{$key}", ignoreCase = true) } }
            if (!needsRuntime && !needsBindings && !needsStatic) return@mapIndexed message

            val values = bindingFor(message, isCurrentTurn, now, batteryLevel)
            val replaced = if (needsRuntime || needsStatic) {
                message.copy(
                    parts = message.parts.map { part ->
                        if (part !is UIMessagePart.Text || '{' !in part.text) {
                            part
                        } else {
                            part.copy(
                                text = replaceInText(
                                    text = part.text,
                                    values = values,
                                    isSystem = message.role == MessageRole.SYSTEM,
                                    staticKeys = staticKeys,
                                    resolveStatic = resolveStatic,
                                )
                            )
                        }
                    }
                )
            } else {
                message
            }
            if (needsBindings) {
                cache.markInjected(message.id.toString())
                replaced.withRuntimeBindings(values)
            } else replaced
        }
    }

    /** 便于测试断言缓存没有无限增长 */
    val cacheSize: Int get() = cache.size

    private fun bindingFor(
        message: UIMessage,
        isCurrentTurn: Boolean,
        now: LocalDate,
        batteryLevel: () -> String?,
    ): Map<String, String> {
        val messageId = message.id.toString()
        cache.get(messageId)?.let { return it }

        val createdAt = message.createdAt.toJavaLocalDateTime().toLocalDate()
        if (!isCurrentTurn) {
            // 从没见过的历史消息：日期由 createdAt 决定（稳定、不含 now），电量明确 unknown
            return runtimeBindingValues(
                isCurrentTurn = false,
                createdAt = createdAt,
                now = now,
                batteryLevel = null,
                formatDate = formatDate,
            )
        }
        // 本轮 user 消息：只在第一次见到时读一次日期与电量，之后一直沿用，工具期间的读数变化不再改动前缀
        return cache.getOrPut(messageId) {
            runtimeBindingValues(
                isCurrentTurn = true,
                createdAt = createdAt,
                now = now,
                batteryLevel = batteryLevel(),
                formatDate = formatDate,
            )
        }
    }

    private fun replaceInText(
        text: String,
        values: Map<String, String>,
        isSystem: Boolean,
        staticKeys: Collection<String>,
        resolveStatic: (String) -> String?,
    ): String {
        var result = text

        RuntimeBindings.KEYS.forEach { key ->
            val value = if (isSystem) RuntimeBindings.reference(key) else values[key] ?: return@forEach
            result = result
                .replace("{{$key}}", value, ignoreCase = true)
                .replace("{$key}", value, ignoreCase = true)
        }

        staticKeys.forEach { key ->
            if (!result.contains("{$key}", ignoreCase = true)) return@forEach
            val value = resolveStatic(key) ?: return@forEach
            result = result
                .replace("{{$key}}", value, ignoreCase = true)
                .replace("{$key}", value, ignoreCase = true)
        }

        return result
    }

    /** 追加在最后一条真实 user 消息末尾；已有绑定块时保持原样（幂等） */
    private fun UIMessage.withRuntimeBindings(values: Map<String, String>): UIMessage {
        if (parts.any { part -> part is UIMessagePart.Text && "<${RuntimeBindings.BLOCK_TAG}>" in part.text }) {
            return this
        }
        val block = renderRuntimeBindingsBlock(values)
        val updated = parts.toMutableList()
        val lastTextIndex = updated.indexOfLast { it is UIMessagePart.Text }
        if (lastTextIndex >= 0) {
            val text = updated[lastTextIndex] as UIMessagePart.Text
            updated[lastTextIndex] = text.copy(text = text.text + block)
        } else {
            updated.add(UIMessagePart.Text(block))
        }
        return copy(parts = updated)
    }

    /** 最后一条真实 user 消息（跳过 notice 拆出的合成消息）；没有就退化为最后一条 user 消息 */
    private fun lastRealUserIndex(messages: List<UIMessage>): Int {
        val realIndex = messages.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
        if (realIndex >= 0) return realIndex
        return messages.indexOfLast { it.role == MessageRole.USER }
    }

    private fun UIMessage.textParts(): List<String> = parts.mapNotNull { part ->
        (part as? UIMessagePart.Text)?.text
    }
}

/** 与旧的 `{cur_date}` 输出一致的本地化短日期 */
internal fun defaultRuntimeDateFormat(date: LocalDate): String = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(Locale.getDefault())
    .format(date)
