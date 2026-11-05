package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * 标题是怎么来的。没写过标题的会话等价于 [AUTO] 的空白标题；用户手改之后变成 [MANUAL]。
 */
enum class ConversationTitleSource {
    /** 自动生成，可以再被自动生成替换 */
    AUTO,

    /** 用户自己改的，自动生成永远不覆盖 */
    MANUAL,
}

/**
 * 标题的来路和进度。挂在 [Conversation.automaticTitleState] 上，跟着会话实例一起传递，
 * 不落库：数据库表结构在 r20 不扩列，重启后按标题本身保守推断（[inferTitleState]）。
 *
 * 发送用户消息时按用户原话先起一个临时标题（[pendingEarly]），等这一轮回答出来，
 * 只有在标题还是自动、并且回答确实带来了新内容时才再精修一次。
 * 所有变换都是纯函数，调用方负责放进 `session.updateMetadata { ... }` 里原子写入。
 */
data class ConversationTitleState(
    val source: ConversationTitleSource = ConversationTitleSource.AUTO,
    /** 用户原话快照：起标题时的锚点，精修时据此判断对话是否真的往前走了 */
    val anchor: String = "",
    /** 已经据以起过标题的消息 id；同一条消息再触发时不重复起标题 */
    val triggerMessageId: Uuid? = null,
    /** 用户发消息时起的临时标题（还没等到回答）；false 表示没有在等精修 */
    val pendingEarly: Boolean = false,
    /** 上一次自动标题覆盖了哪些内容的指纹，同一段内容不再重复起标题 */
    val fingerprint: String = "",
    /** 已经自动起过标题的次数，0 表示还只有空白标题 */
    val autoTitles: Int = 0,
) {
    /** 是否还允许自动标题写入（没被用户手改过） */
    val isAuto: Boolean get() = source == ConversationTitleSource.AUTO
}

/**
 * 新加载的会话（内存里没有状态）按标题推断来路。
 *
 * 已经有标题就当作有正式标题：手改过的、之前自动生成的都不再被自动覆盖，
 * 只有用户自己点「重新生成标题」才会改。空白标题的会话照常自动生成。
 * 保守推断的代价是重启后少一次自动改名，方向是「宁可不改」。
 */
fun inferTitleState(title: String): ConversationTitleState = if (title.isBlank()) {
    ConversationTitleState()
} else {
    ConversationTitleState(source = ConversationTitleSource.MANUAL, autoTitles = 1)
}

/** 用户原话拿去起标题的上限：空白压成一行再截断，避免把整篇粘贴内容塞给快速模型 */
internal const val TITLE_USER_TEXT_LIMIT = 1_000

/** 精修所需的最短回答长度：一句「好的」不值得改标题 */
internal const val TITLE_REFINE_MIN_ANSWER = 16

private val TITLE_SPACE = Regex("\\s+")

/** 取用户原话快照；空白（纯图片/文件）返回空串，这时自动标题要等回答 */
fun String.titleSnapshot(limit: Int = TITLE_USER_TEXT_LIMIT): String {
    val normalized = TITLE_SPACE.replace(this, " ").trim()
    if (normalized.length <= limit) return normalized
    val cut = normalized.take(limit)
    // 尽量在空格处断开，别把词切成两半
    val lastSpace = cut.lastIndexOf(' ')
    return (if (lastSpace > limit / 2) cut.take(lastSpace) else cut).trim()
}

/**
 * 起标题用的锚点：发送的那条用户消息的文本快照 + 它的 id。
 * 只有用户输入才是起标题的依据，助手的长回答不进锚点，所以不会每轮都变。
 */
data class TitleTrigger(
    val messageId: Uuid,
    val userText: String,
) {
    /** 同一条消息、同样的内容：已经据此起过标题，不必再来一次 */
    fun isSameAs(state: ConversationTitleState): Boolean =
        state.triggerMessageId == messageId && state.anchor == userText

    /** 用户原话为空（只有图片/文件）：没有可用的文字快照，标题改用完整对话来起 */
    val hasText: Boolean get() = userText.isNotBlank()

    companion object {
        /** [message] 不是用户消息（比如只是通知）时返回 null */
        fun of(message: UIMessage): TitleTrigger? {
            if (message.role != MessageRole.USER) return null
            return TitleTrigger(message.id, message.toText().titleSnapshot())
        }
    }
}

