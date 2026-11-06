package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import kotlin.uuid.Uuid

/** 收件箱里等待发给模型的一项 */
@Serializable
sealed interface InboxItem {
    val id: Uuid
}

/** 用户输入：等当前这一轮结束后各自开一轮 */
@Serializable
@SerialName("message")
data class QueuedMessage(
    override val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    // 只活在内存里，不进持久层：重启后由新的调用方重新决定怎么等。
    @Transient
    val reply: CompletableDeferred<String?>? = null,
    /**
     * 落库确认：用户消息写进会话后 complete(true)，初始化失败/任务取消/被撤回时 complete(false)。
     * 调用方据此决定什么时候可以安全清掉输入草稿，不影响语音的 [reply]。
     * 同样的原因不能持久化：重启后没有任何等待者。
     */
    @Transient
    val persisted: CompletableDeferred<Boolean>? = null,
    /**
     * 用户在模型生成期间发来的输入：允许在运行中插进当前这一轮（下一步之前），
     * 不必等这一轮结束。默认 false 保持旧语义（只排队，下一轮再发）。
     */
    val deliverDuringRun: Boolean = false,
) : InboxItem

/**
 * 运行时事件，例如后台命令结束：正在生成时在下一步请求模型前插进这一轮，空闲时单独开一轮。
 * [key] 标识事件来源，来源可以据此撤回已经不需要发送的通知。
 */
@Serializable
@SerialName("notice")
data class QueuedNotice(
    override val id: Uuid = Uuid.random(),
    val key: String,
    val notice: UIMessagePart.Notice,
    /** Passive tool completions are consumed by an existing/user-started turn, never start one. */
    val wakeOnArrival: Boolean = true,
) : InboxItem

/**
 * 队列暂停的原因。只决定「网络恢复了能不能自己继续」：
 * 用户手动停止和出错都要用户自己处理，网络恢复只能清掉 [NETWORK]。
 */
@Serializable
enum class InboxPauseReason {
    NONE,
    USER,
    ERROR,
    NETWORK,
}

@Serializable
data class InboxState(
    val items: List<InboxItem> = emptyList(),
    val paused: Boolean = false,
    val pauseReason: InboxPauseReason = InboxPauseReason.NONE,
    /**
     * 已经出队、还没确认写进会话的那一条。只在带持久层时使用，重启后回到队首重发。
     * 它同时是附件引用检查的输入：这条消息的附件在 ack 之前都不能删。
     */
    val inFlight: QueuedMessage? = null,
    val ownerAssistantId: Uuid? = null,
    val mutedNoticeKeys: Set<String> = emptySet(),
) {
    val messages: List<QueuedMessage> get() = items.filterIsInstance<QueuedMessage>()
    val notices: List<QueuedNotice> get() = items.filterIsInstance<QueuedNotice>()

    /** 让 [paused] 和 [pauseReason] 互相自洽；旧版本只存了 paused=true 的文件按 ERROR 处理 */
    fun normalized(): InboxState = when {
        paused && pauseReason == InboxPauseReason.NONE -> copy(pauseReason = InboxPauseReason.ERROR)
        !paused && pauseReason != InboxPauseReason.NONE -> copy(pauseReason = InboxPauseReason.NONE)
        else -> this
    }
}

/** 收件箱决定的下一轮 */
sealed interface InboxTurn {
    /** 发送这条用户输入；等待中的通知在这一轮第一步之前一起取走 */
    data class Message(val message: QueuedMessage) : InboxTurn

    /** 只有通知在等，单独开一轮；通知在开始时用 [ConversationInbox.takeNotices] 取 */
    data object Notices : InboxTurn
}

/** Pending input is kept outside conversation history until dispatched. */
class MessageQueuePausedException : IllegalStateException()

