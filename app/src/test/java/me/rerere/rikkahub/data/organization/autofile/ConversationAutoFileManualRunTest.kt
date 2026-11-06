package me.rerere.rikkahub.data.organization.autofile

import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * r22 收尾缺陷的回归测试（三块）：
 * 1. 调用方取消批次时，**子任务必须被 cancel + join 之后才放锁**（旧实现只 remove+unlock）；
 * 2. 手动「立即整理」排成每助手**唯一**的后台任务，已有任务时不重复入队、回执用真实 id；
 * 3. 外发同意：切到 AI 模式 / 手动触发 AI 模式都必须显式确认，不能被后台 flag 绕过。
 */
class ConversationAutoFileManualRunTest {

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

        fun addThreeIdle(): List<Conversation> = listOf(
            add("旅行计划", createAt = now - 100 * hour, updateAt = now - 48 * hour),
            add("出差安排", createAt = now - 90 * hour, updateAt = now - 47 * hour),
            add("年假路线", createAt = now - 80 * hour, updateAt = now - 46 * hour),
        )

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

        override suspend fun placement(conversationId: Uuid): AutoFilePlacement? {
            val conversation = conversations[conversationId] ?: return null
            return AutoFilePlacement(
                conversationId = conversation.id,
                folderId = conversation.folderId,
                updateAt = conversation.updateAt,
                pinned = false,
                locked = false,
            )
        }