/**
 * 读会话上的标题状态。内存里没有（刚加载、刚 fork、刚导入）就按当前标题保守推断。
 */
val Conversation.titleState: ConversationTitleState
    get() = automaticTitleState ?: inferTitleState(title)

/**
 * 写入标题和状态。状态挂在会话实例上，[copy] 会一起带下去，所以调用方接着用返回值即可；
 * 不能退回到用会话实例当键的全局表，流式生成每来一段都会 copy 出新实例，状态会丢。
 */
fun Conversation.withTitleState(newTitle: String, state: ConversationTitleState): Conversation =
    copy(title = newTitle, automaticTitleState = state)

/** 用户手改标题：标记成手改，清掉待精修的临时标题，之后的自动标题不再覆盖 */
fun Conversation.withManualTitle(newTitle: String): Conversation {
    val state = titleState
    return withTitleState(
        newTitle = newTitle,
        state = state.copy(
            source = ConversationTitleSource.MANUAL,
            pendingEarly = false,
            autoTitles = maxOf(state.autoTitles, 1),
        ),
    )
}

/**
 * 阶段一：用户消息刚落库，先按原话起的临时标题。
 * 只依赖 [trigger.userText]，可重试；同一条消息重复触发时次数不会继续加。
 */
fun Conversation.withEarlyTitle(newTitle: String, trigger: TitleTrigger): Conversation {
    val state = titleState
    val counted = if (state.pendingEarly || trigger.isSameAs(state)) state.autoTitles
    else state.autoTitles + 1
    return withTitleState(
        newTitle = newTitle,
        state = state.copy(
            source = ConversationTitleSource.AUTO,
            anchor = trigger.userText,
            triggerMessageId = trigger.messageId,
            pendingEarly = true,
            autoTitles = counted,
        ),
    )
}

/**
 * 阶段二：这一轮回答出来之后的精修。
 *
 * @return 新的会话状态；null 表示不值得再改（用户手改过、同一段内容已经精修过）
 */
fun Conversation.refinedTitle(newTitle: String, fingerprint: String): Conversation? {
    val state = titleState
    if (!state.isAuto) return null
    if (state.fingerprint == fingerprint) return null
    return withTitleState(
        newTitle = newTitle,
        state = state.copy(
            pendingEarly = false,
            fingerprint = fingerprint,
            autoTitles = state.autoTitles + 1,
        ),
    )
}

/**
 * 用户自己点的「重新生成标题」：手改过的标题也能换，但来源保持不变：
 * 手改过的仍然算手改（之后的自动标题不能反过来盖掉），自动的仍然是自动。
 * 写完不再等精修，这一版就是用户要的。
 */
fun Conversation.withForcedTitle(newTitle: String, fingerprint: String): Conversation {
    val state = titleState
    return withTitleState(
        newTitle = newTitle,
        state = state.copy(
            pendingEarly = false,
            fingerprint = fingerprint,
            autoTitles = if (state.isAuto) state.autoTitles + 1 else state.autoTitles,
        ),
    )
}

/** 回答出来之后标题要不要做一次、是不是精修 */
data class TitleDecision(
    val request: Boolean,
    /** 精修：已经有临时标题，回答出来了，用完整对话再起一次 */
    val refine: Boolean,
    /** 用户手动点的重新生成：不受手改和自动开关限制 */
    val force: Boolean = false,
) {
    /** 用完整对话（而不是只用用户原话）作为输入 */
    val fullContext: Boolean get() = refine || force

    companion object {
        val Skip = TitleDecision(request = false, refine = false)
        val Initial = TitleDecision(request = true, refine = false)
        val Refine = TitleDecision(request = true, refine = true)
        val Forced = TitleDecision(request = true, refine = false, force = true)
    }
}

/**
 * 决定 [fingerprint] 这段内容的标题要不要做、是不是精修。
 *
 * 首次标题总是要做；之后只有在这段内容从没起过标题、只有临时标题（还没有正式标题），
 * 并且对话确实往前走了（[newContext]）时才精修一次：一条消息最多带来一次精修，不会每轮无限重起标题。
 * 用户手动点的 [force] 不看手改、不看自动开关，也不看这段内容是不是已经起过标题。
 */
