package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * 时间提醒注入转换器
 *
 * 只做两件事，两件事都依赖「这次请求真正发出的时刻」：
 *
 * 1. 历史标记：在第一条 user 消息、以及时间间隔超过阈值的 user 消息之前，标一条
 *    `<time_reminder>Message sent at: ...</time_reminder>`。写的是这条历史消息自己的
 *    `createdAt`，文本里明确说这是历史时间，不是现在。
 * 2. 当前时间：在请求**尾部**追加一条只有本次请求才会出现的
 *    `<time_reminder>Current time: ...</time_reminder>`，取自注入的 [Clock.System.now]。
 *
 * 之前的行为是拿 `UIMessage.createdAt` 当 "Current time"，只在第一条 user 和间隔超阈值时插入。
 * 工具循环里没有新的 user 消息，于是一轮长工具/子代理跑完后再发请求，模型看到的 "现在" 还是
 * 用户发消息那一刻。转换器在 [me.rerere.ai.agent.AgentHooks.prepareStep] 里每一步都会被调用
 * （ChatRequestFactory.buildStep），所以这里读时钟就能拿到每一步的真实发请求时间。
 *
 * 约束：
 * - 只在本次请求里生效，`isSynthetic = true`，不写回持久历史；
 * - 关闭 [Assistant.enableTimeReminder] 时原样返回入参，一个字都不改；
 * - 动态内容全部放在请求尾部，历史标记只由 `createdAt` 推导，缓存前缀保持稳定；
 * - 上一次请求注入过的提醒会在本次输入里被丢掉再按当前时钟重建，多次请求不会越堆越多。
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = applyTimeReminderFor(
        messages = messages,
        assistant = ctx.assistant,
        now = Clock.System.now(),
        timeZone = TimeZone.currentSystemDefault(),
    )
}

/**
 * 按助手的开关和阈值注入时间提醒。
 *
 * 单独拿出来是为了让「开关关掉」「阈值」这两件事能在不构造 `TransformerContext`
 * （要 Android `Context` 和整套 `Settings`）的情况下被测试覆盖。
 */
