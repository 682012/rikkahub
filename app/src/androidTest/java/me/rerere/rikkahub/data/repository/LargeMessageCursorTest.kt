package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.CursorWindow
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteCursor
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeMeta
import me.rerere.rikkahub.data.db.dao.MessageNodeSnapshot
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * 大消息（单行超出 CursorWindow）回退路径的真机/SQLite 级测试。
 *
 * 和 `app/src/test` 里的 FakeNodeSource 不同，这里全部走真实 SQLite：
 * - 库用 Room + 生产同一套 `RequerySQLiteOpenHelperFactory`，表结构就是 schema 34 的 `message_node`；
 * - 读全部用真实 `MessageNodeDAO` 的 SQL（paged / getNodeMeta / getMessagesSlice / getLastNodes /
 *   getLastNodeMeta / getSnapshots），事务边界与 `ConversationRepository` 一致（`withTransaction`）；
 * - `messages` 是真实 `List<UIMessage>` 的 JSON，塞满 emoji 代理对，用来验证
 *   「SQLite `length()`/`substr()` 按 code point 计数」与 Kotlin 的 UTF-16 下标是两套语义。
 *
 * 刻意不做的事：
 * - 不伪造 CursorWindow 异常。超大行是真实写进库的，失败来自真实的 SQLite 读取。
 * - 生产 requery fork 创建 32 MiB 窗口，4.8MB 快路径已实测。溢出用该驱动原生 2 MiB
 *   CursorWindow 构造器显式配置，SQL 真实读取产生错误，不用反射也不伪造异常。
 */