        override suspend fun postSummary(assistantId: Uuid, text: String) = Unit
    }

    private class Classifier : ConversationAutoFileClassifier {
        var calls = 0

        /** 分类期间挂住，用来测取消。 */
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        /** 子任务真的收到取消并退出时才完成——用来证明调用方等它停下了。 */
        val stopped = CompletableDeferred<Unit>()

        override suspend fun classify(
            assistantId: Uuid,
            items: List<AutoFileClassifyItem>,
            folders: List<AutoFileFolderRef>,
        ): AutoFileParsedClassification {
            calls += 1
            entered.complete(Unit)
            try {
                gate?.await()
            } catch (cancelled: CancellationException) {
                stopped.complete(Unit)
                throw cancelled
            }
            return AutoFileParsedClassification(
                assignments = items.map { AutoFileAssignment(it.id, "旅行", true, 0.95) },
            )
        }
    }

    private class Scheduler : ConversationAutoFileScheduler {
        val scheduled = LinkedHashMap<Uuid, Boolean>()
        var manualCalls = 0
        var cancelManualCalls = 0
        var manualFailure: Throwable? = null

        /** 非空时模拟「同名任务已经排在队列里（KEEP 生效）」。 */
        var existing: AutoFileManualWork? = null

        override fun schedule(assistantId: Uuid, enabled: Boolean, mode: AutoFileMode) {
            scheduled[assistantId] = enabled
        }

        override suspend fun scheduleManual(assistantId: Uuid): AutoFileManualWork {
            manualCalls += 1
            manualFailure?.let { throw it }
            existing?.let { return it }
            return AutoFileManualPolicy.manualWork(
                assistantId = assistantId,
                workId = "work-$manualCalls",
                state = "ENQUEUED",
                enqueued = true,
            )
        }

        override fun cancelManual(assistantId: Uuid) {
            cancelManualCalls += 1
        }
    }

    private fun controller(
        gateway: Gateway,
        classifier: ConversationAutoFileClassifier = Classifier(),
        scheduler: Scheduler = Scheduler(),
    ) = ConversationAutoFileController(
        storeDir = File(temporaryFolder.root, "store-${Uuid.random()}"),
        gateway = gateway,
        classifier = classifier,
        scheduler = scheduler,
        now = { now },
    )

    // ---- 1. 取消 ----

    @Test
    fun `cancelling the caller stops the child batch before releasing the lock`() = runTest {
        val gateway = Gateway()
        gateway.addThreeIdle()
        val classifier = Classifier().apply { gate = CompletableDeferred() }
        val controller = controller(gateway, classifier)

        val job = launch { controller.organizeNow(assistantId) }
        classifier.entered.await()
        job.cancelAndJoin()

        // 旧实现：这里子任务还在 gate 上挂着继续搬库。修好之后调用方取消会 cancel + join 子任务。
        assertTrue("子任务没有被取消", classifier.stopped.isCompleted)
        val state = controller.observe(assistantId).value
        assertFalse("忙态没清干净", state.running)
        assertTrue("取消前不该搬东西", gateway.folders.isEmpty())
        assertTrue(gateway.conversations.values.all { it.folderId == null })

        // 锁确实放掉了：紧接着还能完整跑一批（旧任务不会再来抢）。
        classifier.gate = null
        controller.organizeNow(assistantId)
        assertEquals(3, controller.observe(assistantId).value.moved)
    }

    @Test
    fun `cancelling a pending manual work is part of cancelRun`() = runTest {
        val scheduler = Scheduler()
        val controller = controller(Gateway(), scheduler = scheduler)

        // 没有在跑的批次，但队列里那条手动任务也必须撤掉。
        assertFalse(controller.cancelRun(assistantId))
        assertEquals(1, scheduler.cancelManualCalls)

        controller.updateSettings(assistantId, ConversationAutoFileSettings(enabled = false))
        assertEquals(2, scheduler.cancelManualCalls)
    }

    // ---- 2. 手动任务的身份与去重 ----

    @Test
    fun `manual run is queued as the unique background work of this assistant`() = runTest {
        val scheduler = Scheduler()
        val controller = controller(Gateway(), scheduler = scheduler)

        val work = controller.enqueueManualRun(assistantId)

        assertTrue(work.enqueued)
        assertEquals("work-1", work.workId)
        assertEquals(AutoFileManualPolicy.manualWorkName(assistantId), work.workName)
        // 手动任务和周期任务用两个名字：一个是显式动作，一个受自动开关约束。
        assertNotEquals(AutoFileManualPolicy.periodicWorkName(assistantId), work.workName)
        assertNotEquals(
            AutoFileManualPolicy.manualWorkName(assistantId),
            AutoFileManualPolicy.manualWorkName(Uuid.random()),
        )
    }

    @Test
    fun `an already queued manual work keeps its real id and is not reported as newly queued`() = runTest {
        val scheduler = Scheduler().apply {
            existing = AutoFileManualPolicy.manualWork(
                assistantId = assistantId,
                workId = "already-there",
                state = "ENQUEUED",
                enqueued = false,
            )
        }
        val controller = controller(Gateway(), scheduler = scheduler)

        val work = controller.enqueueManualRun(assistantId)

        assertFalse("KEEP 生效时不能假装新入队", work.enqueued)
        assertEquals("already-there", work.workId)
    }

    @Test
    fun `a batch that is already running does not enqueue a second work`() = runTest {
        val gateway = Gateway()
        gateway.addThreeIdle()
        val classifier = Classifier().apply { gate = CompletableDeferred() }
        val scheduler = Scheduler()
        val controller = controller(gateway, classifier, scheduler)

        val job = launch { controller.organizeNow(assistantId) }
        classifier.entered.await()
        val work = controller.enqueueManualRun(assistantId)

        assertEquals("RUNNING", work.state)
        assertFalse(work.enqueued)
        assertEquals("在跑的时候不该再排一个", 0, scheduler.manualCalls)

        classifier.gate?.complete(Unit)
        job.join()
    }

    @Test
    fun `a scheduling failure is reported instead of a fake success`() = runTest {
        val scheduler = Scheduler().apply { manualFailure = IllegalStateException("WorkManager 不可用") }
        val controller = controller(Gateway(), scheduler = scheduler)

        val work = controller.enqueueManualRun(assistantId)

        assertFalse(work.enqueued)
        assertEquals("FAILED", work.state)
        val error = controller.observe(assistantId).value.error
        assertTrue("排不上要有可查的错误", error != null && error.contains("WorkManager 不可用"))
    }

    // ---- 3. AI 模式切换/手动触发的外发同意 ----

    @Test
    fun `switching from local to AI while auto-file is on needs a confirmation`() {
        val localOn = ConversationAutoFileSettings(enabled = true, mode = AutoFileMode.LOCAL)
        assertTrue(AutoFileManualPolicy.modeSwitchNeedsConfirmation(localOn, AutoFileMode.AI))
        // 已经是 AI：没有新的外发决定
        assertFalse(
            AutoFileManualPolicy.modeSwitchNeedsConfirmation(localOn.copy(mode = AutoFileMode.AI), AutoFileMode.AI),
        )
        // 自动归档关着：真正的门槛在手动触发那一步
        assertFalse(AutoFileManualPolicy.modeSwitchNeedsConfirmation(localOn.copy(enabled = false), AutoFileMode.AI))
        // 往本地切永远不需要确认
        assertFalse(AutoFileManualPolicy.modeSwitchNeedsConfirmation(localOn, AutoFileMode.LOCAL))
    }

    @Test
    fun `manual trigger only asks for consent in AI mode with the switch off`() {
        assertTrue(AutoFileManualPolicy.needsRunConfirmation(ConversationAutoFileSettings(enabled = false)))
        assertFalse(
            AutoFileManualPolicy.needsRunConfirmation(
                ConversationAutoFileSettings(enabled = true, mode = AutoFileMode.AI),
            ),
        )
        // 本地规则模式不发网，不需要外发确认
        assertFalse(
            AutoFileManualPolicy.needsRunConfirmation(
                ConversationAutoFileSettings(enabled = false, mode = AutoFileMode.LOCAL),
            ),
        )
    }

    @Test
    fun `automation cannot turn on AI mode without an explicit external usage confirmation`() = runTest {
        val controller = controller(Gateway())
        val facade = ConversationAutoFileFacade(controller)

        try {
            facade.configure(assistantId, enabled = true, mode = AutoFileMode.AI)
            fail("没有显式确认也把 AI 模式打开了")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("确认") == true)
        }
        assertFalse(controller.status(assistantId).settings.enabled)

        // 本地规则模式不需要确认，但也不会因此变成 AI。
        val status = facade.configure(assistantId, enabled = true, mode = AutoFileMode.LOCAL)
        assertTrue(status.enabled)
        assertEquals("local", status.mode)

        // 显式确认后 AI 模式才能被打开。
        val ai = facade.configure(
            assistantId,
            enabled = true,
            mode = AutoFileMode.AI,
            confirmExternalModelUsage = true,
        )
        assertEquals("ai", ai.mode)
    }
}
