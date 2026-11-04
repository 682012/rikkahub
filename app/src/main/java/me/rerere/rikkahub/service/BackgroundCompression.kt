package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.event.CompressionOutcome
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * 一次手动压缩在某个会话上的状态，供聊天页显示。任务本身在 [ChatService] 的 AppScope 里跑，
 * 这个状态只反映最近一次任务，不持有协程。
 */
data class CompressionState(
    val taskId: Uuid,
    /** null 表示还在压缩 */
    val outcome: CompressionOutcome?,
    /** 失败/丢弃的原因，成功时为 null */
    val message: String? = null,
    val keepRecentMessages: Int = 0,
    val startedAt: Long = 0L,
    val finishedAt: Long? = null,
) {
    val isRunning: Boolean get() = outcome == null
}

/** 快照里一个节点的身份：节点 id、选中的分支下标、分支上那条消息的 id */
internal data class CompressionNodeRef(
    val nodeId: Uuid,
    val selectIndex: Int,
    val messageId: Uuid?,
    val content: List<me.rerere.ai.ui.UIMessagePart>?,
)

/**
 * 压缩开始时的边界快照。
 *
 * 压缩请求是一次远程调用，回来时对话可能已经被用户改过。提交前只比较**压缩范围之前**（含范围内最后
 * 一个节点）的节点身份：范围内改了分支、换了消息、删了节点，摘要就对不上了，必须丢弃；
 * 范围之后追加的新消息不影响摘要本身，可以保留（检查点插在范围末尾，新消息仍在它后面）。
 */
internal data class CompressionSnapshot(
    /** 开始时的会话 incarnation，会话被整条替换（删除/撤销删除）后不再写入 */
    val incarnation: Int,
    /** 压缩范围结束下标（不含）：nodes[0, endIndex) 是被压缩的消息 */
    val endIndex: Int,
    val nodes: List<CompressionNodeRef>,
) {
    /** 检查点插在这个节点之后 */
    val targetNodeId: Uuid get() = nodes.last().nodeId
}

/** 取范围 [0, endIndex) 的快照；区间非法时返回 null */internal fun Conversation.compressionSnapshot(endIndex: Int, incarnation: Int): CompressionSnapshot? {
    if (endIndex <= 0 || endIndex > messageNodes.size) return null
    val nodes = messageNodes.subList(0, endIndex).map { node ->
        CompressionNodeRef(
            nodeId = node.id,
            selectIndex = node.selectIndex,
            messageId = node.messages.getOrNull(node.selectIndex)?.id,
            content = node.messages.getOrNull(node.selectIndex)?.parts,
        )
    }
    return CompressionSnapshot(incarnation = incarnation, endIndex = endIndex, nodes = nodes)
}

/** 提交校验的结果 */
internal sealed interface CompressionCommit {
    /** 可以把摘要插到 [afterNodeId] 之后 */
    data class Ready(val afterNodeId: Uuid) : CompressionCommit

    /** 范围内的节点/分支/消息变了，或者会话已被整条替换：旧摘要不安全，丢弃 */
    data object Changed : CompressionCommit

    /** 范围边界已经有检查点（自己或自动压缩插过），再插会出现两条相邻摘要 */
    data object AlreadyCompressed : CompressionCommit
}

/**
 * 在提交摘要前核对快照。必须在会话的同步块内调用（[ConversationSession.tryUpdate] 的变换里），
 * 这样比较的是原子看到的状态。
 */
internal fun Conversation.compressionCommit(
    snapshot: CompressionSnapshot,
    currentIncarnation: Int,
): CompressionCommit {
    if (currentIncarnation != snapshot.incarnation) return CompressionCommit.Changed
    if (snapshot.nodes.isEmpty() || snapshot.nodes.size != snapshot.endIndex) return CompressionCommit.Changed
    if (messageNodes.size < snapshot.endIndex) return CompressionCommit.Changed

    for (index in snapshot.nodes.indices) {
        val ref = snapshot.nodes[index]
        val node = messageNodes[index]
        if (node.id != ref.nodeId) return CompressionCommit.Changed
        if (node.selectIndex != ref.selectIndex) return CompressionCommit.Changed
        val message = node.messages.getOrNull(node.selectIndex)
        if (message?.id != ref.messageId || message?.parts != ref.content) return CompressionCommit.Changed
    }

    // 范围内最后一个节点已经是检查点：这段历史已经压过
    if (messageNodes[snapshot.endIndex - 1].isContextCheckpointNode()) {
        return CompressionCommit.AlreadyCompressed
    }
    // 范围后面紧跟着一条检查点：说明别的压缩（或自动压缩）刚好插在这个边界上
    if (messageNodes.getOrNull(snapshot.endIndex)?.isContextCheckpointNode() == true) {
        return CompressionCommit.AlreadyCompressed
    }
    return CompressionCommit.Ready(snapshot.targetNodeId)
}

/** 选中的分支无效时不抛异常，只当作"不是检查点" */
private fun MessageNode.isContextCheckpointNode(): Boolean =
    messages.getOrNull(selectIndex)?.isContextCheckpoint == true

/**
 * 压缩期间新开始的一轮生成是否正写进被压缩的范围。
 *
 * 范围末尾之后追加的消息可以留（检查点插在它们前面，互不影响）；但如果正在流式写入的那条回复本身
 * 就在范围里，摘要既没包含它的最终内容，插入检查点又会让它同时以"摘要里的"和"原样的"出现，
 * 这种情况直接丢弃。
 */
internal fun CompressionSnapshot.coversStreamingMessage(streamingMessageId: Uuid?): Boolean =
    streamingMessageId != null && nodes.any { it.messageId == streamingMessageId }

