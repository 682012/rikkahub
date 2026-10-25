package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.MessageNodeMeta
import me.rerere.rikkahub.data.db.dao.MessageNodeSnapshot
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

/**
 * 节点读取底层查询的抽象，只为让分页降级逻辑能脱离 Room 测试。
 */
internal interface NodePageSource {
    /** 整页读取（含 messages 整段 JSON），行太大时由底层抛异常。 */
    suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity>

    /** 轻量 metadata：id / node_index / select_index 以及 SQLite 里的长度，不含 messages。 */
    suspend fun readMeta(): List<MessageNodeMeta>

    /** 按 SQLite `length()`（code point）从 1 开始的下标切出 messages 的一个片段。 */
    suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int): String?
}

/**
 * 读完全部节点：正常走分页快路径；某页因为行太大装不进 CursorWindow 时，**整体**切换成
 * metadata + `substr` 分片回退。
 *
 * 语义要点（这正是本函数存在的理由）：
 * - 绝不静默跳页，也绝不把读不到的行当成“已被删除” —— 少读一个节点，下一次保存就会把库里剩下的删掉。
 * - 只有明确的溢出信号才降级；`IllegalStateException` 只有带溢出特征时才算，其它原样抛出。
 * - 取消（`CancellationException`）与普通 SQL 错误向上传播，绝不返回半页。
 */
internal suspend fun loadAllNodesResilient(
    pageSize: Int,
    chunkCodepoints: Int,
    source: NodePageSource,
): List<MessageNodeEntity> {
    val result = ArrayList<MessageNodeEntity>()
    var offset = 0
    while (true) {
        val page = try {
            source.readPage(pageSize, offset)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isCursorWindowOverflow(e)) throw e
            // 已经读到的部分直接丢弃，改用完整的分片读取重建，避免快慢两套结果拼在一起
            return loadNodesChunked(source, chunkCodepoints)
        }
        if (page.isEmpty()) break
        result += page
        offset += page.size
    }
    return result
}

/**
 * Read the newest nodes, returning them in chronological order. The metadata query is also limited
 * to the requested tail, so one large recent row never forces the whole conversation into memory.
 */
internal suspend fun loadTailNodesResilient(
    limit: Int,
    chunkCodepoints: Int,
    readTail: suspend (Int) -> List<MessageNodeEntity>,
    readTailMeta: suspend (Int) -> List<MessageNodeMeta>,
    readSlice: suspend (String, Int, Int) -> String?,
): List<MessageNodeEntity> {
    val boundedLimit = limit.coerceIn(1, 100)
    return loadAllNodesResilient(
        pageSize = boundedLimit,
        chunkCodepoints = chunkCodepoints,
        source = object : NodePageSource {
            override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> =
                if (offset == 0) readTail(boundedLimit).reversed() else emptyList()

            override suspend fun readMeta(): List<MessageNodeMeta> = readTailMeta(boundedLimit)

            override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int): String? =
                readSlice.invoke(nodeId, start, length)
        },
    )
}

/**
 * 分片回退：先读轻量 metadata（不会溢出），再逐节点用 `substr` 按 code point 切片拼回完整 JSON。
 *
 * 任何一片读失败（返回 null 或抛错）都向上抛，保证调用方看到的是“明确失败”，而不是缺了内容的会话。
 */
internal suspend fun loadNodesChunked(
    source: NodePageSource,
    chunkCodepoints: Int,
): List<MessageNodeEntity> {
    require(chunkCodepoints > 0) { "chunkCodepoints must be positive" }
    val metas = source.readMeta()
    return metas.sortedBy { it.nodeIndex }.map { meta ->
        val builder = StringBuilder(minOf(meta.messagesLength.coerceAtLeast(0), chunkCodepoints))
        // SQLite substr 是 1 基、按 code point 计数，所以 start 从 1 开始按块累加
        var start = 1
        while (start <= meta.messagesLength) {
            val slice = source.readMessagesSlice(meta.id, start, chunkCodepoints)
                ?: throw IllegalStateException(
                    "Failed to read message node ${meta.id} at offset $start/${meta.messagesLength}"
                )
            builder.append(slice)
            val consumed = codepointLength(slice)
            if (consumed <= 0) {
                throw IllegalStateException(
                    "Empty slice for message node ${meta.id} at offset $start/${meta.messagesLength}"
                )
            }
            start += consumed
        }
        val messages = builder.toString()
        if (codepointLength(messages) != meta.messagesLength) {
            throw IllegalStateException(
                "Chunked read of node ${meta.id} returned ${codepointLength(messages)} " +
                    "codepoints but SQLite reports ${meta.messagesLength}"
            )
        }
        MessageNodeEntity(
            id = meta.id,
            conversationId = meta.conversationId,
            nodeIndex = meta.nodeIndex,
            messages = messages,
            selectIndex = meta.selectIndex,
        )
    }
}

/**
 * 读取用于增量保存比较的节点快照（id / 顺序 / select_index / messages 全文）。
 *
 * `getSnapshots` 一次读全部行，同样可能撞上 CursorWindow：溢出时整体走分片回退，
 * 保证“内容有没有变 / 有没有节点被删”的判断永远基于完整数据。
 */
internal suspend fun loadSnapshotsResilient(
    readSnapshots: suspend () -> List<MessageNodeSnapshot>,
    fallback: suspend () -> List<MessageNodeSnapshot>,
): List<MessageNodeSnapshot> {
    return try {
        readSnapshots()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!isCursorWindowOverflow(e)) throw e
        fallback()
    }
}

/**
 * 是否属于“这一行太大，装不进 CursorWindow”。
 *
 * Android 上 SQLite 抛 `SQLiteBlobTooBigException`；Room 有时把它包成 `IllegalStateException("... CursorWindow ...")`。
 * 只有消息里明确提到窗口/太大时才当作可降级，其它 `IllegalStateException` 不是我们的场景。
 */
internal fun isCursorWindowOverflow(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 8) {
        if (current.javaClass.simpleName == "SQLiteBlobTooBigException") return true
        val message = current.message.orEmpty()
        if (message.contains("CursorWindow", ignoreCase = true) ||
            message.contains("Row too big", ignoreCase = true) ||
            message.contains("blob too big", ignoreCase = true) ||
            message.contains("too big to fit", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

/** 按 code point（= SQLite `length()`）计算长度。 */
internal fun codepointLength(text: String): Int = text.codePointCount(0, text.length)

/**
 * 按 code point 切分文本，每片最多 [chunkCodepoints] 个 code point。
 *
 * 用于测试与预览：SQLite 的 `substr` 与 `length()` 按 code point 计数，而 Kotlin 的 `String.length`
 * 是 UTF-16 单元数 —— 含 emoji（代理对）时两者不同，必须按 code point 切，否则边界会落在代理对中间。
 */
internal fun splitByCodepoints(text: String, chunkCodepoints: Int): List<String> {
    require(chunkCodepoints > 0) { "chunkCodepoints must be positive" }
    if (text.isEmpty()) return emptyList()
    val chunks = ArrayList<String>((text.length / chunkCodepoints) + 1)
    var index = 0
    while (index < text.length) {
        var end = index
        var consumed = 0
        while (end < text.length && consumed < chunkCodepoints) {
            val codePoint = text.codePointAt(end)
            end += Character.charCount(codePoint)
            consumed++
        }
        chunks += text.substring(index, end)
        index = end
    }
    return chunks
}
