package me.rerere.rikkahub.data.organization

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

/** 家族根：先看谁建得早，同一时刻按 ID，保证同一份数据永远算出同一个根、同一份回写清单。 */
private val createAtThenId: Comparator<Conversation> =
    compareBy<Conversation>({ it.createAt }, { it.id.toString() })

/**
 * 旧会话的家族识别（r21 扩项 B）：从已有的会话里**保守**地认出「谁是谁的分叉」，补上
 * [Conversation.lineageId] 之前留下的空白。
 *
 * 只处理没有 [Conversation.lineageId] 的旧记录；已经有 lineage 的会话是权威，只当证据用，
 * 不改也不并。认不出、证据互相打架、或者只剩可疑线索的，一律当「各自是根」——少认一族的代价
 * 只是列表里多一张卡，错认一族会把两条不同的对话塞进同一张卡，还会让自动归库搬错文件夹。
 *
 * ## 判据只有一条：开头那条真正由用户发出的消息的 ID
 *
 * fork 复制的是整段前缀，消息 ID 原样保留（只有节点 ID 换新），所以「同一个开头用户消息 ID」
 * 是旧数据里唯一靠得住的分叉痕迹。标题、正文、后缀编号、`你好` 这类短问候一律不看：这些内容
 * 相同的原因太多，凭它们并族必然误伤。
 *
 * ## 预设消息必须跳过
 *
 * 新会话会把助手的 `presetMessages` 原样贴进来（`ChatService` 直接
 * `updateCurrentMessages(assistant.presetMessages)`），**消息 ID 不变**，于是同一个助手下
 * 每条会话的首条都是同一个 ID。这种共享 ID 不是分叉，必须按 [presetMessageIds] 跳过；
 * 领先的 assistant/system/tool 节点也不是用户开头，一并跳过，从第一条真正的用户消息比起。
 *
 * ## 预设被删掉的旧数据
 *
 * 用户删过预设之后，[presetMessageIds] 里就没有那个 ID 了，旧会话开头那条共享的 USER 消息
 * 会被误当成「共同开头」。这类模板消息的 [UIMessage.createdAt] 是模板本身被创建/导入的时间，
 * 必然早于用它新建的任何一条会话的 [Conversation.createAt]；而真分叉复制出来的消息，其时间
 * 正是源会话开头那条消息的时间，不早于同族里最早那条会话的创建时间。所以：
 * 消息时间早于「持有这个 ID 的会话中最早的那条」的创建时间 → 判定为老模板，不作为证据。
 *
 * 代价是「源会话已被删除」的真分叉也会一起漏掉（手里剩下的那条会话建得比消息晚，看起来
 * 和老模板一样）。这是有意的取舍：宁可漏认，也不要把两条无关的旧会话并成一族。
 * 导入、时区、时钟偏差同理，一律偏向漏认。
 *
 * 同样的时间判据也管着显式 lineage 的会话：它们本身不用改，但只有当开头证据不像老模板时才
 * 允许被当成锚点。否则一个被删掉的预设会把整个助手的旧会话全吸进那个家族。
 *
 * fork 本身从 r21 起直接写显式 [Conversation.lineageId]；这里只是给旧数据兜底的补救。
 */
object ConversationLineageResolver {

    /**
     * 认出旧记录的家族根。
     *
     * @param conversations 同一个或几个助手的会话开头快照；跨助手一定不会合并，这里也按
     * [Conversation.assistantId] 再分一次组，调用方多传了也不会串。
     * @param presetMessageIds 当前助手的预设消息 ID（`Assistant.presetMessages` 的 id），
     * 用来跳过每条会话开头共享的模板消息。空集合表示没有预设，也允许（老模板另有时间判据）。
     * @return 需要回写 lineage 的旧会话 → 家族根 ID。只包含**确实要改**的会话：已经有
     * [Conversation.lineageId] 的不在里面，本身就被认成根的也不在里面（null 已经等价于自己是根）。
     * 根 ID 可能指向一条已经删掉的会话——显式 lineage 就是这么用的，DAO 折叠照样能算。
     */
    fun infer(
        conversations: List<Conversation>,
        presetMessageIds: Set<Uuid>,
    ): Map<Uuid, Uuid> {
        if (conversations.size < 2) return emptyMap()
        val zone = TimeZone.currentSystemDefault()
        val result = LinkedHashMap<Uuid, Uuid>()
        conversations
            .distinctBy { it.id }
            .groupBy { it.assistantId }
            .forEach { (_, group) ->
                if (group.size >= 2) inferInAssistant(group, presetMessageIds, zone, result)
            }
        return result
    }