@RunWith(AndroidJUnit4::class)
class LargeMessageCursorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private lateinit var nodeDao: MessageNodeDAO

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .build()
        nodeDao = database.messageNodeDao()
        // message_node 对 conversation 有 CASCADE 外键，节点必须挂在真实会话下
        database.conversationDao().insert(
            ConversationEntity(
                id = CONVERSATION_ID,
                assistantId = "assistant-large-message",
                title = "large message fixture",
                nodes = "[]",
                createAt = 1L,
                updateAt = 1L,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    /** 与 [ConversationRepository.nodePageSource] 一一对应的真实 DAO 接线。 */
    private val source: NodePageSource
        get() = object : NodePageSource {
            override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> =
                nodeDao.getNodesOfConversationPaged(CONVERSATION_ID, limit, offset)

            override suspend fun readMeta(): List<MessageNodeMeta> = nodeDao.getNodeMeta(CONVERSATION_ID)

            override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int): String? =
                nodeDao.getMessagesSlice(nodeId, start, length)
        }

    private fun boundedWindowSource(): NodePageSource = object : NodePageSource {
        override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> {
            val nativeDb = database.openHelper.readableDatabase as SQLiteDatabase
            return nativeDb.rawQueryWithFactory(
                { _, driver, editTable, query ->
                    SQLiteCursor(driver, editTable, query).apply {
                        window = CursorWindow("test-bounded-window", 2 * 1024 * 1024)
                    }
                },
                "SELECT id, conversation_id, node_index, messages, select_index FROM message_node " +
                    "WHERE conversation_id = ? ORDER BY node_index LIMIT ? OFFSET ?",
                arrayOf<Any>(CONVERSATION_ID, limit, offset), null,
            ).use { raw ->
                val cursor = raw as SQLiteCursor
                assertEquals("factory creates an unpositioned cursor", -1, cursor.position)
                assertEquals(2 * 1024 * 1024, cursor.window.windowSizeBytes)
                buildList {
                    while (cursor.moveToNext()) add(MessageNodeEntity(
                        cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getString(3), cursor.getInt(4),
                    ))
                }
            }
        }
        override suspend fun readMeta(): List<MessageNodeMeta> = nodeDao.getNodeMeta(CONVERSATION_ID)
        override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int): String? =
            nodeDao.getMessagesSlice(nodeId, start, length)
    }

    @Test
    fun boundedFactoryReadsSmallRowsWithoutInvalidCursorState() = runBlocking<Unit> {
        val json = messagesJsonOf("small row")
        nodeDao.insert(MessageNodeEntity("small-bounded", CONVERSATION_ID, 0, json, 0))
        assertEquals(json, boundedWindowSource().readPage(64, 0).single().messages)
    }

    /**
     * 真实单行超出窗口 → 整行读失败 → metadata + substr 分片完整重组。
     * 覆盖：失败信号、SQLite `length()` 口径、顺序 / select_index / 全文 SHA、JSON 可解码。
     */
    @Test
    fun nativeLargeRowReadsFullyAndBoundedWindowFailureRebuildsRealChunks() = runBlocking {
        val payload = EMOJI.repeat(BIG_CODEPOINTS)
        val messages = messagesJsonOf(payload)
        val byteSize = utf8Size(messages)
        val codePoints = codepointLength(messages)
        assertTrue(
            "fixture must be a single multi-MiB row, was $byteSize bytes",
            byteSize > 4 * 1024 * 1024,
        )
        nodeDao.insert(MessageNodeEntity("node-big", CONVERSATION_ID, 0, messages, selectIndex = 1))

        // The bundled requery fork explicitly creates a 32 MiB window, so 4.8 MB
        // really succeeds. Assert the real fast path first, then exercise the same SQL
        // with a genuinely bounded 2 MiB native CursorWindow (not a fake exception).
        assertSameJson("native large row", messages,
            nodeDao.getNodesOfConversationPaged(CONVERSATION_ID, 64, 0).single().messages)
        val bounded = boundedWindowSource()
        val failure = runCatching { bounded.readPage(64, 0) }.exceptionOrNull()
        assertNotNull(
            "a $byteSize byte single row was read without error; this stack's CursorWindow is bigger " +
                "than the fixture, so the overflow path is not proven here",
            failure,
        )
        val error = failure!!
        android.util.Log.e("BoundedCursorProbe", "factory-created native 2MiB window", error)
        assertTrue(
            "expected a CursorWindow overflow, got ${error.javaClass.name}: ${error.message}",
            isCursorWindowOverflow(error),
        )

        // 2) metadata 只读 length(messages)，大行不进窗口
        val meta = nodeDao.getNodeMeta(CONVERSATION_ID).single()
        assertEquals(codePoints, meta.messagesLength)
        assertEquals(1, meta.selectIndex)
        assertTrue(
            "emoji fixtures must make SQLite code points differ from Kotlin UTF-16 length " +
                "($codePoints vs ${messages.length})",
            messages.length > codePoints,
        )

        // 3) 分片回退必须逐字节还原，且保持顺序 / select_index
        val loaded = database.withTransaction {
            loadAllNodesResilient(
                pageSize = 64,
                chunkCodepoints = PRODUCTION_CHUNK_CODEPOINTS,
                source = bounded,
            )
        }
        assertEquals(listOf("node-big"), loaded.map { it.id })
        assertEquals(listOf(0), loaded.map { it.nodeIndex })
        assertEquals(listOf(1), loaded.map { it.selectIndex })
        assertSameJson("node-big", messages, loaded.single().messages)
        assertFalse("chunked read must not cut a surrogate pair", hasLoneSurrogate(loaded.single().messages))

        // 4) 重组出来的仍然是可解析的真实消息 JSON
        val decoded = JsonInstant.decodeFromString<List<UIMessage>>(loaded.single().messages)
        assertEquals(1, decoded.size)
        val decodedText = (decoded.single().parts.single() as UIMessagePart.Text).text
        assertSameJson("node-big decoded text", payload, decodedText)
    }

    /**
     * 分片边界必须落在 code point 上。997 是质数，按 UTF-16 下标切会切进 emoji 代理对中间；
     * 同时验证 SQLite `substr(x, n, 1)` 返回的是一个完整 emoji（2 个 UTF-16 单元）。
     */
    @Test
    fun chunksSplitOnSqliteCodePointsNotOnKotlinSurrogateUnits() = runBlocking {
        val expected = listOf(
            MessageNodeEntity("node-0", CONVERSATION_ID, 0, messagesJsonOf("plain ascii node 0"), 0),
            MessageNodeEntity(
                "node-1",
                CONVERSATION_ID,
                1,
                messagesJsonOf(EMOJI.repeat(1500) + "中文abc" + EMOJI.repeat(3)),
                2,
            ),
            MessageNodeEntity("node-2", CONVERSATION_ID, 2, messagesJsonOf(("a" + EMOJI + "中").repeat(700)), 1),
            MessageNodeEntity("node-3", CONVERSATION_ID, 3, messagesJsonOf("tail"), 0),
        )
        // 乱序写入：顺序必须由 node_index 决定，不是插入顺序
        nodeDao.insertAll(listOf(expected[2], expected[0], expected[3], expected[1]))

        val loaded = database.withTransaction { loadNodesChunked(source, chunkCodepoints = 997) }

        assertEquals(expected.map { it.id }, loaded.map { it.id })
        assertEquals(expected.map { it.nodeIndex }, loaded.map { it.nodeIndex })
        assertEquals(expected.map { it.selectIndex }, loaded.map { it.selectIndex })
        expected.zip(loaded).forEach { (expectedNode, actualNode) ->
            assertSameJson(expectedNode.id, expectedNode.messages, actualNode.messages)
            assertFalse(
                "${expectedNode.id} must not contain a lone surrogate",
                hasLoneSurrogate(actualNode.messages),
            )
        }

        // SQLite substr 的 1 基下标是按 code point 的：取 1 个字符得到一个完整 emoji
        val node1 = expected[1].messages
        val firstEmojiAt = node1.indexOf(EMOJI)
        assertTrue("fixture JSON must carry raw emoji, not a unicode escape", firstEmojiAt >= 0)
        val firstEmojiIndex = codepointLength(node1.substring(0, firstEmojiAt)) + 1
        assertEquals(EMOJI, nodeDao.getMessagesSlice("node-1", firstEmojiIndex, 1))
        assertEquals(EMOJI + EMOJI, nodeDao.getMessagesSlice("node-1", firstEmojiIndex, 2))
        assertEquals(EMOJI, nodeDao.getMessagesSlice("node-1", firstEmojiIndex + 1, 1))

        val meta1 = nodeDao.getNodeMeta(CONVERSATION_ID).first { it.id == "node-1" }
        assertEquals(codepointLength(node1), meta1.messagesLength)
        assertTrue("SQLite length() must be code points, not UTF-16 units", meta1.messagesLength < node1.length)
    }

    /** 最新的节点是大行时，尾部读取（子代理看会话用）也要能重组，且顺序/select_index 正确。 */
    @Test
    fun tailReadRebuildsTheNewestBigNodeThroughRealDaoQueries() = runBlocking {
        val nodes = (0..4).map { index ->
            MessageNodeEntity("tail-$index", CONVERSATION_ID, index, messagesJsonOf("node $index"), 0)
        }
        val expected = nodes[4].copy(messages = messagesJsonOf(EMOJI.repeat(MID_CODEPOINTS)), selectIndex = 1)
        nodeDao.insertAll(listOf(nodes[3], nodes[0], expected, nodes[1], nodes[2]))

        val loaded = database.withTransaction {
            loadTailNodesResilient(
                limit = 3,
                chunkCodepoints = PRODUCTION_CHUNK_CODEPOINTS,
                readTail = { nodeDao.getLastNodes(CONVERSATION_ID, it) },
                readTailMeta = { nodeDao.getLastNodeMeta(CONVERSATION_ID, it) },
                readSlice = { nodeId, start, length -> nodeDao.getMessagesSlice(nodeId, start, length) },
            )
        }

        assertEquals(listOf("tail-2", "tail-3", "tail-4"), loaded.map { it.id })
        assertEquals(listOf(2, 3, 4), loaded.map { it.nodeIndex })
        assertSameJson("tail-4", expected.messages, loaded.last().messages)
        assertEquals(1, loaded.last().selectIndex)
        assertEquals(listOf(nodes[2].messages, nodes[3].messages), loaded.take(2).map { it.messages })
    }

    /**
     * 分片读不到时必须整体失败。这里注入的是真实库状态变化（metadata 读完那一行就被删掉），
     * 不是假的 CursorWindow 异常 —— 半截会话比抛错危险得多，保存时会把剩下的节点当成已删除。
     */
    @Test
    fun unreadableSliceFailsTheWholeLoadInsteadOfReturningAPartialConversation() = runBlocking {
        nodeDao.insertAll(
            listOf(
                MessageNodeEntity("keep-0", CONVERSATION_ID, 0, messagesJsonOf("keep me"), 0),
                MessageNodeEntity("broken", CONVERSATION_ID, 1, messagesJsonOf(EMOJI.repeat(MID_CODEPOINTS)), 0),
                MessageNodeEntity("keep-2", CONVERSATION_ID, 2, messagesJsonOf("keep me too"), 0),
            )
        )

        val disappearingSource = object : NodePageSource {
            override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> =
                nodeDao.getNodesOfConversationPaged(CONVERSATION_ID, limit, offset)

            override suspend fun readMeta(): List<MessageNodeMeta> {
                val metas = nodeDao.getNodeMeta(CONVERSATION_ID)
                nodeDao.deleteById("broken")
                return metas
            }

            override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int): String? =
                nodeDao.getMessagesSlice(nodeId, start, length)
        }

        val error = runCatching {
            // Deliberately not wrapped in a transaction: the real deletion must persist
            // for this missing-row fault fixture. Normal transactional reads roll it back.
            loadNodesChunked(disappearingSource, chunkCodepoints = PRODUCTION_CHUNK_CODEPOINTS)
        }.exceptionOrNull()

        assertNotNull("a missing slice must fail loudly instead of returning a shorter conversation", error)
        assertTrue("got ${error!!.javaClass.name}: ${error.message}", error is IllegalStateException)
        assertTrue(error.message.orEmpty().contains("broken"))
        assertTrue(
            "the injected failure must come from a real deleted row",
            nodeDao.getNodeMeta(CONVERSATION_ID).none { it.id == "broken" },
        )
    }

    /** 增量保存用的快照回退：大行溢出后快照仍完整，否则下一次保存会误删/重写整段会话。 */
    @Test
    fun snapshotFallbackKeepsEveryNodeSoAnIncrementalSaveStaysTruthful() = runBlocking {
        val big = messagesJsonOf(EMOJI.repeat(MID_CODEPOINTS))
        nodeDao.insertAll(
            listOf(
                MessageNodeEntity("snap-0", CONVERSATION_ID, 0, messagesJsonOf("small"), 0),
                MessageNodeEntity("snap-1", CONVERSATION_ID, 1, big, 2),
            )
        )

        val snapshots = database.withTransaction {
            loadSnapshotsResilient(
                readSnapshots = { nodeDao.getSnapshots(CONVERSATION_ID) },
                fallback = {
                    loadNodesChunked(source, chunkCodepoints = PRODUCTION_CHUNK_CODEPOINTS).map { node ->
                        MessageNodeSnapshot(node.id, node.nodeIndex, node.messages, node.selectIndex)
                    }
                },
            )
        }

        assertEquals(listOf("snap-0", "snap-1"), snapshots.map { it.id })
        assertEquals(listOf(0, 1), snapshots.map { it.nodeIndex })
        assertEquals(listOf(0, 2), snapshots.map { it.selectIndex })
        assertSameJson("snap-1", big, snapshots[1].messages)

        // 快照与分片读的结果必须是同一套内容与 id，否则 messageNodeWrites 会把大节点判成“内容变了”
        val chunked = database.withTransaction { loadNodesChunked(source, PRODUCTION_CHUNK_CODEPOINTS) }
        assertEquals(chunked.map { it.id }, snapshots.map { it.id })
        assertSameJson("snap-1 via chunked", chunked[1].messages, snapshots[1].messages)
    }

    /** 小会话仍走分页快路径，且快路径与分片结果一致（回退不能改变语义）。 */
    @Test
    fun smallConversationUsesThePlainPagePathAndMatchesTheChunkedRead() = runBlocking {
        val expected = (0..3).map { index ->
            MessageNodeEntity(
                "small-$index",
                CONVERSATION_ID,
                index,
                messagesJsonOf("message $index 中文"),
                index % 2,
            )
        }
        nodeDao.insertAll(expected.reversed())

        val fast = database.withTransaction {
            loadAllNodesResilient(64, PRODUCTION_CHUNK_CODEPOINTS, source)
        }
        val chunked = database.withTransaction { loadNodesChunked(source, PRODUCTION_CHUNK_CODEPOINTS) }

        assertEquals(expected.map { it.id }, fast.map { it.id })
        assertEquals(expected.map { it.messages }, fast.map { it.messages })
        assertEquals(expected.map { it.selectIndex }, fast.map { it.selectIndex })
        assertEquals(chunked.map { it.id }, fast.map { it.id })
        assertEquals(chunked.map { it.messages }, fast.map { it.messages })
    }

    private fun messagesJsonOf(text: String): String =
        JsonInstant.encodeToString(
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text = text))))
        )
}

