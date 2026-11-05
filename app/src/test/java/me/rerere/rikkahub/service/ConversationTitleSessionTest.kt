package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TitleTrigger
import me.rerere.rikkahub.data.model.TitleWriteGuard
import me.rerere.rikkahub.data.model.refinedTitle
import me.rerere.rikkahub.data.model.titleBranchAnchor
import me.rerere.rikkahub.data.model.titleState
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withEarlyTitle
import me.rerere.rikkahub.data.model.withForcedTitle
import me.rerere.rikkahub.data.model.withManualTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 即时标题和会话层配合的几处竞态：单飞、手改优先级、删除/整条替换，以及旧版本方法签名兼容。
 */
class ConversationTitleSessionTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun ConversationSession.addUser(text: String): UIMessage {
        val message = UIMessage.user(text)
        update { it.updateCurrentMessages(listOf(message)) }
        return message
    }

    private fun storedConversation(id: Uuid) =
        Conversation.ofId(id, assistantId = Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("history")))

    @Test
    fun `title singleflight allows only one request per session`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, FakeConversationStore(), {})
        session.initialize(load = { null }, create = { Conversation.ofId(id, assistantId = Uuid.random(), newConversation = true) })

        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var secondRan = false
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            session.singleFlightTitle {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        // 早标题还在请求时，一轮结束的标题撞上来：直接放弃，不并发再请求一次
        val second = session.singleFlightTitle { secondRan = true }
        assertNull(second)
        assertFalse(secondRan)
        assertFalse(first.isCompleted)

        release.complete(Unit)
        first.join()
        // 前面那个做完之后位置空出来，下一次请求照常
        assertEquals(true, session.singleFlightTitle { true })
    }

    @Test
    fun `late automatic title does not overwrite a manual rename`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(storedConversation(id))
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, store, {})
        session.initialize(load = { store.persisted })
        val user = session.addUser("帮我写周报")
        val incarnation = session.incarnation

        // 用户先手改了标题
        session.update { it.withManualTitle("我的周报") }

        // 早标题的请求晚到：锁内的 predicate 发现已经手改，不写
        var persistedTitle: String? = null
        val written = session.updateMetadata(
            transform = { it.withEarlyTitle("自动周报", requireNotNull(TitleTrigger.of(user))) },
            persist = { persistedTitle = it.title },
            expectedIncarnation = incarnation,
            predicate = { it.titleState.isAuto },
        )
        assertFalse(written)
        assertNull(persistedTitle)
        assertEquals("我的周报", session.state.value.title)
        assertFalse(session.state.value.titleState.isAuto)
        assertFalse("没有留下等待精修的临时标题", session.state.value.titleState.pendingEarly)
    }

    @Test
    fun `automatic title still writes while the conversation stays automatic`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(storedConversation(id))
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, store, {})
        session.initialize(load = { store.persisted })
        val user = session.addUser("帮我写周报")
        // 先塞一条还没生成完的助手消息，验证按列写只写标题
        session.update { it.updateCurrentMessages(it.currentMessages + UIMessage.assistant("正在生成")) }

        val written = session.updateMetadata(
            transform = { it.withEarlyTitle("周报", requireNotNull(TitleTrigger.of(user))) },
            persist = { store.persisted = store.persisted!!.copy(title = it.title) },
            predicate = { it.titleState.isAuto },
        )
        assertTrue(written)
        assertEquals("周报", session.state.value.title)
        assertEquals("周报", store.persisted!!.title)
        // 流式内容只留在内存里，没有被顺手整份落库
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `a title started before the conversation was deleted is dropped`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(storedConversation(id))
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, store, {})
        session.initialize(load = { store.persisted })
        val user = session.addUser("要被删掉的会话")
        val incarnation = session.incarnation

        session.replace(Conversation.ofId(id, assistantId = Uuid.random(), newConversation = true), existsInDatabase = false) {
            store.persisted = null
        }

        var persisted = false
        val written = session.updateMetadata(
            transform = { it.withEarlyTitle("不该出现", requireNotNull(TitleTrigger.of(user))) },
            persist = { persisted = true },
            expectedIncarnation = incarnation,
            predicate = { it.titleState.isAuto },
        )
        assertFalse(written)
        assertFalse(persisted)
        assertEquals("", session.state.value.title)
    }

    @Test
    fun `a title waiting for the write lock rechecks the rename inside the lock`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(storedConversation(id))
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, store, {})
        session.initialize(load = { store.persisted })
        val user = session.addUser("并发手改")
        val incarnation = session.incarnation
        val blocking = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()

        // 先占住写锁，让标题请求在锁外等
        store.beforeWrite = {
            blocking.complete(Unit)
            allowWrite.await()
        }
        val flushing = launch(start = CoroutineStart.UNDISPATCHED) { session.flush() }
        blocking.await()

        var autoPersisted = false
        val titling = launch(start = CoroutineStart.UNDISPATCHED) {
            autoPersisted = session.updateMetadata(
                transform = { it.withEarlyTitle("自动标题", requireNotNull(TitleTrigger.of(user))) },
                persist = { autoPersisted = true },
                expectedIncarnation = incarnation,
                predicate = { it.titleState.isAuto },
            )
        }
        // 等锁期间用户改了标题：标题请求拿到锁后必须看见
        session.update { it.withManualTitle("用户标题") }
        allowWrite.complete(Unit)
        flushing.join()
        titling.join()

        assertFalse("拿到锁时已经不是自动标题，不能写", autoPersisted)
        assertEquals("用户标题", session.state.value.title)
    }

    @Test
    fun `updateMetadata without a predicate still behaves like before`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(storedConversation(id))
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, store, {})
        session.initialize(load = { store.persisted })

        // 旧调用方（建议、置顶等）不传 predicate：行为不变
        val written = session.updateMetadata(
            transform = { it.copy(isPinned = true) },
            persist = { store.persisted = store.persisted!!.copy(isPinned = it.isPinned) },
        )
        assertTrue(written)
        assertTrue(session.state.value.isPinned)
        assertTrue(store.persisted!!.isPinned)
    }

    @Test
    fun `a cancelled title request cannot block later ones`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, FakeConversationStore(), {})
        session.initialize(load = { null }, create = { Conversation.ofId(id, assistantId = Uuid.random(), newConversation = true) })
        val generation = scope()
        try {
            val job = generation.launch(start = CoroutineStart.LAZY) {
                session.singleFlightTitle { awaitCancellation() }
            }
            session.setJob(job, cancelPrevious = false)
            job.cancel()
            job.join()
            // 被取消的请求要让出位置：停止生成之后标题还能继续做
            assertEquals(true, session.singleFlightTitle { true })
        } finally {
            session.cleanup()
            generation.cancel()
        }
    }

    @Test
    fun `a switched branch does not inherit the other branch title state`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(storedConversation(id))
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, store, {})
        session.initialize(load = { store.persisted })
        val user = session.addUser("A 分支")
        session.update { it.withEarlyTitle("A 标题", requireNotNull(TitleTrigger.of(user))) }

        // 切到另一条分支：新实例按标题推断，不会带着 A 的锚点
        val switched = session.update { it.copy(title = "",
            messageNodes = listOf(UIMessage.user("B 分支").toMessageNode())) }
        assertTrue("空白标题的新分支可以重新起标题", switched.titleState.isAuto)
        assertEquals("", switched.titleState.anchor)
    }

    // ---- 状态跟着会话实例走：流式复制、手改同一个字符串、手动重新生成 ----

    @Test
    fun `streaming copies inside the session keep the automatic title state`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(storedConversation(id))
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, store, {})
        session.initialize(load = { store.persisted })
        val user = session.addUser("帮我选一台拍照好的手机")
        session.update { it.withEarlyTitle("选手机", requireNotNull(TitleTrigger.of(user))) }

        // 流式生成：每来一段都是一次 copy 出来的新实例
        repeat(4) { step ->
            session.update { it.copy(messageNodes = it.messageNodes + UIMessage.assistant("输出$step").toMessageNode()) }
            assertTrue("复制之后仍然是自动标题", session.state.value.titleState.isAuto)
            assertTrue("还在等精修", session.state.value.titleState.pendingEarly)
        }

        // 回答结束：精修仍然写得进去（状态丢了的话这里会被当成手改，永远不再精修）
        val captured = session.state.value
        val guard = TitleWriteGuard(
            title = captured.title,
            source = captured.titleState.source,
            branch = requireNotNull(captured.currentMessages.titleBranchAnchor()),
            force = false,
        )
        var persisted: String? = null
        val written = session.updateMetadata(
            transform = { it.refinedTitle("拍照手机对比", "fp-answer") },
            persist = { persisted = it.title },
            predicate = guard::allows,
        )
        assertTrue(written)
        assertEquals("拍照手机对比", persisted)
        assertFalse(session.state.value.titleState.pendingEarly)
    }

    @Test
    fun `manual rename to the same string blocks the late automatic title`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(storedConversation(id))
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, store, {})
        session.initialize(load = { store.persisted })
        val user = session.addUser("帮我写周报")
        session.update { it.withEarlyTitle("周报", requireNotNull(TitleTrigger.of(user))) }

        // 标题请求发起时看到的是自动标题
        val captured = session.state.value
        val guard = TitleWriteGuard(
            title = captured.title,
            source = captured.titleState.source,
            branch = requireNotNull(captured.currentMessages.titleBranchAnchor()),
            force = false,
        )
        // 用户手改成同一个字符串：标题没变，来源变了
        session.update { it.withManualTitle("周报") }

        var persisted = false
        val written = session.updateMetadata(
            transform = { it.refinedTitle("自动周报", "fp") },
            persist = { persisted = true },
            predicate = guard::allows,
        )
        assertFalse("标题字符串一样也不能写", written)
        assertFalse(persisted)
        assertEquals("周报", session.state.value.title)
        assertFalse(session.state.value.titleState.isAuto)
    }

    @Test
    fun `a late title for another branch is dropped`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(storedConversation(id))
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, store, {})
        session.initialize(load = { store.persisted })
        val user = session.addUser("A 分支的问题")
        val captured = session.state.value
        val guard = TitleWriteGuard(
            title = captured.title,
            source = captured.titleState.source,
            branch = requireNotNull(captured.currentMessages.titleBranchAnchor()),
            force = false,
        )

        // 期间用户切到另一条分支
        session.update { it.copy(messageNodes = listOf(UIMessage.user("B 分支的问题").toMessageNode())) }

        var persisted = false
        val written = session.updateMetadata(
            transform = { it.withEarlyTitle("A 的标题", requireNotNull(TitleTrigger.of(user))) },
            persist = { persisted = true },
            predicate = guard::allows,
        )
        assertFalse("分支换了，A 的标题不能落到 B 上", written)
        assertFalse(persisted)
        assertEquals("", session.state.value.title)
    }

    @Test
    fun `a forced regenerate replaces a manual title and keeps it manual`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(storedConversation(id))
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, store, {})
        session.initialize(load = { store.persisted })
        session.addUser("帮我写周报")
        session.update { it.withManualTitle("我的周报") }

        val captured = session.state.value
        // 手动重新生成允许替换已经存在的手动标题，仍受请求快照保护
        val guard = TitleWriteGuard(
            title = captured.title,
            source = captured.titleState.source,
            branch = null,
            force = true,
        )
        var persisted: String? = null
        val written = session.updateMetadata(
            transform = { it.withForcedTitle("重新生成的周报", "fp-forced") },
            persist = { persisted = it.title },
            predicate = guard::allows,
        )
        assertTrue("手改过的标题也能重新生成", written)
        assertEquals("重新生成的周报", persisted)
        assertEquals("重新生成的周报", session.state.value.title)
        assertFalse("重新生成之后仍然是用户定的标题", session.state.value.titleState.isAuto)
        assertFalse(session.state.value.titleState.pendingEarly)
    }

    // ---- 单飞挡掉的那一次：等前面做完再补上精修 ----

    @Test
    fun `a title blocked by the gate runs after the request in flight releases it`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, FakeConversationStore(), {})
        session.initialize(load = { null }, create = { Conversation.ofId(id, assistantId = Uuid.random(), newConversation = true) })

        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var followUpRan = false
        // 早标题还在请求中
        val early = launch(start = CoroutineStart.UNDISPATCHED) {
            session.singleFlightTitle {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        // 一轮结束的标题撞上来：抢不到位置
        assertNull(session.singleFlightTitle { true })

        // 但它会排队等：等早标题让出位置之后再判断一次，精修就靠这次补上
        val followUp = launch(start = CoroutineStart.UNDISPATCHED) {
            followUpRan = session.queuedTitle(5_000) { true } == true
        }
        assertFalse("前面还没做完，先等着", followUpRan)
        release.complete(Unit)
        early.join()
        followUp.join()
        assertTrue("早标题让出位置之后精修还能做", followUpRan)
    }

    @Test
    fun `a queued title gives up when the request in flight never finishes`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), this, FakeConversationStore(), {})
        session.initialize(load = { null }, create = { Conversation.ofId(id, assistantId = Uuid.random(), newConversation = true) })
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val early = launch(start = CoroutineStart.UNDISPATCHED) {
            session.singleFlightTitle {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()

        var ran = true
        ran = session.queuedTitle(100) { true } != null
        assertFalse("等太久就放弃，不能一直挂着", ran)
        release.complete(Unit)
        early.join()
        assertEquals("位置还在，下一次照常", true, session.singleFlightTitle { true })
    }
}