    private fun inferInAssistant(
        group: List<Conversation>,
        presetMessageIds: Set<Uuid>,
        zone: TimeZone,
        result: MutableMap<Uuid, Uuid>,
    ) {
        // 稳定顺序：先按创建时间，再按 ID，保证同一份输入算出同一份结果
        val ordered = group.sortedWith(createAtThenId)
        val createAt = ordered.associate { it.id to it.createAt }

        // 每条会话的开头证据：第一条真正用户消息所在节点里的所有变体（消息 ID + 它自己的时间）
        val evidence = LinkedHashMap<Uuid, List<OpeningMessage>>()
        for (conversation in ordered) {
            val opening = firstUserOpening(conversation, presetMessageIds, zone) ?: continue
            evidence[conversation.id] = opening
        }
        if (evidence.isEmpty()) return

        // 一个 ID 最早出现的消息时间，以及哪些会话拿它当开头
        val messageAt = HashMap<Uuid, Instant>()
        val holders = HashMap<Uuid, MutableList<Uuid>>()
        for ((conversationId, opening) in evidence) {
            for (message in opening) {
                val known = messageAt[message.id]
                if (known == null || message.createdAt.isBefore(known)) messageAt[message.id] = message.createdAt
                holders.getOrPut(message.id) { ArrayList() } += conversationId
            }
        }

        // 老模板：共享 ID 的消息比持有它的每一条会话都建得早（源会话已删也是这个形状，同样排除）
        val staleIds = HashSet<Uuid>()
        for ((messageId, createdAt) in messageAt) {
            val earliest = holders[messageId].orEmpty().mapNotNull { createAt[it] }.minOrNull() ?: continue
            if (createdAt.isBefore(earliest)) staleIds += messageId
        }

        // 显式 lineage 是权威：只当锚点。同一个开头 ID 落到两个不同家族上时，谁都别并。
        // 证据本身要是老模板形状（共享 ID 的消息比持有它的会话都早）就不能当锚点，否则一个被删掉的
        // 预设会把整个助手的旧会话全吸进这个家族。
        val explicitRoots = HashMap<Uuid, MutableSet<Uuid>>()
        for (conversation in ordered) {
            val root = conversation.lineageId ?: continue
            for (message in evidence[conversation.id].orEmpty()) {
                if (message.id in staleIds) continue
                explicitRoots.getOrPut(message.id) { LinkedHashSet() } += root
            }
        }

        val clusters = UnionFind()
        val firstHolder = HashMap<Uuid, Uuid>()
        for (conversation in ordered) {
            if (conversation.lineageId != null) continue
            val opening = evidence[conversation.id] ?: continue
            val ids = opening.mapTo(LinkedHashSet<Uuid>()) { it.id }.apply { removeAll(staleIds) }
            if (ids.isEmpty()) continue

            val roots = buildSet { for (id in ids) addAll(explicitRoots[id].orEmpty()) }
            when {
                // 正好能解释成同一个既有家族：挂上去，不参与后面的合并
                roots.size == 1 -> {
                    val root = roots.single()
                    if (root != conversation.id) result[conversation.id] = root
                }
                // 两个不同家族都沾边：证据自相矛盾，这条不认亲
                roots.size > 1 -> Unit
                // 没有既有家族：按共享的开头 ID 做并查集，O(N*k)
                else -> {
                    clusters.add(conversation.id)
                    for (id in ids) {
                        val holder = firstHolder.getOrPut(id) { conversation.id }
                        clusters.union(holder, conversation.id)
                    }
                }
            }
        }

        val components = LinkedHashMap<Uuid, MutableList<Conversation>>()
        for (conversation in ordered) {
            val root = clusters.rootOf(conversation.id) ?: continue
            components.getOrPut(root) { ArrayList() } += conversation
        }
        for (members in components.values) {
            // 只认至少两条会话的家族；孤单一条没有共享证据，不能算认过亲
            if (members.size < 2) continue
            val root = members.minWith(createAtThenId)
            for (member in members) {
                if (member.id != root.id) result[member.id] = root.id
            }
        }
    }

    /**
     * 第一条真正由用户发出的消息所在节点：[conversation] 的开头证据，找不到就返回 null。
     *
     * 从头往后跳：预设消息（ID 在 [presetMessageIds] 里，同助手每条会话共享）、领先的
     * assistant/system/tool 节点。第一个「当前消息是用户、又不属于预设」的节点就是用户开头，
     * 拿它的全部变体（同一节点里的其他重新生成版本）当 ID 集合。
     */
    private fun firstUserOpening(
        conversation: Conversation,
        presetMessageIds: Set<Uuid>,
        zone: TimeZone,
    ): List<OpeningMessage>? {
        for (node in conversation.messageNodes) {
            val messages = node.messages
            // 结构异常（空节点、选中下标越界）不再猜：这条会话直接不参与
            if (messages.isEmpty()) return null
            val current = messages.getOrNull(node.selectIndex) ?: return null
            if (current.id in presetMessageIds) continue
            if (current.role != MessageRole.USER) continue
            val opening = messages
                .filter { it.role == MessageRole.USER && it.id !in presetMessageIds }
                .map { OpeningMessage(it.id, it.createdInstant(zone)) }
            if (opening.isEmpty()) continue
            return opening
        }
        return null
    }

    private fun UIMessage.createdInstant(zone: TimeZone): Instant =
        createdAt.toInstant(zone).toJavaInstant()

    /** 开头节点里的一条候选消息（变体之一）。 */
    private data class OpeningMessage(val id: Uuid, val createdAt: Instant)

    /**
     * 只做合并用的并查集，带路径压缩。
     *
     * 每条会话只按自己的开头 ID 集合合并，总代价 O(N*k)（k 是开头节点里的变体数），
     * 不会退化成两两比较。谁当树根不影响结果——家族根是按 [Conversation.createAt] 和 ID
     * 另外挑的，这里只管「哪些会话连在一起」。
     */
    private class UnionFind {
        private val parent = HashMap<Uuid, Uuid>()

        fun add(value: Uuid) {
            parent.getOrPut(value) { value }
        }

        fun rootOf(value: Uuid): Uuid? {
            if (value !in parent) return null
            var root = value
            while (true) {
                val next = parent.getValue(root)
                if (next == root) break
                root = next
            }
            var current = value
            while (current != root) {
                val next = parent.getValue(current)
                parent[current] = root
                current = next
            }
            return root
        }

        fun union(a: Uuid, b: Uuid) {
            add(a)
            add(b)
            val rootA = rootOf(a) ?: return
            val rootB = rootOf(b) ?: return
            if (rootA == rootB) return
            // 按 ID 定根，和输入顺序无关，同一份数据永远合并出同一个形状
            if (rootA.toString() <= rootB.toString()) parent[rootB] = rootA else parent[rootA] = rootB
        }
    }
}