fun Conversation.titleDecision(
    fingerprint: String,
    newContext: Boolean,
    force: Boolean,
    hasContent: Boolean,
): TitleDecision {
    val state = titleState
    return when {
        force -> TitleDecision.Forced
        !hasContent -> TitleDecision.Skip
        !state.isAuto -> TitleDecision.Skip
        // 同一段内容已经起过标题：不重复请求
        state.fingerprint == fingerprint -> TitleDecision.Skip
        state.autoTitles == 0 -> TitleDecision.Initial
        // 只有临时标题等着精修时才做，做完一次就不再动
        state.pendingEarly && newContext -> TitleDecision.Refine
        else -> TitleDecision.Skip
    }
}

/** 指纹：当前分支末尾几条消息的 id 和文本，内容变了指纹就变 */
fun titleFingerprint(messages: List<UIMessage>, recent: Int = 4): String {
    if (messages.isEmpty()) return ""
    return buildString {
        messages.takeLast(recent).forEach { message ->
            append(message.id)
            append(':')
            append(message.toText().titleSnapshot(limit = 240))
            append('\n')
        }
    }
}

/**
 * 精修前判断对话是不是真的往前走了一段，而不是只有「好的」这类短回答重复。
 *
 * [anchor] 是用户原话快照。要求：最后一条是助手消息（用户那条已经被回答过）、
 * 那条用户消息的内容仍然与锚点一致（用户没改写起点）、并且回答本身有实质内容。
 */
fun isMateriallyNewContext(messages: List<UIMessage>, anchor: String): Boolean {
    if (messages.size < 2) return false
    if (messages.last().role != MessageRole.ASSISTANT) return false
    val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (lastUserIndex == -1 || lastUserIndex == messages.lastIndex) return false
    if (messages[lastUserIndex].toText().titleSnapshot() != anchor) return false
    val answer = messages.last().toText().trim()
    if (answer.isEmpty()) return false
    return answer.length >= TITLE_REFINE_MIN_ANSWER
}

/**
 * 起标题时看到的那条分支：选中的用户消息，精修时还有它下面那条回答。
 *
 * 写回标题之前拿它核对最新状态：用户改写了这句话、切到另一条分支、或者重新生成了回答，
 * 标题就不该落在这条新内容上（那已经是另一段对话了）。
 */
data class TitleBranchAnchor(
    val userMessageId: Uuid,
    val userText: String,
    /** 精修时依据的那条回答；早标题还没回答，为 null */
    val answerMessageId: Uuid? = null,
) {
    /**
     * 最新分支是不是还是起标题时那条：选中的用户消息 id 和文本都没变、
     * 之后没有再来一条用户消息（那是下一轮）、精修时最后一条还是那条回答。
     */
    fun isIntactIn(messages: List<UIMessage>): Boolean {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex == -1) return false
        val user = messages[lastUserIndex]
        if (user.id != userMessageId) return false
        if (user.toText().titleSnapshot() != userText) return false
        if (messages.drop(lastUserIndex + 1).any { it.role == MessageRole.USER }) return false
        val answerId = answerMessageId ?: return true
        val last = messages.lastOrNull() ?: return false
        return last.role == MessageRole.ASSISTANT && last.id == answerId
    }
}

/** 当前分支末尾的锚点：最后一条用户消息，[answerMessageId] 不为空时再带上它下面那条回答 */
fun List<UIMessage>.titleBranchAnchor(answerMessageId: Uuid? = null): TitleBranchAnchor? {
    val user = lastOrNull { it.role == MessageRole.USER } ?: return null
    return TitleBranchAnchor(
        userMessageId = user.id,
        userText = user.toText().titleSnapshot(),
        answerMessageId = answerMessageId,
    )
}

/**
 * 标题请求发起时的样子，写回之前要在写锁里按它核对最新状态。
 *
 * @param title 发起时看到的标题：期间标题被别的路径改过，这一版就作废
 * @param source 发起时标题的来路：期间被用户手改过（哪怕改成同一个字符串）就作废
 * @param branch 发起时的分支，见 [TitleBranchAnchor]
 * @param force 用户手动点的重新生成：只核对会话是否被换掉，标题和分支都随用户意愿
 */
data class TitleWriteGuard(
    val title: String,
    val source: ConversationTitleSource,
    val branch: TitleBranchAnchor?,
    val force: Boolean,
) {
    fun allows(latest: Conversation): Boolean {
        if (force) return true
        val state = latest.titleState
        if (state.source != source || !state.isAuto) return false
        if (latest.title != title) return false
        val branch = branch ?: return false
        return branch.isIntactIn(latest.currentMessages)
    }
}
