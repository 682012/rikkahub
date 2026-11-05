package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.agent.GenerationTiming
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * r21「轨道」运行态与上下文表盘的纯函数测试（不需要设备）。
 *
 * 三条底线：
 * 1. 运行态只从既有数据推导，不新存状态，也不伪造数值：测不到速度就是 UNAVAILABLE，
 *    而不是把"—"说成 0；
 * 2. 四种状态的颜色 / 文案互不相同，运行状态在轨道上不会被"看起来一样"地藏掉；
 * 3. 表盘宽度门槛随字体缩放放大，大字体下宁可少画装饰，也不挤掉模型名和 TPS。
 */
class OrbitRailStateTest {

    private fun conversationOf(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        title = "轨道验收",
        messageNodes = messages.map { MessageNode(messages = listOf(it), selectIndex = 0) },
    )

    private fun finishedMessage(timing: GenerationTiming?) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        finishedAt = LocalDateTime.parse("2026-11-03T20:00:00"),
        generationTiming = timing,
    )

    @Test
    fun runStateCoversEveryCombination() {
        assertEquals(OrbitRunState.IDLE, orbitRunState(hasAssistantMessage = false, streaming = false, hasSpeed = false))
        // 没有助手消息时，其它两个参数不该把它翻成"生成中"
        assertEquals(OrbitRunState.IDLE, orbitRunState(hasAssistantMessage = false, streaming = true, hasSpeed = true))
        assertEquals(OrbitRunState.STREAMING, orbitRunState(hasAssistantMessage = true, streaming = true, hasSpeed = false))
        assertEquals(OrbitRunState.STREAMING, orbitRunState(hasAssistantMessage = true, streaming = true, hasSpeed = true))
        assertEquals(OrbitRunState.MEASURED, orbitRunState(hasAssistantMessage = true, streaming = false, hasSpeed = true))
        assertEquals(OrbitRunState.UNAVAILABLE, orbitRunState(hasAssistantMessage = true, streaming = false, hasSpeed = false))
    }

    @Test
    fun streamingIsReadFromTheSameFinishedFlagAsTheSpeedFooter() {
        val streaming = conversationOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), finishedAt = null),
        )
        assertEquals(OrbitRunState.STREAMING, orbitRunStateOf(streaming))
    }

    @Test
    fun measuredAndUnavailableComeStraightFromTheSharedSpeedNumbers() {
        val measured = conversationOf(
            finishedMessage(GenerationTiming(generationMs = 2000, outputTokens = 100, measuredSteps = 1, totalSteps = 1)),
        )
        assertEquals(OrbitRunState.MEASURED, orbitRunStateOf(measured))
        assertEquals(50.0, conversationSpeedUi(measured).speed!!, 0.001)

        // 生成结束但没有可信窗口：轨道说"未测到"，绝不拿 0 冒充
        val unmeasured = conversationOf(finishedMessage(timing = null))
        assertEquals(OrbitRunState.UNAVAILABLE, orbitRunStateOf(unmeasured))
        assertNull(conversationSpeedUi(unmeasured).speed)
    }

    @Test
    fun emptyConversationIsIdleAndUserOnlyMessagesDoNotCount() {
        assertEquals(OrbitRunState.IDLE, orbitRunStateOf(conversationOf()))
        val userOnly = conversationOf(UIMessage(role = MessageRole.USER, parts = emptyList()))
        assertEquals(OrbitRunState.IDLE, orbitRunStateOf(userOnly))
    }

    @Test
    fun contextRatioRefusesBadNumbersInsteadOfDrawingAFakeZero() {
        assertNull(orbitContextRatio(null, 128_000))
        assertNull(orbitContextRatio(1_000, null))
        assertNull(orbitContextRatio(1_000, 0))
        assertNull(orbitContextRatio(-1, 128_000))
        assertEquals(0f, orbitContextRatio(0, 128_000)!!, 0f)
        assertEquals(0.5f, orbitContextRatio(64_000, 128_000)!!, 0f)
        assertEquals(1f, orbitContextRatio(999_999, 128_000)!!, 0f)
    }

    @Test
    fun gaugeOnlyShowsWhenThereIsRoomForItAtThisFontScale() {
        assertTrue(orbitContextGaugeVisible(widthDp = 380f, fontScale = 1f))
        assertFalse(orbitContextGaugeVisible(widthDp = 379f, fontScale = 1f))
        // 1.3 倍字体：阈值一起放大，320dp 的小屏一定不画，模型名不会被挤掉
        assertFalse(orbitContextGaugeVisible(widthDp = 411f, fontScale = 1.3f))
        assertTrue(orbitContextGaugeVisible(widthDp = 500f, fontScale = 1.3f))
        assertTrue(orbitContextGaugeVisible(widthDp = 500f, fontScale = 0.85f))
        // 坏值一律不画，而不是抛异常或者画到屏幕外
        assertFalse(orbitContextGaugeVisible(widthDp = Float.NaN, fontScale = 1f))
        assertFalse(orbitContextGaugeVisible(widthDp = 0f, fontScale = 1f))
        assertFalse(orbitContextGaugeVisible(widthDp = -10f, fontScale = 1f))
        assertTrue(
            "fontScale 是坏值时按 1 倍算，宽屏照常显示",
            orbitContextGaugeVisible(widthDp = 400f, fontScale = Float.NaN),
        )
    }

    @Test
    fun everyRunStateHasItsOwnColorAndLabel() {
        listOf(lightColorScheme(), darkColorScheme()).forEach { scheme ->
            val colors = OrbitRunState.entries.map { orbitRunColor(it, scheme) }
            colors.forEach { color -> assertTrue("状态色不能是透明的", color.alpha > 0f) }
            // 恢复中和生成中都是 tertiary（同一个"正在进行"色），可区分的状态必须五色分明
            assertEquals(
                "除 RECOVERING/STREAMING 共用 tertiary 外，各状态色必须互不相同",
                OrbitRunState.entries.size - 1,
                colors.toSet().size,
            )
            assertEquals(scheme.error, orbitRunColor(OrbitRunState.ERROR, scheme))
            assertEquals(scheme.outlineVariant, orbitRunColor(OrbitRunState.UNAVAILABLE, scheme))
        }
        val labels = OrbitRunState.entries.map { orbitRunStateRes(it) }
        assertEquals("每个状态都要有自己的文案", labels.size, labels.toSet().size)
    }

    @Test
    fun recoveringOnlyWhenTheEngineIsIdleButAStatusIsPending() {
        // 等待重连 / 已排定重试：任务没在跑，状态还挂着
        assertTrue(orbitRecovering(hasPendingStatus = true, generationActive = false))
        assertTrue(orbitRecovering(hasPendingStatus = true, generationActive = null))
        // OCR、上下文压缩、单次重试都发生在任务内部，不能把顶部说成"正在恢复"
        assertFalse(orbitRecovering(hasPendingStatus = true, generationActive = true))
        // 没有状态就不是恢复
        assertFalse(orbitRecovering(hasPendingStatus = false, generationActive = false))
        assertFalse(orbitRecovering(hasPendingStatus = false, generationActive = null))
    }

    @Test
    fun headerStateKeepsRecoveryErrorAndStreamingApart() {
        val streaming = conversationOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), finishedAt = null),
        )
        val finishedUnmeasured = conversationOf(finishedMessage(timing = null))
        val finishedMeasured = conversationOf(
            finishedMessage(GenerationTiming(generationMs = 2000, outputTokens = 100, measuredSteps = 1, totalSteps = 1)),
        )
        val empty = conversationOf()

        // 恢复中压过错误：重连期间不该报"已中断，需处理"
        assertEquals(
            OrbitRunState.RECOVERING,
            orbitHeaderRunState(
                generationActive = false, hasError = true, recovering = true, conversation = finishedUnmeasured,
            ),
        )
        // 有错误且没在跑、也没有恢复任务：确实是需要人处理
        assertEquals(
            OrbitRunState.ERROR,
            orbitHeaderRunState(
                generationActive = false, hasError = true, recovering = false, conversation = finishedUnmeasured,
            ),
        )
        // 生成中：哪怕错误列表里还留着上一条，也不能把状态说成恢复
        assertEquals(
            OrbitRunState.STREAMING,
            orbitHeaderRunState(
                generationActive = true, hasError = true, recovering = false, conversation = streaming,
            ),
        )
        // 生成结束但测不到速度：是"未测到"，不是"待机"，更不能假装测到了
        assertEquals(
            OrbitRunState.UNAVAILABLE,
            orbitHeaderRunState(
                generationActive = false, hasError = false, recovering = false, conversation = finishedUnmeasured,
            ),
        )
        assertEquals(
            OrbitRunState.MEASURED,
            orbitHeaderRunState(
                generationActive = false, hasError = false, recovering = false, conversation = finishedMeasured,
            ),
        )
        // 真没有助手消息才是待机
        assertEquals(
            OrbitRunState.IDLE,
            orbitHeaderRunState(
                generationActive = false, hasError = false, recovering = false, conversation = empty,
            ),
        )
    }
}