private const val CONVERSATION_ID = "conv-large-message"

/** 与 ConversationRepository.NODE_CHUNK_CODEPOINTS 一致：每片 64Ki code point。 */
private const val PRODUCTION_CHUNK_CODEPOINTS = 64 * 1024

/** 😀 = 1 code point / 2 UTF-16 units / 4 UTF-8 bytes，三种计数都不一样。 */
private const val EMOJI = "\uD83D\uDE00"

/** 约 4.8MB UTF-8 的单行，用于证明整行读取真的会失败。 */
private const val BIG_CODEPOINTS = 1_200_000

/** 约 2.8MB UTF-8，用于其余需要“大行”但不必最大的用例。 */
private const val MID_CODEPOINTS = 700_000

// 计数直接复用 production 的 `codepointLength`（同包 internal），保证测试与实现的 code point 语义一致。

private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * 大文本逐字节比较：先比 code point / UTF-16 / UTF-8 长度与 SHA-256，
 * 避免断言失败时把几 MB 文本全部打进 logcat。
 */
private fun assertSameJson(label: String, expected: String, actual: String) {
    assertEquals("$label: code point count", codepointLength(expected), codepointLength(actual))
    assertEquals("$label: UTF-16 length", expected.length, actual.length)
    assertEquals("$label: UTF-8 size", utf8Size(expected), utf8Size(actual))
    assertEquals("$label: sha256", sha256(expected), sha256(actual))
    assertTrue("$label: content differs despite equal length and sha256", expected == actual)
}

/** 是否有落单的代理项（分片切在代理对中间的症状）。 */
private fun hasLoneSurrogate(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            Character.isHighSurrogate(char) -> {
                if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return true
                index += 2
            }

            Character.isLowSurrogate(char) -> return true
            else -> index++
        }
    }
    return false
}
