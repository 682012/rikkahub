package me.rerere.rikkahub.ui.floating

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.companion.CompanionAiPhase
import me.rerere.rikkahub.data.companion.CompanionAiState
import me.rerere.rikkahub.data.companion.CompanionFailure
import me.rerere.rikkahub.data.companion.CompanionInteraction
import me.rerere.rikkahub.data.companion.CompanionModelDescription
import me.rerere.rikkahub.data.companion.CompanionTrigger
import me.rerere.rikkahub.data.companion.CompanionTriggerKind
import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 小助手的 Compose 入口：状态卡、面板里的状态卡、以及"点击 -> 真实生成"这条接线。
 *
 * 只用 Compose rule，不启动 Koin、不碰网络、不点真实悬浮窗权限：状态全由用例显式给，
 * 断言的是"界面显示的就是给它的真实状态"和"按钮真的回调到控制器入口"。
 *
 * 明确不声称：模型真的回了什么（那要靠真实账号，用单测里的假 source 覆盖），
 * 以及动效/像素观感。
 */
@RunWith(AndroidJUnit4::class)
class CompanionAiEntryTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val modelLabel = "test-model · Test"

    private fun completedState() = CompanionAiState(
        phase = CompanionAiPhase.COMPLETED,
        model = CompanionModelDescription(label = modelLabel, source = CompanionModelMode.FOLLOW_CONVERSATION),
        trigger = CompanionTrigger(CompanionTriggerKind.TAP),
        text = "在呢",
    )

    private fun interaction(text: String, at: Long = 1_700_000_000_000L) = CompanionInteraction(
        trigger = CompanionTrigger(CompanionTriggerKind.INPUT, detail = "在吗"),
        phase = CompanionAiPhase.COMPLETED,
        text = text,
        modelLabel = modelLabel,
        atMillis = at,
    )

    private fun textShown(text: String): Boolean =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun setCard(
        state: CompanionAiState,
        interactions: List<CompanionInteraction> = emptyList(),
        expanded: Boolean = false,
        onExpandedChange: (Boolean) -> Unit = {},
        onRefresh: () -> Unit = {},
        onRetry: () -> Unit = {},
        onStop: () -> Unit = {},
        onSend: (String) -> Boolean = { true },
        onOpenSettings: () -> Unit = {},
        onEnableAi: (() -> Unit)? = null,
    ) {
        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                CompanionAiStatusCard(
                    state = state,
                    interactions = interactions,
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
                    onRefresh = onRefresh,
                    onRetry = onRetry,
                    onStop = onStop,
                    onSend = onSend,
                    onOpenSettings = onOpenSettings,
                    onEnableAi = onEnableAi,
                )
            }
        }
    }

    @Test
    fun collapsedCardShowsTheRealModelPhaseAndLastLine() {
        setCard(completedState())
        rule.onNodeWithTag(CompanionStatusTestTags.CARD).assertIsDisplayed()
        assertTrue("model label must be the resolved one", textShown(modelLabel))
        assertTrue("phase must be completed", textShown(context.getString(R.string.companion_status_phase_completed)))
        assertTrue("the generated line must be visible", textShown("在呢"))
        assertFalse(textShown(context.getString(R.string.companion_status_recent)))
    }

    @Test
    fun expandingShowsDetailsRecordsRefreshAndRetry() {
        var expanded by mutableStateOf(false)
        val refreshes = AtomicInteger()
        val retries = AtomicInteger()
        setCard(
            state = completedState(),
            interactions = listOf(interaction("上一句")),
            expanded = expanded,
            onExpandedChange = { expanded = it },
            onRefresh = { refreshes.incrementAndGet() },
            onRetry = { retries.incrementAndGet() },
        )
        rule.onNodeWithTag(CompanionStatusTestTags.TOGGLE).performClick()
        rule.waitForIdle()

        assertTrue(textShown(context.getString(R.string.companion_status_recent)))
        assertTrue("recent records must show their text", textShown("上一句"))
        assertTrue("follow mode must explain its privacy", textShown(context.getString(R.string.companion_status_privacy)))

        rule.onNodeWithTag(CompanionStatusTestTags.REFRESH).performClick()
        rule.onNodeWithTag(CompanionStatusTestTags.RETRY).performClick()
        assertEquals(1, refreshes.get())
        assertEquals(1, retries.get())
    }

    @Test
    fun busyStateOffersStopAndCallsIt() {
        val stops = AtomicInteger()
        setCard(
            state = CompanionAiState(
                phase = CompanionAiPhase.GENERATING,
                model = CompanionModelDescription(label = modelLabel, source = CompanionModelMode.FIXED),
                text = "正在",
            ),
            onStop = { stops.incrementAndGet() },
        )
        rule.onNodeWithTag(CompanionStatusTestTags.STOP).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, stops.get())
    }

    @Test
    fun missingModelIsStatedExplicitlyAndLinksToSettings() {
        var opened = 0
        setCard(
            state = CompanionAiState(
                phase = CompanionAiPhase.FAILED,
                model = CompanionModelDescription(failure = CompanionFailure.ModelMissing),
                failure = CompanionFailure.ModelMissing,
            ),
            expanded = true,
            onOpenSettings = { opened++ },
        )
        assertTrue(textShown(context.getString(R.string.companion_status_model_missing)))
        rule.onNodeWithTag(CompanionStatusTestTags.OPEN_SETTINGS).performClick()
        rule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun aiOffersAOneTapAuthorizationRightInThePanel() {
        var enabled = 0
        setCard(
            state = CompanionAiState(
                phase = CompanionAiPhase.IDLE,
                model = CompanionModelDescription(failure = CompanionFailure.AiDisabled),
            ),
            onEnableAi = { enabled++ },
        )
        rule.onNodeWithTag(CompanionStatusTestTags.ENABLE).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, enabled)
    }

    @Test
    fun typedLineIsHandedToTheController() {
        val sent = mutableListOf<String>()
        setCard(state = completedState(), expanded = true, onSend = { text -> sent += text; true })
        rule.onNodeWithTag(CompanionStatusTestTags.INPUT).performTextInput("在吗")
        rule.onNodeWithTag(CompanionStatusTestTags.SEND).performClick()
        rule.waitForIdle()
        assertEquals(listOf("在吗"), sent)
    }

    @Test
    fun panelStillApprovesToolsAndHostsTheStatusCard() {
        val approvals = mutableListOf<Boolean>()
        var expanded by mutableStateOf(false)
        val state = previewCompanionState().copy(
            pendingTools = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "shell",
                    input = "{\"command\":\"ls\"}",
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
        )
        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                ChatFloatingPanel(
                    state = state,
                    maxHeight = 520.dp,
                    onCollapse = {},
                    onDismiss = {},
                    onOpenConversation = {},
                    onToolApproval = { _, approved -> approvals += approved },
                    onStop = {},
                    onSend = {},
                    onResumeQueue = {},
                    companionState = completedState(),
                    companionInteractions = listOf(interaction("上一句")),
                    companionExpanded = expanded,
                    onCompanionExpandedChange = { expanded = it },
                )
            }
        }
        rule.onNodeWithTag(CompanionStatusTestTags.CARD).assertIsDisplayed()
        rule.onNodeWithTag(CompanionStatusTestTags.TOGGLE).performClick()
        rule.waitForIdle()
        assertTrue(textShown("上一句"))

        // the original tool approval must still be there
        assertTrue(textShown(context.getString(R.string.chat_message_tool_approve)))
        rule.onAllNodesWithText(context.getString(R.string.chat_message_tool_approve), substring = true)[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), approvals)
    }

    /** provider 的报错原文带着响应体/密钥时，覆盖层里只能看到脱敏后的短句。 */
    @Test
    fun providerErrorTextIsRedactedBeforeItIsShown() {
        val raw = "Failed to get response: 401 {\"error\":\"invalid key Bearer sk-live-abcdef123456\"} " +
            "https://api.example.com/v1?key=sk-secret-value"
        setCard(
            state = CompanionAiState(
                phase = CompanionAiPhase.FAILED,
                model = CompanionModelDescription(label = modelLabel, source = CompanionModelMode.FIXED),
                failure = CompanionFailure.Request(raw),
            ),
            expanded = true,
        )
        assertTrue("the failure must be stated", textShown("Failed to get response"))
        assertFalse("api keys must not reach the overlay", textShown("sk-live-abcdef123456"))
        assertFalse("query keys must not reach the overlay", textShown("sk-secret-value"))
        assertTrue("redaction must be visible as such", textShown("Bearer ***"))
    }

    // --------------------------------------------- panel layout / keyboard / drafts

    private fun busyState() = CompanionAiState(
        phase = CompanionAiPhase.GENERATING,
        model = CompanionModelDescription(label = modelLabel, source = CompanionModelMode.FIXED),
        text = "正在",
    )

    /** 面板里的小助手状态卡 + 真实的主聊天状态。和其它用例一样，状态全部由用例显式给。 */
    private fun setPanel(
        maxHeight: Dp,
        companionState: CompanionAiState,
        interactions: List<CompanionInteraction> = emptyList(),
        expanded: () -> Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onChatSend: (String) -> Unit = {},
        onCompanionSend: (String) -> Boolean = { false },
        onCompanionRefresh: () -> Unit = {},
        onCompanionStop: () -> Unit = {},
        onToolApproval: (String, Boolean) -> Unit = { _, _ -> },
        pendingTools: List<UIMessagePart.Tool> = emptyList(),
    ) {
        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                ChatFloatingPanel(
                    state = previewCompanionState().copy(pendingTools = pendingTools),
                    maxHeight = maxHeight,
                    onCollapse = {},
                    onDismiss = {},
                    onOpenConversation = {},
                    onToolApproval = onToolApproval,
                    onStop = {},
                    onSend = onChatSend,
                    onResumeQueue = {},
                    companionState = companionState,
                    companionInteractions = interactions,
                    companionExpanded = expanded(),
                    onCompanionExpandedChange = onExpandedChange,
                    onCompanionRefresh = onCompanionRefresh,
                    onCompanionStop = onCompanionStop,
                    onCompanionSend = onCompanionSend,
                )
            }
        }
    }

    private fun bounds(tag: String): androidx.compose.ui.geometry.Rect =
        rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /**
     * 小屏 + 展开的状态卡：状态卡必须和预览一起滚在同一个受限区里，
     * 主聊天回复栏（发送/停止）仍然留在面板内部、还能打字发出去。
     *
     * 断言看的是布局坐标，不是"文本存在"：旧结构下面板的回复栏会被顶到 440dp/300dp 之外。
     */
    @Test
    fun expandedStatusCardScrollsInsteadOfPushingTheReplyBarOutOfThePanel() {
        var expanded by mutableStateOf(false)
        val refreshes = AtomicInteger()
        val chatSent = mutableListOf<String>()
        setPanel(
            maxHeight = 300.dp, // 小屏：面板上限比 440dp 还小
            companionState = completedState(),
            interactions = (1..8).map { interaction("记录 $it") },
            expanded = { expanded },
            onExpandedChange = { expanded = it },
            onChatSend = { chatSent += it },
            onCompanionRefresh = { refreshes.incrementAndGet() },
        )
        rule.onNodeWithTag(CompanionStatusTestTags.TOGGLE).performClick()
        rule.waitForIdle()

        val panel = bounds(FloatingPanelTestTags.PANEL)
        val bar = bounds(FloatingPanelTestTags.REPLY_BAR)
        val capPx = 300f * context.resources.displayMetrics.density
        assertTrue("panel must respect the height cap: $panel cap=${capPx}px", panel.height <= capPx + 1f)
        assertTrue(
            "the reply bar must stay inside the panel, bar=$bar panel=$panel",
            bar.top >= panel.top - 1f && bar.bottom <= panel.bottom + 1f && bar.height > 0f,
        )

        // 状态卡展开后的内容仍在面板里滚动得到、点得着
        rule.onNodeWithTag(FloatingPanelTestTags.SCROLL)
            .performScrollToNode(hasTestTag(CompanionStatusTestTags.REFRESH))
        rule.onNodeWithTag(CompanionStatusTestTags.REFRESH).performClick()
        rule.waitForIdle()
        assertEquals(1, refreshes.get())
        assertTrue(
            "scrolling inside the panel must not move the reply bar out of it",
            bounds(FloatingPanelTestTags.REPLY_BAR).bottom <= bounds(FloatingPanelTestTags.PANEL).bottom + 1f,
        )

        // 主聊天输入栏：打字时面板再收一档（键盘不会盖住输入栏），发出去的话不丢
        val heightBeforeTyping = bounds(FloatingPanelTestTags.PANEL).height
        rule.onNodeWithTag(FloatingPanelTestTags.REPLY_INPUT).performTextInput("在吗")
        rule.waitForIdle()
        val heightWhileTyping = bounds(FloatingPanelTestTags.PANEL).height
        assertTrue(
            "panel must shrink while the keyboard is up: $heightBeforeTyping -> $heightWhileTyping",
            heightWhileTyping < heightBeforeTyping,
        )
        assertTrue(
            "reply bar must stay reachable while typing",
            bounds(FloatingPanelTestTags.REPLY_BAR).bottom <= bounds(FloatingPanelTestTags.PANEL).bottom + 1f,
        )
        rule.onNodeWithTag(FloatingPanelTestTags.REPLY_SEND).performClick()
        rule.waitForIdle()
        assertEquals(listOf("在吗"), chatSent)
    }

    /** 控制器拒绝了这句（正忙/冷却/窗口不可见）：面板必须把用户原话留在输入框里，并写清没发出去。 */
    @Test
    fun rejectedCompanionSendKeepsTheTypedLine() {
        var expanded by mutableStateOf(true)
        var accept by mutableStateOf(false)
        val attempts = mutableListOf<String>()
        setPanel(
            maxHeight = 440.dp,
            companionState = completedState(),
            expanded = { expanded },
            onExpandedChange = { expanded = it },
            onCompanionSend = { text -> attempts += text; accept },
        )
        rule.onNodeWithTag(FloatingPanelTestTags.SCROLL)
            .performScrollToNode(hasTestTag(CompanionStatusTestTags.INPUT))
        rule.onNodeWithTag(CompanionStatusTestTags.INPUT).performTextInput("在吗")
        rule.onNodeWithTag(CompanionStatusTestTags.SEND).performClick()
        rule.waitForIdle()

        assertEquals(listOf("在吗"), attempts)
        rule.onNodeWithTag(FloatingPanelTestTags.SCROLL)
            .performScrollToNode(hasTestTag(CompanionStatusTestTags.SEND_KEPT))
        rule.onNodeWithTag(CompanionStatusTestTags.SEND_KEPT).assertIsDisplayed()
        rule.onNodeWithTag(CompanionStatusTestTags.INPUT).assertTextContains("在吗")
        assertTrue("the draft must survive a rejected send", textShown("在吗"))

        // 收下之后才清空：草稿不是被无条件丢掉的
        accept = true
        rule.onNodeWithTag(FloatingPanelTestTags.SCROLL)
            .performScrollToNode(hasTestTag(CompanionStatusTestTags.SEND))
        rule.onNodeWithTag(CompanionStatusTestTags.SEND).performClick()
        rule.waitForIdle()
        assertEquals(listOf("在吗", "在吗"), attempts)
        assertFalse("accepted send clears the draft", textShown("在吗"))
    }

    /** 卡上生成中：发送按钮禁用，键盘发送键也不发，草稿原样留着。 */
    @Test
    fun busyCompanionIgnoresImeSendAndKeepsTheDraft() {
        val sent = mutableListOf<String>()
        setCard(state = busyState(), expanded = true, onSend = { text -> sent += text; true })
        rule.onNodeWithTag(CompanionStatusTestTags.INPUT).performTextInput("在吗")
        rule.onNodeWithTag(CompanionStatusTestTags.SEND).assertIsNotEnabled()
        rule.onNodeWithTag(CompanionStatusTestTags.INPUT).performImeAction()
        rule.waitForIdle()
        assertTrue("nothing may be sent while the companion is busy: $sent", sent.isEmpty())
        rule.onNodeWithTag(CompanionStatusTestTags.INPUT).assertTextContains("在吗")
    }

    /** 排队/审批还在时，主聊天回复栏没有被挤出面板（同一滚动区不会顶掉固定栏）。 */
    @Test
    fun pendingApprovalKeepsItsButtonsAndTheReplyBarReachable() {
        var expanded by mutableStateOf(false)
        val approvals = mutableListOf<Boolean>()
        setPanel(
            maxHeight = 340.dp,
            companionState = completedState(),
            interactions = (1..6).map { interaction("记录 $it") },
            expanded = { expanded },
            onExpandedChange = { expanded = it },
            onToolApproval = { _, approved -> approvals += approved },
            pendingTools = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "shell",
                    input = "{\"command\":\"ls\"}",
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
        )
        rule.onNodeWithTag(CompanionStatusTestTags.TOGGLE).performClick()
        rule.waitForIdle()
        // 审批卡出现后自动滚进视野：批准按钮点得到，回复栏仍在面板里
        val approveLabel = context.getString(R.string.chat_message_tool_approve)
        rule.onNodeWithTag(FloatingPanelTestTags.SCROLL)
            .performScrollToNode(hasText(approveLabel, substring = true))
        rule.onAllNodesWithText(approveLabel, substring = true)[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), approvals)
        val panel = bounds(FloatingPanelTestTags.PANEL)
        val bar = bounds(FloatingPanelTestTags.REPLY_BAR)
        assertTrue("reply bar must stay inside the panel: bar=$bar panel=$panel", bar.bottom <= panel.bottom + 1f)
    }

    // ------------------------------------------------------- tap -> generate wiring

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private fun setBubble(
        companion: FloatingCompanionSettings,
        clicks: AtomicInteger,
        aiTriggers: AtomicInteger,
        aiState: CompanionAiState = CompanionAiState(),
    ) {
        val owner = TestLifecycleOwner()
        rule.runOnUiThread { owner.moveTo(Lifecycle.State.RESUMED) }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                    ChatFloatingBubble(
                        state = previewCompanionState(),
                        onClick = { clicks.incrementAndGet() },
                        companion = companion,
                        aiState = aiState,
                        onAiTrigger = { aiTriggers.incrementAndGet() },
                    )
                }
            }
        }
    }

    @Test
    fun tapWithAiOnAsksTheModelInsteadOfReadingStaticLines() {
        val clicks = AtomicInteger()
        val aiTriggers = AtomicInteger()
        setBubble(
            companion = FloatingCompanionSettings(
                preset = FloatingCompanionPreset.WHALE_MAID,
                animationEnabled = false,
                minimized = false,
                aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION,
            ),
            clicks = clicks,
            aiTriggers = aiTriggers,
        )
        rule.waitForIdle()
        rule.onNodeWithTag("portrait-character").performClick()
        rule.waitUntil(3_000) { aiTriggers.get() == 1 }
        assertEquals(1, aiTriggers.get())
        // static lines must not pretend to be the model
        assertFalse(textShown(context.getString(R.string.portrait_line_1)))
        assertFalse(textShown(context.getString(R.string.portrait_line_2)))
        // a tap still opens the panel
        rule.waitUntil(3_000) { clicks.get() == 1 }
    }

    @Test
    fun tapWithAiOffKeepsTheStaticLine() {
        val clicks = AtomicInteger()
        val aiTriggers = AtomicInteger()
        setBubble(
            companion = FloatingCompanionSettings(
                preset = FloatingCompanionPreset.WHALE_MAID,
                animationEnabled = false,
                minimized = false,
                aiModelMode = CompanionModelMode.OFF,
            ),
            clicks = clicks,
            aiTriggers = aiTriggers,
        )
        rule.waitForIdle()
        rule.onNodeWithTag("portrait-character").performClick()
        rule.waitUntil(3_000) {
            textShown(context.getString(R.string.portrait_line_1)) ||
                textShown(context.getString(R.string.portrait_line_2))
        }
        assertEquals(0, aiTriggers.get())
    }

    @Test
    fun bubbleShowsTheGeneratedLineFromRealState() {
        val clicks = AtomicInteger()
        val aiTriggers = AtomicInteger()
        setBubble(
            companion = FloatingCompanionSettings(
                preset = FloatingCompanionPreset.WHALE_MAID,
                animationEnabled = false,
                minimized = false,
                aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION,
            ),
            clicks = clicks,
            aiTriggers = aiTriggers,
            aiState = completedState(),
        )
        rule.waitForIdle()
        assertTrue("the bubble must show the line from controller state", textShown("在呢"))
    }
}