/**
 * 会话收件箱：等待发给模型的用户输入和运行时事件，进入会话历史之前都放在这里。
 *
 * 暂停只挡住开始新的一轮；正在进行的一轮仍然会在步骤之间取走通知。
 *
 * 传了 [storage] 就是可靠队列：每次改动都先把新状态落盘，写成功才发布到内存，
 * 写失败直接抛给调用方、内存和回执都保持原样。进程被杀以后 [restore] 把没确认的那一条
 * 放回队首继续发，队列不会因为崩溃丢掉用户输入。
 *
 * 两个例外，都是为了「内存不能落在数据库后面」：[pause] 落盘失败也照样在内存里暂停，
 * [migrateOwner] 落盘失败也照样切换 owner（异常仍然抛出），两种情况下磁盘内容由下一次
 * 成功的写入补齐。
 *
 * 不传 [storage] 时行为和以前完全一样：纯内存、出队即移除、没有 inFlight 牵制。
 */
class ConversationInbox(
    private val storage: ConversationInboxStorage? = null,
    private val onPersistenceFailure: (Throwable) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(InboxState())
    val state = mutableState.asStateFlow()
    private var restored = false

    /**
     * 用持久层里的内容恢复队列。没有文件返回 null（表示新会话，队列保持原样）。
     *
     * 文件坏了会抛出并保留文件，方便事后人工恢复；不会把坏文件当成空队列覆盖掉。
     * 只读一个小文件，调用方自己安排到 IO 线程上跑。
     *
     * 恢复时 [InboxState.inFlight] 放回队首并清掉编辑占位，否则重启后会一直卡住；
     * 同一 id 已经在内存里的不会重复入队。
     */
    @Synchronized
    fun restore(): InboxState? {
        if (restored) return if (storage != null) state.value else null
        val store = storage ?: run { restored = true; return null }
        val stored = store.read()?.normalized() ?: run { restored = true; return null }
        val current = mutableState.value
        val recovered = buildList<InboxItem> {
            stored.inFlight?.let { add(it.copy(isEditing = false)) }
            stored.items.forEach { item ->
                add(if (item is QueuedMessage) item.copy(isEditing = false) else item)
            }
        }
        val merged = (recovered + current.items).distinctBy { it.id }
        val wasEditing = stored.messages.any { it.isEditing }
        val next = InboxState(
            items = merged,
            paused = current.paused || stored.paused || wasEditing,
            pauseReason = if (current.paused) current.pauseReason else if (wasEditing) InboxPauseReason.USER else stored.pauseReason,
            inFlight = current.inFlight,
            ownerAssistantId = current.ownerAssistantId ?: stored.ownerAssistantId,
            mutedNoticeKeys = stored.mutedNoticeKeys + current.mutedNoticeKeys,
        ).normalized()
        mutableState.value = next
        restored = true
        return next
    }

    /**
     * 受理一条用户输入。[id] 可以传用户消息固定的 UUID，重启后同一份输入不会入队两次。
     *
     * 返回 null 表示内容为空、根本没有受理；返回的 [QueuedMessage] 表示已经受理
     * （带持久层时已经落盘）。同一 id 已经在队列或正在发送时返回原来那条，不重复入队。
     */
    @Synchronized
    fun enqueue(
        parts: List<UIMessagePart>,
        answer: Boolean = true,
        reply: CompletableDeferred<String?>? = null,
        persisted: CompletableDeferred<Boolean>? = null,
        id: Uuid = Uuid.random(),
        deliverDuringRun: Boolean = false,
    ): QueuedMessage? {
        restore()
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            persisted?.complete(false)
            return null
        }
        val current = mutableState.value
        val duplicate = current.messages.firstOrNull { it.id == id }
            ?: current.inFlight?.takeIf { it.id == id }
        if (duplicate != null) return duplicate
        val message = QueuedMessage(
            id = id,
            parts = parts.toList(),
            answer = answer,
            reply = reply,
            persisted = persisted,
            deliverDuringRun = deliverDuringRun,
        )
        try {
            commit(current.copy(items = current.items + message))
        } catch (e: Exception) {
            // 没受理就不能留下「已经收下」的假象：回执给 false，异常继续往上抛
            persisted?.complete(false)
            reply?.completeExceptionally(e)
            throw e
        }
        return message
    }

    /** 放入一条通知；同一来源已有没发出的通知时原地替换 */
    @Synchronized
    fun post(key: String, notice: UIMessagePart.Notice, wakeOnArrival: Boolean = true): QueuedNotice? {
        restore()
        val current = mutableState.value
        if (key in current.mutedNoticeKeys) return null
        val index = current.items.indexOfFirst { it is QueuedNotice && it.key == key }
        val next = if (index == -1) {
            current.copy(items = current.items + QueuedNotice(key = key, notice = notice, wakeOnArrival = wakeOnArrival))
        } else {
            current.copy(
                items = current.items.toMutableList()
                    .apply { set(index, (get(index) as QueuedNotice).copy(notice = notice, wakeOnArrival = wakeOnArrival)) },
            )
        }
        commit(next)
        return next.items.getOrNull(if (index == -1) next.items.lastIndex else index) as? QueuedNotice
    }

    /** 撤回还没发出的通知；已经发出的不受影响 */
    @Synchronized
    fun withdraw(key: String) {
        restore()
        val current = mutableState.value
        if (current.items.none { it is QueuedNotice && it.key == key }) return
        commit(current.copy(items = current.items.filterNot { it is QueuedNotice && it.key == key }))
    }

    /**
     * 取出下一轮：先发用户输入，只剩通知时单独开一轮。
     * 正在编辑的队首挡住后面的用户输入，但不挡通知。
     *
     * 带持久层时出队会同时把这一条记进 [InboxState.inFlight] 并落盘，
     * 写盘失败就抛异常、消息仍留在队列里。上一条还没 [acknowledge] 之前不再取新的用户输入，
     * 否则 inFlight 会被覆盖、那条输入就丢了。
     */
    @Synchronized
    fun takeNext(): InboxTurn? {
        restore()
        val current = mutableState.value
        if (current.paused) return null
        val blockedByInFlight = storage != null && current.inFlight != null
        val head = if (blockedByInFlight) null else current.messages.firstOrNull()
        if (head != null && !head.isEditing) {
            if (storage == null) {
                // 旧语义：纯内存出队即移除，没有任何牵制
                mutableState.value = current.copy(items = current.items.filterNot { it.id == head.id })
            } else {
                commit(
                    current.copy(
                        items = current.items.filterNot { it.id == head.id },
                        inFlight = head.copy(isEditing = false),
                    ),
                )
            }
            return InboxTurn.Message(head)
        }
        return InboxTurn.Notices.takeIf { current.notices.any { it.wakeOnArrival } }
    }

    /** 取出全部通知，按放入顺序；不受暂停影响 */
    @Synchronized
    fun takeNotices(): List<UIMessagePart.Notice> {
        restore()
        val current = mutableState.value
        val notices = current.notices
        if (notices.isEmpty()) return emptyList()
        commit(current.copy(items = current.items.filterNot { it is QueuedNotice }))
        return notices.map { it.notice }
    }

    /**
     * 当前待发通知的快照，按放入顺序，**不删除任何东西**（[restore] 之后的状态）。
     *
     * 给「通知先被模型消费、之后才确认」的路径用：调用方拿这份快照把通知塞进会话并 flush，
     * 成功后再用同一份快照调 [acknowledgeNotices]。中途失败或进程被杀，通知都还在队列里，
     * 重启后 [restore] 会把它放回队首重发，不会因为「已经出队」而丢掉结果。
     */
    @Synchronized
    fun peekNotices(): List<QueuedNotice> {
        restore()
        return mutableState.value.notices
    }

    /**
     * 交付确认：只移除仍然与 [delivered] 里那条**完全相等**的通知。
     *
     * 用整条 [QueuedNotice] 判等（含 id、key、内容、唤醒标记）而不是按 key/id 匹配：
     * 交付期间同一来源又 [post] 了新内容时，那条已经是新版本、还没被模型看见，
     * 不能被旧快照顺带删掉，继续留在队列里等下一轮。已 [withdraw] 或已交付的条目
     * 因为不在队列里，匹配不到就什么都不做，重复确认是安全的。
     *
     * 落盘失败时抛给调用方，内存和磁盘都保持原状（通知仍在队列里），
     * 不会出现「内存说已确认、磁盘还留着」的半截状态。
     */
    @Synchronized
    fun acknowledgeNotices(delivered: List<QueuedNotice>) {
        restore()
        if (delivered.isEmpty()) return
        val targets = delivered.toSet()
        val current = mutableState.value
        val remaining = current.items.filterNot { it is QueuedNotice && it in targets }
        if (remaining.size == current.items.size) return
        commit(current.copy(items = remaining))
    }

    /**
     * 运行中插话的候选快照：**不移除**任何条目，供主代理塞进正在生成的那一轮。
     *
     * 只取队首连续的、可插话的用户输入（[QueuedMessage.deliverDuringRun] 且非编辑中、要模型回答、
     * 全是纯文本）；遇到第一条普通排队输入就停，绝不允许插话跨过它抢序。通知不参与、也不挡路：
     * 它们由 [takeNotices] 在每步开头单独取走。
     *
     * 上限 [STEERING_MAX_MESSAGES] 条 / [STEERING_MAX_TEXT] 字。第一条本身就超长时返回空：
     * 宁可让它正常走下一轮（[takeNext] 照常发它），也不在这里吞掉。
     *
     * 暂停或还有一条 [InboxState.inFlight] 时返回空：那一轮还没确认落库，插进去会让两条输入乱序。
     */
    @Synchronized
    fun peekSteering(): List<QueuedMessage> {
        restore()
        val current = mutableState.value
        if (current.paused || current.inFlight != null) return emptyList()
        val picked = mutableListOf<QueuedMessage>()
        var textLength = 0
        for (item in current.items) {
            // 通知不是用户输入：既不取也不挡，本轮开头会用 takeNotices 单独取走
            if (item !is QueuedMessage) continue
            if (picked.size >= STEERING_MAX_MESSAGES) break
            if (!item.steerable()) break
            // 和 shouldSteerInput 同一口径：按拼接后真正发出去的长度算（含文本之间的换行）
            val length = steeringTextLength(item.parts)
            if (textLength + length > STEERING_MAX_TEXT) break
            picked += item
            textLength += length
        }
        return picked
    }

    /**
     * 插话交付确认：只移除仍然与 [delivered] 里那条**完全相等**的用户输入（含 id、parts、
     * answer、deliverDuringRun 等全部字段）。
     *
     * 整条判等而不是按 id 匹配，理由同 [acknowledgeNotices]：交付期间用户可能编辑了同一条
     * （[finishEdit] 换掉 parts），那是还没送到模型的新内容，不能被旧快照顺带删掉。
     * 不动 [InboxState.inFlight]，也不动任何通知。
     *
     * 先落盘再 complete(persisted, true)：写盘失败时异常抛给调用方，队列原样保留，
     * 重启后 [restore] 会把这些输入放回队首，不会既没插进会话又丢了输入。
     */
    @Synchronized
    fun acknowledgeSteering(delivered: List<QueuedMessage>) {
        restore()
        if (delivered.isEmpty()) return
        val targets = delivered.toSet()
        val current = mutableState.value
        val remaining = current.items.filterNot { it is QueuedMessage && it in targets }
        if (remaining.size != current.items.size) commit(current.copy(items = remaining))
        // 已经插进会话并落库：草稿可以清了。重复确认在这里是空操作。
        delivered.forEach { it.persisted?.complete(true) }
    }

    /** 关掉某一条的运行中插话；已经插进去的内容不受影响，它只是不再参与后续插话 */
    @Synchronized
    fun clearSteeringMode(id: Uuid): QueuedMessage? {
        restore()
        val current = mutableState.value
        val target = current.messages.find { it.id == id && it.deliverDuringRun } ?: return null
        commit(
            current.copy(
                items = current.items.map {
                    if (it is QueuedMessage && it.id == id) it.copy(deliverDuringRun = false) else it
                },
            ),
        )
        return target
    }

    /**
     * 这一条已经写进会话（数据库已 flush），可以从 [InboxState.inFlight] 清掉。
     * 返回 false 表示它不是当前正在发送的那条，什么都没改。
     */
    @Synchronized
    fun acknowledge(id: Uuid): Boolean {
        restore()
        val current = mutableState.value
        val inFlight = current.inFlight ?: return false
        if (inFlight.id != id) return false
        commit(current.copy(inFlight = null))
        return true
    }

    /**
     * 发送失败：把正在发送的那条放回队首留着重试，不丢用户输入。
     * [reason] 只给调用方自己记日志用，不改变队列内容。
     */
    @Synchronized
    fun requeueInFlight(reason: String? = null): QueuedMessage? {
        restore()
        val current = mutableState.value
        val inFlight = current.inFlight ?: return null
        val requeued = inFlight.copy(isEditing = false, reply = null, persisted = null)
        commit(
            current.copy(
                items = listOf(requeued) + current.items.filterNot { it.id == requeued.id },
                inFlight = null,
            ),
        )
        return requeued
    }

    @Synchronized
    fun remove(id: Uuid): InboxItem? {
        restore()
        val current = mutableState.value
        val removed = current.items.find { it.id == id } ?: return null
        commit(current.copy(items = current.items.filterNot { it.id == id }))
        (removed as? QueuedMessage)?.reply?.complete(null)
        // 从队列里撤回的输入不会被落库了，草稿要还给用户
        (removed as? QueuedMessage)?.persisted?.complete(false)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        restore()
        val current = mutableState.value
        val message = current.messages.find { it.id == id && !it.isEditing } ?: return null
        commit(
            current.copy(
                items = current.items.map { if (it.id == id) message.copy(isEditing = true) else it },
            ),
        )
        return message
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        restore()
        if (parts != null && parts.isEmptyInputMessage()) return null
        val current = mutableState.value
        val previous = current.messages.find { it.id == id } ?: return null
        commit(
            current.copy(
                items = current.items.map {
                    if (it.id == id) {
                        previous.copy(parts = parts?.toList() ?: previous.parts, isEditing = false)
                    } else {
                        it
                    }
                },
            ),
        )
        // 取消编辑只释放占位，不能清理原附件。
        return previous.takeIf { parts != null }
    }

    /**
     * 暂停队列。默认 [InboxPauseReason.ERROR] 保持旧调用点的语义：只有 [resume] 能继续。
     *
     * 人工停止（[InboxPauseReason.USER]）和出错不会被网络恢复清掉，
     * 已经在暂停的状态只允许往更「需要人」的方向升级。
     */
    @Synchronized
    fun pause(reason: InboxPauseReason = InboxPauseReason.ERROR) {
        val readable = try {
            restore()
            true
        } catch (e: Exception) {
            // A corrupt/unreadable queue must not crash a generation completion callback or be overwritten.
            runCatching { onPersistenceFailure(e) }
            false
        }
        val current = mutableState.value
        val nextReason = stickyReason(current, reason)
        if (!current.paused || current.pauseReason != nextReason) {
            val next = current.copy(paused = true, pauseReason = nextReason)
            try {
                if (readable) commit(next) else mutableState.value = next
            } catch (e: Exception) {
                runCatching { onPersistenceFailure(e) }
                // 暂停是「停下来」的开关：写盘失败也必须在内存里生效，否则这一轮结束后队列会
                // 继续把用户刚停掉的输入发出去。这条路径从生成任务的完成回调里进来，让异常
                // 冒出去会变成未捕获异常；磁盘上的暂停标记由下一次成功的写入补上。
                mutableState.value = next
            }
        }
        mutableState.value.messages.forEach {
            it.reply?.completeExceptionally(MessageQueuePausedException())
            // Input stays queued. Keep its persistence receipt alive for a later resume.
        }
    }

    fun failReplyWaiters(message: String) {
        state.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
            // Approval pauses the turn, not the queued message's eventual persistence.
        }
    }

    /** 用户手动继续；任何原因造成的暂停都能清掉 */
    @Synchronized
    fun resume() {
        restore()
        val current = mutableState.value
        if (current.paused || current.pauseReason != InboxPauseReason.NONE) {
            commit(current.copy(paused = false, pauseReason = InboxPauseReason.NONE))
        }
    }

    /**
     * 网络恢复后自动继续：只有 [InboxPauseReason.NETWORK] 造成的暂停才清掉。
     * 返回 true 表示队列已经恢复，false 表示这次暂停要人来处理（或本来就没暂停）。
     */
    @Synchronized
    fun resumeAfterNetwork(): Boolean {
        restore()
        val current = mutableState.value
        if (!current.paused || current.pauseReason != InboxPauseReason.NETWORK) return false
        commit(current.copy(paused = false, pauseReason = InboxPauseReason.NONE))
        return true
    }

    @Synchronized
    fun bindOwner(assistantId: Uuid) {
        restore()
        val current = state.value
        if (current.ownerAssistantId == assistantId) return
        check(current.ownerAssistantId == null || (current.messages.isEmpty() && current.inFlight == null)) {
            "Pending input still belongs to another assistant"
        }
        if (current.ownerAssistantId != null) {
            migrateOwner(assistantId)
        } else {
            commit(current.copy(ownerAssistantId = assistantId))
        }
    }

    /**
     * User-confirmed ownership transfer never carries the old assistant's queued notices.
     *
     * 调用这个的时候数据库里的 owner 已经换掉了（调用方先落库再改内存），所以内存里的 owner
     * 必须跟着换：落盘失败只该丢掉「旧通知已作废」这份记录，不能让内存停在旧 owner 上，
     * 否则下一次 dispatch 会把旧助手的通知当成新助手的输入发出去。写失败时内存照旧切换到
     * 新状态，异常继续抛给调用方，下一次成功的写入会把整份新状态补上。
     */
    @Synchronized
    fun migrateOwner(assistantId: Uuid, previousNoticeKeys: Set<String> = emptySet()) {
        restore()
        val current = state.value
        check(current.messages.isEmpty() && current.inFlight == null) {
            "Pending messages must be handled before moving the conversation"
        }
        val next = InboxState(
            ownerAssistantId = assistantId,
            mutedNoticeKeys = (current.mutedNoticeKeys + previousNoticeKeys + current.notices.map { it.key })
                .takeLastSet(MUTED_NOTICE_KEY_LIMIT),
        )
        try {
            commit(next)
        } catch (e: Exception) {
            // 数据库里已经是新助手了：内存跟着换，落盘留给下一次写入
            mutableState.value = next
            throw e
        }
    }

    /** 先把新状态落盘，写成功才发布；写失败时内存保持旧状态，异常交给调用方 */
    private fun commit(next: InboxState) {
        val store = storage
        if (store != null) store.write(next)
        mutableState.value = next
    }

    /** 已经暂停时只能升级到更需要人处理的原因，别让网络抖动把人工停止的队列自动放走 */
    private fun stickyReason(current: InboxState, reason: InboxPauseReason): InboxPauseReason {
        val requested = if (reason == InboxPauseReason.NONE) InboxPauseReason.ERROR else reason
        if (!current.paused) return requested
        return when {
            requested == InboxPauseReason.USER || current.pauseReason == InboxPauseReason.USER -> InboxPauseReason.USER
            current.pauseReason == InboxPauseReason.ERROR -> InboxPauseReason.ERROR
            requested == InboxPauseReason.ERROR -> requested
            else -> InboxPauseReason.NETWORK
        }
    }
}

private fun Set<String>.takeLastSet(limit: Int): Set<String> = if (size <= limit) this else toList().takeLast(limit).toSet()

/** 静音键的上限：迁移过的旧任务不会再回来，超出的最老的键可以安全丢掉 */
private const val MUTED_NOTICE_KEY_LIMIT = 512
