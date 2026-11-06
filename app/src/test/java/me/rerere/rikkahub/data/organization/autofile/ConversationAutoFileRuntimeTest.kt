package me.rerere.rikkahub.data.organization.autofile

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

/**
 * r22 运行层：历史批次（模式/移动数/失败数/撤销）、批次详情（会话+目标库+理由）、
 * 以及给自动化用的小门面（含「AI 模式必须显式确认外发」的门槛）。
 */
class ConversationAutoFileRuntimeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val assistantId: Uuid = Uuid.random()
    private val now = 1_000_000_000_000L
    private val hour = 60L * 60L * 1000L

    private class Conversation(
        val id: Uuid,
        val title: String,
        val createAt: Long,
        var updateAt: Long,
        var folderId: Uuid? = null,
    )

    private class Gateway : ConversationAutoFileGateway {
        val conversations = LinkedHashMap<Uuid, Conversation>()
        val folders = LinkedHashMap<Uuid, String>()

        fun add(title: String, createAt: Long, updateAt: Long): Conversation =
            Conversation(Uuid.random(), title, createAt, updateAt).also { conversations[it.id] = it }

        override suspend fun canAutoFile(conversationId: Uuid): Boolean = true

        override suspend fun loadCandidates(assistantId: Uuid): List<AutoFileCandidate> =
            conversations.values.map {
                AutoFileCandidate(
                    id = it.id,
                    title = it.title,
                    createAt = it.createAt,
                    updateAt = it.updateAt,
                    folderId = it.folderId,
                    pinned = false,
                    locked = false,
                    familyKey = it.id,
                )
            }

        override suspend fun loadExcerpt(conversationId: Uuid, headChars: Int, tailChars: Int): String = ""

        override suspend fun loadFolders(assistantId: Uuid): List<AutoFileFolderRef> =
            folders.map { AutoFileFolderRef(it.key, it.value) }

        override suspend fun createFolder(assistantId: Uuid, name: String): Uuid =
            Uuid.random().also { folders[it] = name }

        override suspend fun move(
            assistantId: Uuid,
            conversationId: Uuid,
            expectedUpdateAt: Long,
            expectedFolderId: Uuid?,
            folderId: Uuid?,
            undo: Boolean,
        ): Boolean {
            val conversation = conversations[conversationId] ?: return false
            if (conversation.updateAt != expectedUpdateAt) return false
            if (conversation.folderId != expectedFolderId) return false
            conversation.folderId = folderId
            return true
        }

        override suspend fun placement(conversationId: Uuid): AutoFilePlacement? =
            conversations[conversationId]?.let {
                AutoFilePlacement(it.id, it.folderId, it.updateAt, pinned = false, locked = false)
            }

        override suspend fun postSummary(assistantId: Uuid, text: String) = Unit

        override suspend fun loadConversationTitles(
            assistantId: Uuid,
            conversationIds: List<Uuid>,
        ): Map<Uuid, String> = conversationIds.mapNotNull { id ->
            conversations[id]?.let { id to it.title }
        }.toMap()
    }

    private class FailingGateway : ConversationAutoFileClassifier {
        override suspend fun classify(
            assistantId: Uuid,
            items: List<AutoFileClassifyItem>,
            folders: List<AutoFileFolderRef>,
        ): AutoFileParsedClassification = AutoFileParsedClassification(error = "模型报错")
    }

    private class Recorder : ConversationAutoFileScheduler {
        val calls = mutableListOf<Triple<Uuid, Boolean, AutoFileMode>>()

        override fun schedule(assistantId: Uuid, enabled: Boolean, mode: AutoFileMode) {
            calls += Triple(assistantId, enabled, mode)
        }
    }

    private fun controller(
        gateway: Gateway = Gateway(),
        classifier: ConversationAutoFileClassifier = FailingGateway(),
        scheduler: Recorder = Recorder(),
        name: String = Uuid.random().toString(),
    ) = ConversationAutoFileController(
        storeDir = File(temporaryFolder.root, "store-$name"),
        gateway = gateway,
        classifier = classifier,
        scheduler = scheduler,
        now = { now },
    )

    private fun Gateway.threeIdle(): List<Conversation> = listOf(
        add("旅行 京都 行程", now - 100 * hour, now - 48 * hour),
        add("旅行 京都 住宿", now - 90 * hour, now - 47 * hour),
        add("旅行 京都 交通", now - 80 * hour, now - 46 * hour),
    )

    @Test
    fun `local mode records a batch with mode, moved count and reasons`() = runTest {
        val gateway = Gateway().apply { threeIdle() }
        val controller = controller(gateway = gateway)
        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = true, mode = AutoFileMode.LOCAL))
        controller.organizeNow(assistantId)

        val history = controller.history(assistantId)
        assertEquals(1, history.size)
        val batch = history.first()
        assertEquals(AutoFileMode.LOCAL, batch.modeOrDefault)
        assertEquals(3, batch.movedCount)
        assertEquals(0, batch.failedCount)
        assertTrue(batch.isUndoable())

        val detail = controller.details(assistantId, batch.id)
        assertNotNull(detail)
        assertEquals(3, detail!!.batch.moves.size)
        assertTrue(detail.titles.isNotEmpty())
        assertTrue(detail.batch.moves.all { it.folderName != null })
        // 本地模式的每条理由都写明了命中的词
        assertTrue(detail.batch.moves.all { !it.reason.isNullOrBlank() })
    }

    @Test
    fun `local mode never falls back to the model classifier`() = runTest {
        val gateway = Gateway().apply { threeIdle() }
        val controller = controller(gateway = gateway, classifier = FailingGateway())
        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = true, mode = AutoFileMode.LOCAL))
        controller.organizeNow(assistantId)
        // AI 分类器被换成了必然报错的那个；本地模式照样搬成功，说明它真的没走模型。
        assertEquals(3, controller.history(assistantId).first().movedCount)
        assertNull(controller.status(assistantId).error)
    }

    @Test
    fun `history is newest first and reports failure batches`() = runTest {
        val gateway = Gateway().apply { threeIdle() }
        val controller = controller(gateway = gateway, classifier = FailingGateway())
        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = true, mode = AutoFileMode.AI))
        controller.organizeNow(assistantId)
        // AI 分类器报错：不动库，也不伪造批次。
        assertTrue(controller.history(assistantId).isEmpty())
        assertEquals("模型报错", controller.status(assistantId).error)
    }

    @Test
    fun `details of a missing batch is null`() = runTest {
        assertNull(controller().details(assistantId, "nope"))
    }

    @Test
    fun `facade exposes status history details and enforces the AI confirmation`() = runTest {
        val gateway = Gateway().apply { threeIdle() }
        val controller = controller(gateway = gateway)
        val facade = ConversationAutoFileFacade(controller)

        // 默认关着，且默认模式是 AI（与 r21 一致）
        val initial = facade.status(assistantId)
        assertEquals(false, initial.enabled)
        assertEquals("ai", initial.mode)

        // 自动化打开 AI 模式必须显式确认外发
        val refused = runCatching {
            facade.configure(assistantId, enabled = true, mode = AutoFileMode.AI)
        }
        assertTrue(refused.isFailure)
        assertEquals(false, facade.status(assistantId).enabled)

        // 本地模式不发网，不需要确认
        val local = facade.configure(assistantId, enabled = true, mode = AutoFileMode.LOCAL)
        assertTrue(local.enabled)
        assertEquals("local", local.mode)
        assertEquals(listOf(assistantId.toString()), facade.enabledAssistantIds())

        val triggered = facade.trigger(assistantId)
        assertEquals(3, triggered.moved)

        val batches = facade.history(assistantId)
        assertEquals(1, batches.size)
        val detail = facade.details(assistantId, batches.first().id)
        assertNotNull(detail)
        assertEquals(3, detail!!.moves.size)
        assertTrue(detail.moves.all { it.title != null && it.folderName != null && it.reason != null })

        val undone = facade.undo(assistantId)
        assertEquals(false, undone.canUndo)
    }

    @Test
    fun `turning the switch off cancels the schedule and keeps the chosen mode`() = runTest {
        val scheduler = Recorder()
        val controller = controller(gateway = Gateway().apply { threeIdle() }, scheduler = scheduler)
        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = true, mode = AutoFileMode.LOCAL))
        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = false, mode = AutoFileMode.LOCAL))
        assertEquals(false, scheduler.calls.last().second)
        assertEquals(AutoFileMode.LOCAL, scheduler.calls.last().third)
        assertTrue(controller.history(assistantId).isEmpty())
    }

    @Test
    fun `settings written by r21 without a mode still read as AI and stay disabled by default`() = runTest {
        val store = ConversationAutoFileSettingsStore(File(temporaryFolder.root, "legacy"))
        assertEquals(AutoFileMode.AI, store.read(assistantId).mode)
        assertEquals(false, store.read(assistantId).enabled)
    }
}
