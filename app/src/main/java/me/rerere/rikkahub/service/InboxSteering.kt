package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 运行中插话（steering）的纯判定与转换，不持有状态、不碰持久层。
 *
 * 收件箱负责排队与快照（[ConversationInbox.peekSteering]），AgentLoop 负责在安全点把 part 追加进
 * 当前这一轮（[me.rerere.ai.agent.AgentHooks.pendingUserParts]），这里只回答两个问题：
 * 「这条输入现在能不能插进去」和「它是不是已经插进去了」。
 */

/** 单条插话的文本上限：和 [ConversationInbox.peekSteering] 的批次上限保持一致 */
const val STEERING_MAX_TEXT = 16_000

/** 一次最多插几条：再多说明用户是在补一大段内容，让它走正常下一轮更稳 */
const val STEERING_MAX_MESSAGES = 8

/**
 * 这条输入现在应该插进正在生成的那一轮，还是退回普通排队。
 *
 * 只有同时满足才插话：用户在本轮设置里开了插话（[requested]）、模型正在生成（[generating]）、
 * 没有工具在等审批（[awaitingApproval]，此时插进去等于绕过审批顺序）、这条输入要模型回答
 * （[answer]）、内容是纯文本且非空白、总长不超上限。任何一条不满足都退回普通排队 ——
 * 排队语义和以前完全一样，不会丢输入。
 */
fun shouldSteerInput(
    parts: List<UIMessagePart>,
    answer: Boolean,
    generating: Boolean,
    awaitingApproval: Boolean,
    requested: Boolean,
): Boolean {
    if (!requested || !generating || awaitingApproval || !answer) return false
    val texts = parts.map { it as? UIMessagePart.Text ?: return false }
    if (texts.none { it.text.isNotBlank() }) return false
    // 按真正会发出去的样子算长度：多个文本 part 之间会插入一个换行（[steeringText]），
    // 只把各自长度加起来会放进来一条拼接后其实超上限的输入。
    return steeringTextLength(texts) <= STEERING_MAX_TEXT
}

/**
 * 把一条排队输入变成可以追加进助手消息的 [UIMessagePart.Notice]。
 *
 * 正文一个字都不改：`expandNotices` 会把它拆成一条 user 消息，模型看到的就是用户原话。
 * [UIMessagePart.Notice.title] 只用于界面展示；metadata 记下来源和队列 id，重启后靠它判断
 * 「这条是否已经插过」。
 */
fun QueuedMessage.toSteeringPart(): UIMessagePart.Notice = UIMessagePart.Notice(
    title = STEERING_NOTICE_TITLE,
    text = steeringText(),
    metadata = buildJsonObject {
        put(STEERING_ID_KEY, id.toString())
        put(STEERING_SOURCE_KEY, STEERING_SOURCE_USER)
    },
)

/**
 * 这条输入是不是已经作为插话进了会话历史。
 *
 * 只认 metadata 里的队列 id + 正文**完全相等**：不能用相似度判断，用户可能随后又发了一条
 * 措辞相近的话，把新输入误判成已交付就会直接吞掉它。重启后主代理用它补 ack，避免同一条插话
 * 被重复注入。
 */
fun QueuedMessage.isSteeringDeliveredIn(messages: List<UIMessage>): Boolean {
    val expected = steeringText()
    if (expected.isEmpty()) return false
    val expectedId = id.toString()
    return messages.any { message ->
        message.parts.any { part ->
            part is UIMessagePart.Notice &&
                part.text == expected &&
                part.metadata.steeringId() == expectedId &&
                part.metadata.steeringSource() == STEERING_SOURCE_USER
        }
    }
}

/** 收件箱里这一条能不能在运行中插话：非编辑中、要回答、开了插话、全是纯文本且非空白 */
internal fun QueuedMessage.steerable(): Boolean =
    !isEditing &&
        answer &&
        deliverDuringRun &&
        parts.isNotEmpty() &&
        parts.all { it is UIMessagePart.Text } &&
        parts.any { (it as UIMessagePart.Text).text.isNotBlank() }

/** 用户原话：多个文本 part 按顺序接起来，不添加任何说明文字 */
internal fun QueuedMessage.steeringText(): String =
    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

/**
 * 插话正文的实际长度：[steeringText] 用 "\n" 连接多个文本 part，所以长度是各段之和 + (段数 - 1)。
 * [shouldSteerInput] 和 [ConversationInbox.peekSteering] 都必须用这一口径，否则拼接后超过
 * [STEERING_MAX_TEXT] 的输入会被误判成「装得下」。
 */
internal fun steeringTextLength(parts: List<UIMessagePart>): Int {
    val texts = parts.filterIsInstance<UIMessagePart.Text>()
    if (texts.isEmpty()) return 0
    return texts.sumOf { it.text.length } + (texts.size - 1)
}

/**
 * 读 metadata 里的队列 id / 来源。历史消息里的 metadata 是外部数据：可能不是 JSON primitive
 * （JsonObject/JsonArray/JsonNull），`jsonPrimitive` 遇到它们会抛异常 —— 那会让「通知历史里有没有
 * 这条插话」直接崩掉，所以这里按形状取值，读不出来就当作没有，不做相似度或宽松匹配。
 */
private fun JsonObject?.steeringId(): String? = stringOrNull(STEERING_ID_KEY)

private fun JsonObject?.steeringSource(): String? = stringOrNull(STEERING_SOURCE_KEY)

private fun JsonObject?.stringOrNull(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal const val STEERING_ID_KEY = "meridian_steering_id"
internal const val STEERING_SOURCE_KEY = "source"
internal const val STEERING_SOURCE_USER = "USER"
internal const val STEERING_NOTICE_TITLE = "用户插话"
