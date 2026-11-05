package me.rerere.rikkahub.data.organization.autofile

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class ConversationAutoFileControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val assistantId: Uuid = Uuid.random()
    private val now = 1_000_000_000_000L
    private val hour = 60L * 60L * 1000L

    private class FakeConversation(
        val id: Uuid,
        val title: String,
        val createAt: Long,
        var updateAt: Long,
        var folderId: Uuid? = null,
        var pinned: Boolean = false,
        var locked: Boolean = false,
        val familyKey: Uuid = id,
    )

    private class FakeGateway : ConversationAutoFileGateway {
        val conversations = LinkedHashMap<Uuid, FakeConversation>()
        val folders = LinkedHashMap<Uuid, String>()
        val busy = HashSet<Uuid>()
        val failingMoves = HashSet<Uuid>()
        val summaries = mutableListOf<String>()
        var failOnLoad = false
        var placementReads = 0

        /** 模拟「刚搬完用户又聊了一句」：归属变更后 updateAt 前进，撤销必须因此判为不可撤。 */
        var continueChatAfterMove = false

        /** 每次真实搬迁之前执行，用来在批处理中途改设置。 */
        var beforeMove: (suspend () -> Unit)? = null

        fun add(
            title: String,
            createAt: Long,
            updateAt: Long,
            familyKey: Uuid? = null,
        ): FakeConversation {
            val conversation = FakeConversation(
                id = Uuid.random(),
                title = title,
                createAt = createAt,
                updateAt = updateAt,
                familyKey = familyKey ?: Uuid.random(),
            )
            conversations[conversation.id] = conversation
            return conversation
        }

        override suspend fun canAutoFile(conversationId: Uuid): Boolean = conversationId !in busy

        override suspend fun loadCandidates(assistantId: Uuid): List<AutoFileCandidate> {
            if (failOnLoad) throw IllegalStateException("读候选失败")
            return conversations.values
                .filter { it.folderId == null }
                .map {
                    AutoFileCandidate(
                        id = it.id,
                        title = it.title,
                        createAt = it.createAt,
                        updateAt = it.updateAt,
                        folderId = it.folderId,
                        pinned = it.pinned,
                        locked = it.locked,
                        familyKey = it.familyKey,
                    )
                }
        }

        override suspend fun loadExcerpt(conversationId: Uuid, headChars: Int, tailChars: Int): String =
            "excerpt-$conversationId"

        override suspend fun loadFolders(assistantId: Uuid): List<AutoFileFolderRef> =
            folders.map { AutoFileFolderRef(it.key, it.value) }

        override suspend fun createFolder(assistantId: Uuid, name: String): Uuid {
            val id = Uuid.random()
            folders[id] = name
            return id
        }

        override suspend fun move(
            assistantId: Uuid,
            conversationId: Uuid,
            expectedUpdateAt: Long,
            expectedFolderId: Uuid?,
            folderId: Uuid?,
            undo: Boolean,
        ): Boolean {
            val conversation = conversations[conversationId] ?: return false
            beforeMove?.invoke()
            if (conversationId in failingMoves) return false
            if (conversation.updateAt != expectedUpdateAt) return false
            if (conversation.folderId != expectedFolderId) return false
            if (conversation.pinned || conversation.locked) return false
            if (!undo && conversationId in busy) return false
            // 与 ConversationRepository.applyFolderAssignment 一致：只改归属列，不动 update_at。
            conversation.folderId = folderId
            if (continueChatAfterMove) conversation.updateAt += 5 * 60L * 60L * 1000L
            return true
        }

        override suspend fun placement(conversationId: Uuid): AutoFilePlacement? {
            placementReads += 1
            val conversation = conversations[conversationId] ?: return null
            return AutoFilePlacement(
                conversationId = conversation.id,
                folderId = conversation.folderId,
                updateAt = conversation.updateAt,
                pinned = conversation.pinned,
                locked = conversation.locked,
            )
        }

        override suspend fun postSummary(assistantId: Uuid, text: String) {
            summaries += text
        }
    }

    private class FakeClassifier : ConversationAutoFileClassifier {
        var calls = 0
        var lastItems: List<AutoFileClassifyItem> = emptyList()
        var folderName: String = "旅行"
        var isNew: Boolean = true
        var confidence: Double = 0.95
        var error: String? = null

        /** 分类期间挂住，用来测「分类中改设置 / 取消」。 */
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override suspend fun classify(
            assistantId: Uuid,
            items: List<AutoFileClassifyItem>,
            folders: List<AutoFileFolderRef>,
        ): AutoFileParsedClassification {
            calls += 1
            lastItems = items
            entered.complete(Unit)
            gate?.await()
            if (error != null) return AutoFileParsedClassification(error = error)
            return AutoFileParsedClassification(
                assignments = items.map { AutoFileAssignment(it.id, folderName, isNew, confidence) },
            )
        }
    }

    private class FakeScheduler : ConversationAutoFileScheduler {
        val scheduled = LinkedHashMap<Uuid, Boolean>()

        override fun schedule(assistantId: Uuid, enabled: Boolean) {
            scheduled[assistantId] = enabled
        }
    }

    private fun controller(
        gateway: FakeGateway,
        classifier: FakeClassifier,
        scheduler: FakeScheduler,
        storeRoot: File = File(temporaryFolder.root, "store-$assistantId"),
    ) = ConversationAutoFileController(
        storeDir = storeRoot,
        gateway = gateway,
        classifier = classifier,
        scheduler = scheduler,
        now = { now },
    )

    private fun FakeGateway.threeIdleConversations(): List<FakeConversation> = listOf(
        add("旅行计划", createAt = now - 100 * hour, updateAt = now - 48 * hour),
        add("出差安排", createAt = now - 90 * hour, updateAt = now - 47 * hour),
        add("年假路线", createAt = now - 80 * hour, updateAt = now - 46 * hour),
    )

    @Test
    fun `organize now files idle conversations into a new folder and reports state`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val scheduler = FakeScheduler()
        val conversations = gateway.threeIdleConversations()
        val controller = controller(gateway, classifier, scheduler)

        controller.organizeNow(assistantId)

        val state = controller.observe(assistantId).value
        assertEquals(3, state.moved)
        assertTrue(state.canUndo)
        assertNull(state.error)
        assertFalse(state.running)
        assertEquals(1, classifier.calls)
        assertEquals(3, classifier.lastItems.size)
        assertTrue(classifier.lastItems.all { it.excerpt.isNotEmpty() })
        assertEquals(1, gateway.folders.size)
        assertTrue(conversations.all { it.folderId == gateway.folders.keys.first() })
        assertEquals(1, gateway.summaries.size)
    }

    @Test
    fun `classifier error keeps everything untouched`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier().apply { error = "分类结果格式不正确" }
        val conversations = gateway.threeIdleConversations()
        val controller = controller(gateway, classifier, FakeScheduler())

        controller.organizeNow(assistantId)

        val state = controller.observe(assistantId).value
        assertNotNull(state.error)
        assertEquals(0, state.moved)
        assertFalse(state.canUndo)
        assertTrue(gateway.folders.isEmpty())
        assertTrue(conversations.all { it.folderId == null })
    }

    @Test
    fun `scheduled run does nothing when disabled`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        gateway.threeIdleConversations()
        val controller = controller(gateway, classifier, FakeScheduler())

        controller.organizeScheduled(assistantId)

        assertEquals(0, classifier.calls)
        assertTrue(gateway.folders.isEmpty())
    }

    @Test
    fun `scheduled run works when enabled`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val scheduler = FakeScheduler()
        gateway.threeIdleConversations()
        val controller = controller(gateway, classifier, scheduler)
        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = true))
        assertEquals(true, scheduler.scheduled[assistantId])

        controller.organizeScheduled(assistantId)

        assertEquals(1, classifier.calls)
        assertEquals(3, controller.observe(assistantId).value.moved)
    }

    @Test
    fun `undo brings conversations back and clears undo flag`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val conversations = gateway.threeIdleConversations()
        val controller = controller(gateway, classifier, FakeScheduler())

        controller.organizeNow(assistantId)
        assertTrue(conversations.all { it.folderId != null })

        controller.undoLast(assistantId)

        assertTrue(conversations.all { it.folderId == null })
        val state = controller.observe(assistantId).value
        assertFalse(state.canUndo)
        assertNull(state.error)
        assertEquals(0, state.moved)
    }

    @Test
    fun `undo never overrides later user activity`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val conversations = gateway.threeIdleConversations()
        val controller = controller(gateway, classifier, FakeScheduler())

        controller.organizeNow(assistantId)
        val folderId = gateway.folders.keys.first()
        // 用户后来又聊过：updateAt 变了，撤销必须跳过
        conversations.forEach { it.updateAt = it.updateAt + 10 * hour }

        controller.undoLast(assistantId)

        assertTrue(conversations.all { it.folderId == folderId })
        val state = controller.observe(assistantId).value
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("未撤销"))
        assertFalse(state.canUndo)
    }

    @Test
    fun `undo records the planned version so a chat right after the move is not undoable`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val conversations = gateway.threeIdleConversations()
        // 模拟「搬完的瞬间用户又聊了一句」：搬完之后读到的 updateAt 已经变了。
        gateway.continueChatAfterMove = true
        val controller = controller(gateway, classifier, FakeScheduler())

        controller.organizeNow(assistantId)
        val folderId = gateway.folders.keys.first()
        assertEquals(3, controller.observe(assistantId).value.moved)

        controller.undoLast(assistantId)

        // 撤销基准是计划时的那一版，不是搬完之后又读到的新版本 → 三个都不能撤。
        assertTrue(conversations.all { it.folderId == folderId })
        val state = controller.observe(assistantId).value
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("未撤销"))
    }

    @Test
    fun `single failed move does not stop the batch`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val conversations = gateway.threeIdleConversations()
        gateway.failingMoves += conversations.first().id
        val controller = controller(gateway, classifier, FakeScheduler())

        controller.organizeNow(assistantId)

        val state = controller.observe(assistantId).value
        assertEquals(2, state.moved)
        assertTrue(state.canUndo)
        assertNull(conversations.first().folderId)
        assertTrue(conversations.drop(1).all { it.folderId != null })
    }

    @Test
    fun `failure releases running flag and allows a retry`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        gateway.threeIdleConversations()
        gateway.failOnLoad = true
        val controller = controller(gateway, classifier, FakeScheduler())

        controller.organizeNow(assistantId)
        val failed = controller.observe(assistantId).value
        assertFalse(failed.running)
        assertNotNull(failed.error)

        gateway.failOnLoad = false
        controller.organizeNow(assistantId)
        val retried = controller.observe(assistantId).value
        assertNull(retried.error)
        assertEquals(3, retried.moved)
    }

    @Test
    fun `busy conversations are skipped`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val conversations = gateway.threeIdleConversations()
        gateway.busy += conversations.first().id
        val controller = controller(gateway, classifier, FakeScheduler())

        controller.organizeNow(assistantId)

        assertEquals(2, controller.observe(assistantId).value.moved)
        assertNull(conversations.first().folderId)
    }

    @Test
    fun `pinned locked fresh and filed conversations never reach the model`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val eligible = gateway.threeIdleConversations()
        gateway.add("置顶", createAt = now - 100 * hour, updateAt = now - 60 * hour).pinned = true
        gateway.add("锁了", createAt = now - 100 * hour, updateAt = now - 60 * hour).locked = true
        gateway.add("刚聊过", createAt = now - 100 * hour, updateAt = now - 2 * hour)

        val controller = controller(gateway, classifier, FakeScheduler())
        controller.organizeNow(assistantId)

        assertEquals(3, classifier.lastItems.size)
        assertEquals(eligible.map { it.id }.toSet(), classifier.lastItems.map { it.id }.toSet())
    }

    @Test
    fun `family members are represented by the oldest conversation`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val family = Uuid.random()
        val oldest = gateway.add("同族早", createAt = now - 100 * hour, updateAt = now - 60 * hour, familyKey = family)
        val newer = gateway.add("同族晚", createAt = now - 10 * hour, updateAt = now - 60 * hour, familyKey = family)
        gateway.add("另一个", createAt = now - 90 * hour, updateAt = now - 60 * hour)
        gateway.add("再一个", createAt = now - 80 * hour, updateAt = now - 60 * hour)

        val controller = controller(gateway, classifier, FakeScheduler())
        controller.organizeNow(assistantId)

        assertEquals(3, classifier.lastItems.size)
        assertTrue(classifier.lastItems.any { it.id == oldest.id })
        assertFalse(classifier.lastItems.any { it.id == newer.id })
    }

    @Test
    fun `adopted family moves its eligible siblings without extra model input`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val family = Uuid.random()
        val representative = gateway.add("同族代表", createAt = now - 100 * hour, updateAt = now - 60 * hour, familyKey = family)
        val siblingA = gateway.add("同族兄弟A", createAt = now - 50 * hour, updateAt = now - 55 * hour, familyKey = family)
        val siblingB = gateway.add("同族兄弟B", createAt = now - 40 * hour, updateAt = now - 45 * hour, familyKey = family)
        gateway.add("别的话题一", createAt = now - 90 * hour, updateAt = now - 60 * hour)
        gateway.add("别的话题二", createAt = now - 80 * hour, updateAt = now - 60 * hour)

        val controller = controller(gateway, classifier, FakeScheduler())
        controller.organizeNow(assistantId)

        // 模型输入仍然只有 3 个家族代表，但同族三个会话一起归库。
        assertEquals(3, classifier.lastItems.size)
        assertTrue(classifier.lastItems.any { it.id == representative.id })
        assertFalse(classifier.lastItems.any { it.id == siblingA.id })
        assertEquals(5, controller.observe(assistantId).value.moved)
        assertNotNull(representative.folderId)
        assertEquals(representative.folderId, siblingA.folderId)
        assertEquals(representative.folderId, siblingB.folderId)
    }

    @Test
    fun `initialize only schedules assistants that are enabled`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val scheduler = FakeScheduler()
        val storeRoot = File(temporaryFolder.root, "store")
        val controller = controller(gateway, classifier, scheduler, storeRoot)
        val enabled = Uuid.random()
        val disabled = Uuid.random()
        ConversationAutoFileSettingsStore(storeRoot).apply {
            write(enabled, ConversationAutoFileSettings(enabled = true))
            write(disabled, ConversationAutoFileSettings(enabled = false))
        }

        controller.initialize()

        assertEquals(mapOf(enabled to true), scheduler.scheduled)
    }

    @Test
    fun `updateSettings persists and flips the scheduler`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val scheduler = FakeScheduler()
        val storeRoot = File(temporaryFolder.root, "store")
        val controller = controller(gateway, classifier, scheduler, storeRoot)

        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = true, idleHours = 30))
        assertEquals(true, scheduler.scheduled[assistantId])
        assertEquals(30, ConversationAutoFileSettingsStore(storeRoot).read(assistantId).idleHours)

        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = false))
        assertEquals(false, scheduler.scheduled[assistantId])
        assertFalse(ConversationAutoFileSettingsStore(storeRoot).read(assistantId).enabled)
    }

    @Test
    fun `settings write failure keeps the old settings and never schedules`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val scheduler = FakeScheduler()
        val storeRoot = File(temporaryFolder.root, "store-blocked")
        // 设置目录被一个同名文件占住：原子写一定失败。
        storeRoot.mkdirs()
        File(storeRoot, "settings").writeText("not a directory")
        val controller = controller(gateway, classifier, scheduler, storeRoot)

        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = true, idleHours = 30))

        val state = controller.observe(assistantId).value
        // 界面留在旧设置（默认关闭、36h），只报错，不能先发布再写盘。
        assertFalse(state.settings.enabled)
        assertEquals(ConversationAutoFileSettings.DEFAULT.idleHours, state.settings.idleHours)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("没能保存"))
        // 存储与调度都没变：没有排任务。
        assertNull(scheduler.scheduled[assistantId])
    }

    @Test
    fun `disabling while classification runs aborts before any move`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier().apply { gate = CompletableDeferred() }
        val scheduler = FakeScheduler()
        val conversations = gateway.threeIdleConversations()
        val storeRoot = File(temporaryFolder.root, "store-toggle")
        val controller = controller(gateway, classifier, scheduler, storeRoot)
        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = true))

        val job = launch { controller.organizeScheduled(assistantId) }
        classifier.entered.await()
        // 设置锁与批次锁是两把锁：分类还在跑，开关也能立刻落盘。
        withTimeout(5_000) {
            controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = false))
        }
        classifier.gate!!.complete(Unit)
        job.join()

        val state = controller.observe(assistantId).value
        assertEquals(1, classifier.calls)
        assertEquals(0, state.moved)
        assertFalse(state.running)
        assertFalse(state.settings.enabled)
        assertTrue(gateway.folders.isEmpty())
        assertTrue(conversations.all { it.folderId == null })
    }

    @Test
    fun `scheduled batch stops committing as soon as the setting is turned off`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val conversations = gateway.threeIdleConversations()
        val storeRoot = File(temporaryFolder.root, "store-mid")
        val controller = controller(gateway, classifier, FakeScheduler(), storeRoot)
        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = true))
        var moves = 0
        gateway.beforeMove = {
            moves += 1
            if (moves == 1) {
                controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = false))
            }
        }

        controller.organizeScheduled(assistantId)

        // 第一条搬完就关掉：后面的不再新建文件夹、不再搬。
        val state = controller.observe(assistantId).value
        assertEquals(1, state.moved)
        assertEquals(1, gateway.folders.size)
        assertEquals(1, conversations.count { it.folderId != null })
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("停止"))
        val stored = ConversationAutoFileJournal(storeRoot) { now }.latest(assistantId)
        assertNotNull(stored)
        assertEquals(AutoFileBatchStatus.DONE, stored!!.status)
        assertTrue(stored.moves.none { it.status == AutoFileMoveStatus.PLANNED })
    }

    @Test
    fun `cancelling a batch releases the lock and does not report a failure`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier().apply { gate = CompletableDeferred() }
        gateway.threeIdleConversations()
        val controller = controller(gateway, classifier, FakeScheduler())

        val job = launch { controller.organizeNow(assistantId) }
        classifier.entered.await()
        job.cancelAndJoin()

        val cancelled = controller.observe(assistantId).value
        assertFalse(cancelled.running)
        assertFalse(cancelled.canUndo)
        assertTrue(gateway.folders.isEmpty())

        // 锁和忙态都放掉了：紧接着还能完整跑一批。
        classifier.gate = null
        controller.organizeNow(assistantId)
        val retried = controller.observe(assistantId).value
        assertEquals(3, retried.moved)
        assertNull(retried.error)
    }

    @Test
    fun `interrupted batch keeps its undo and blocks a new batch until undone`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val scheduler = FakeScheduler()
        val storeRoot = File(temporaryFolder.root, "store-crash")
        val folderId = Uuid.random()
        gateway.folders[folderId] = "旅行"
        val applied = gateway.add("崩溃前已搬", createAt = now - 100 * hour, updateAt = now - 48 * hour)
        applied.folderId = folderId
        val pending = gateway.add("崩溃时未确认", createAt = now - 90 * hour, updateAt = now - 47 * hour)
        val fresh = gateway.add("新的散卡", createAt = now - 80 * hour, updateAt = now - 46 * hour)

        // 模拟进程被杀：批次还停在 planned，一条 applied 一条 planned。
        ConversationAutoFileJournal(storeRoot) { now }.begin(
            assistantId,
            listOf(
                AutoFileJournalMove(
                    conversationId = applied.id.toString(),
                    toFolderId = folderId.toString(),
                    folderName = "旅行",
                    status = AutoFileMoveStatus.APPLIED,
                    updateAtBefore = applied.updateAt,
                ),
                AutoFileJournalMove(
                    conversationId = pending.id.toString(),
                    toFolderId = folderId.toString(),
                    folderName = "旅行",
                    status = AutoFileMoveStatus.PLANNED,
                    updateAtBefore = pending.updateAt,
                ),
            ),
        )

        val controller = controller(gateway, classifier, scheduler, storeRoot)
        controller.organizeNow(assistantId)

        // 不开新批、不重发不确定的操作，撤销机会保留。
        assertEquals(0, classifier.calls)
        assertNull(fresh.folderId)
        val blocked = controller.observe(assistantId).value
        assertTrue(blocked.canUndo)
        assertNotNull(blocked.error)

        // 撤销把确认搬过的那条搬回、关掉旧批。
        controller.undoLast(assistantId)
        assertNull(applied.folderId)
        assertFalse(controller.observe(assistantId).value.canUndo)

        // 旧批收尾之后才允许新的一批。
        controller.organizeNow(assistantId)
        assertEquals(1, classifier.calls)
        assertNotNull(fresh.folderId)
    }

    @Test
    fun `planned journal entry is not undone after the conversation moved on`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val storeRoot = File(temporaryFolder.root, "store-planned-late")
        val folderId = Uuid.random()
        gateway.folders[folderId] = "旅行"
        val conversation = gateway.add("崩溃后用户又聊过", createAt = now - 200 * hour, updateAt = now - 48 * hour)
        conversation.folderId = folderId
        // 计划时的版本是 now-100h，崩溃后用户续聊变成 now-50h（仍然闲置超过 36h）。
        val plannedVersion = now - 100 * hour
        conversation.updateAt = now - 50 * hour
        ConversationAutoFileJournal(storeRoot) { now }.begin(
            assistantId,
            listOf(
                AutoFileJournalMove(
                    conversationId = conversation.id.toString(),
                    toFolderId = folderId.toString(),
                    folderName = "旅行",
                    status = AutoFileMoveStatus.PLANNED,
                    updateAtBefore = plannedVersion,
                ),
            ),
        )

        val controller = controller(gateway, classifier, FakeScheduler(), storeRoot)
        controller.undoLast(assistantId)

        assertEquals(folderId, conversation.folderId)
        val state = controller.observe(assistantId).value
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("未撤销"))
    }

    @Test
    fun `planned journal entry is undone when the version never changed`() = runTest {
        val gateway = FakeGateway()
        val classifier = FakeClassifier()
        val storeRoot = File(temporaryFolder.root, "store-planned-fresh")
        val folderId = Uuid.random()
        gateway.folders[folderId] = "旅行"
        val conversation = gateway.add("刚搬完就崩溃", createAt = now - 200 * hour, updateAt = now - 2 * hour)
        conversation.folderId = folderId
        ConversationAutoFileJournal(storeRoot) { now }.begin(
            assistantId,
            listOf(
                AutoFileJournalMove(
                    conversationId = conversation.id.toString(),
                    toFolderId = folderId.toString(),
                    folderName = "旅行",
                    status = AutoFileMoveStatus.PLANNED,
                    updateAtBefore = conversation.updateAt,
                ),
            ),
        )

        val controller = controller(gateway, classifier, FakeScheduler(), storeRoot)
        controller.undoLast(assistantId)

        // 版本没变就是「没再动过」，与「现在又闲置够 36 小时」无关。
        assertNull(conversation.folderId)
        assertNull(controller.observe(assistantId).value.error)
    }
}