internal fun applyTimeReminderFor(
    messages: List<UIMessage>,
    assistant: Assistant,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<UIMessage> = applyTimeReminder(
    messages = messages,
    intervalMinutes = assistant.timeReminderIntervalMinutes,
    now = now,
    timeZone = timeZone,
    enabled = assistant.enableTimeReminder,
)

/**
 * 纯函数版本：`now` 和 `timeZone` 全部由调用方给定，方便定向测试。
 *
 * @param messages 即将发给模型的消息（含 system），函数不修改入参、不写回任何持久数据
 * @param intervalMinutes 历史消息之间超过多少分钟才插入间隔标记，最小 1 分钟
 * @param now 这次请求真正发出的设备时钟
 * @param timeZone 渲染时间用的时区，文本里会带上它的 UTC 偏移
 * @param enabled 助手的「时间提醒」开关，关闭时原样返回 [messages]
 */
internal fun applyTimeReminder(
    messages: List<UIMessage>,
    intervalMinutes: Int = 60,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    enabled: Boolean = true,
): List<UIMessage> {
    // 关了开关就原样返回同一个实例：不发任何时间信息，也不“顺手”清理别人的东西
    if (!enabled) return messages
    // 空请求没有必要凭空造一条只有提醒的消息
    if (messages.isEmpty()) return messages

    val thresholdSeconds = intervalMinutes.coerceAtLeast(1).toLong() * 60
    val zoneId = ZoneId.of(timeZone.id)
    // 输入里可能带着上一次请求注入的提醒（重复转换、或调用方把上一次结果又传回来）；
    // 先全部丢掉再按当前时钟重建，否则每请求一次就多一份旧时间
    val history = messages.filterNot { it.isInjectedTimeReminder() }

    val result = ArrayList<UIMessage>(history.size + 4)
    var firstUserSeen = false
    for (i in history.indices) {
        val current = history[i]
        if (current.role == MessageRole.USER && !current.isSynthetic) {
            val currentInstant = current.createdAt.toInstant(timeZone)
            if (!firstUserSeen) {
                // 第一条 user 之前的标记保留（设置页写的「首条用户消息之前总会提醒」），
                // 但它写的是这条消息的时间，不再冒充“现在”
                firstUserSeen = true
                result.add(buildHistoryTimeMessage(null, currentInstant, zoneId))
            } else {
                val previousInstant = history[i - 1].createdAt.toInstant(timeZone)
                val gapSeconds = (currentInstant - previousInstant).inWholeSeconds
                // 阈值严格大于：恰好等于阈值不插入（与旧行为一致）；负间隔（跨设备时钟漂移）同样不插入
                if (gapSeconds > thresholdSeconds) {
                    result.add(buildHistoryTimeMessage(gapSeconds, currentInstant, zoneId))
                }
            }
        }
        result.add(current)
    }

    // 请求尾部：本次真正发请求时的设备时钟。放在最后，历史前缀逐字节不变，缓存照旧命中；
    // 工具结果消息后面接一条 user 提醒，和 expandNotices 已有的 `user(tool_result) → user(notice)`
    // 形状一致，provider 的工具消息顺序约束不被破坏
    val lastMessage = history.lastOrNull()
    val secondsSinceLastMessage = lastMessage
        ?.takeIf { it.role != MessageRole.SYSTEM }
        ?.let { (now - it.createdAt.toInstant(timeZone)).inWholeSeconds }
    result.add(buildCurrentTimeMessage(now, secondsSinceLastMessage, zoneId))
    return result
}

/**
 * 这条消息是不是本转换器上一次注入的提醒。
 *
 * 只认 `isSynthetic` 的 user 消息：用户自己打出来的 `<time_reminder>` 是用户的内容，不能擦掉。
 */
internal fun UIMessage.isInjectedTimeReminder(): Boolean =
    role == MessageRole.USER &&
        isSynthetic &&
        parts.isNotEmpty() &&
        parts.all { it is UIMessagePart.Text } &&
        toText().trimStart().startsWith(TIME_REMINDER_OPEN)

private const val TIME_REMINDER_OPEN = "<time_reminder>"

/** 历史消息时间标记：只声称这条消息是什么时候发的，不声称是现在 */
private fun buildHistoryTimeMessage(
    gapSeconds: Long?,
    instant: Instant,
    zoneId: ZoneId,
): UIMessage {
    val gapNote = gapSeconds?.let { " — ${formatGap(it)} since the previous message" }.orEmpty()
    val text = "<time_reminder>Message sent at: ${formatStamp(instant, zoneId, withSeconds = false)}" +
        "$gapNote. History timestamp only, not the current time.</time_reminder>"
    return syntheticMessage(text, createdAt = instant.at(zoneId))
}

/** 请求尾部的当前时间：设备时钟 + 时区偏移，不做任何“完成时刻”的推测 */
private fun buildCurrentTimeMessage(
    now: Instant,
    secondsSinceLastMessage: Long?,
    zoneId: ZoneId,
): UIMessage {
    // 已经过去多久同样只从真实时间戳算：长工具/子代理跑完再请求时，模型能看出中间空了多久。
    // 不到 1 分钟不提，避免每步都多一句废话
    val elapsedNote = if (secondsSinceLastMessage != null && secondsSinceLastMessage >= 60) {
        " Time since the last message above: ${formatGap(secondsSinceLastMessage)}."
    } else {
        ""
    }
    val text = "<time_reminder>Current time: ${formatStamp(now, zoneId, withSeconds = true)}. " +
        "This is the device clock at the moment this request is sent; treat it as now.$elapsedNote</time_reminder>"
    return syntheticMessage(text, createdAt = now.at(zoneId))
}

/**
 * 请求期间才存在的提醒消息：`isSynthetic` 保证它不会被当成用户输入持久化。
 *
 * `createdAt` 写的是这条提醒对应的真实时刻（历史消息时间或本次发请求时间），
 * 这样重复转换时也不会互相污染间隔计算。
 */
private fun syntheticMessage(text: String, createdAt: LocalDateTime): UIMessage =
    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)), createdAt = createdAt)
        .copy(isSynthetic = true)

/** kotlin.time.Instant 在给定时区里的本地时间 */
private fun Instant.at(zoneId: ZoneId): LocalDateTime =
    toJavaInstant().atZone(zoneId).toLocalDateTime().toKotlinLocalDateTime()

/** 例如 `Saturday, 2026-02-22 14:35:07 (UTC+08:00)` */
private fun formatStamp(instant: Instant, zoneId: ZoneId, withSeconds: Boolean): String {
    val zoned = instant.toJavaInstant().atZone(zoneId)
    // 星期几跟随系统语言（沿用旧行为），日期与偏移用固定格式，模型读起来不会因语言而变形
    val dayOfWeek = zoned.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val pattern = if (withSeconds) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm"
    val dateTime = zoned.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
    return "$dayOfWeek, $dateTime (${zoned.offset.utcLabel()})"
}

/** `UTC`、`UTC+08:00`、`UTC-03:30`：只看偏移，不写时区名，夏令时给的是那一刻实际生效的偏移 */
private fun ZoneOffset.utcLabel(): String {
    if (totalSeconds == 0) return "UTC"
    val sign = if (totalSeconds < 0) "-" else "+"
    val minutes = abs(totalSeconds) / 60
    return "UTC$sign" + String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
}

private fun formatGap(seconds: Long): String {
    return when {
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86400 -> "${seconds / 3600} h"
        else -> "${seconds / 86400} d"
    }
}
