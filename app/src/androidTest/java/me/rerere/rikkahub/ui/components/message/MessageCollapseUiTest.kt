package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 长消息正文折叠（[CollapsibleMessageText]）的真实布局/交互回归。
 *
 * 只用 Compose rule + 本地 `MaterialExpressiveTheme`，不启动 Koin、不读设置（`enabled` 由用例显式传入）、
 * 不碰网络，也不改生产代码：需要定位的地方全部用测试侧 `Modifier.testTag`。
 *
 * 断言的都是可证伪的事实：
 * - 折叠态可见高度被裁到一屏左右（镜像生产常量的 280dp），展开后高度真的长回来（差值远超 48dp）；
 * - 折叠**不是字符串截断**：正文子节点整段被测出 900dp、全文语义节点仍在，只裁绘制；
 * - 手动展开/收起记在 [MessageCollapseStateHolder] 里、按 messageId 生效：整条移出组合再回来仍是用户选的状态，
 *   换一条 id 的同样长文仍按默认折叠（排除“全局开关”假实现）；
 * - 流式生成中不折、不给按钮，结束后才折；短正文完全不受影响；关掉设置时老行为不变。
 *
 * 明确不声称的东西：折叠态仍然完整组合/测量（不是 lazy），本文件只断言高度与语义，不测帧率、不截图。
 */
@RunWith(AndroidJUnit4::class)
class MessageCollapseUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 生产里 `CollapsedPreviewMaxHeight` 是 private 常量，这里镜像同一个值做上限断言。 */
    private val previewMax = 280.dp

    /** 展开/收起按钮：48dp 触控 + 4dp 上间距，折叠态总高最多这么多。 */
    private val toggleBudget = 56.dp

    /** 测试专用正文高度：确定性，不依赖文字换行结果。 */
    private val fakeBodyHeight = 900.dp

    private var mounted by mutableStateOf(true)
    private var renderingId by mutableStateOf(LONG_ID)
    private var streaming by mutableStateOf(false)

    @Before
    fun resetHolder() {
        MessageCollapseStateHolder.reset()
        mounted = true
        renderingId = LONG_ID
        streaming = false
    }

    @After
    fun clearHolder() {
        MessageCollapseStateHolder.reset()
    }

    // ------------------------------------------------------------------ helpers

    /** 10 段 × 89 个非空白字符 = 890，稳过 700 字阈值；20 行、10 段也满足多行规则。 */
    private fun longBody(): String = buildString {
        repeat(10) { index ->
            append("Block $index ")
            append("lorem ipsum dolor sit amet ".repeat(3))
            append("\n\n")
        }
    }

    /**
     * 蒙太奇：正文渲染用固定高度的假子节点，折叠判定仍旧吃真实 `textParts`。
     * 这样高度断言不受字体/换行影响，而“被测出的高度 900dp 大于被裁的 280dp”本身就是“不是截断”的证据。
     */
    private fun show(
        textParts: List<String>,
        enabled: Boolean = true,
        content: @Composable () -> Unit = {
            Box(Modifier.testTag(CHILD_TAG).fillMaxWidth().height(fakeBodyHeight)) { Text(textParts.joinToString("")) }
        },
    ) {
        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                // 和聊天页一样，消息条目在无界高度里测量（LazyColumn/滚动容器），窗口高度不参与断言
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (mounted) {
                        CollapsibleMessageText(
                            messageId = renderingId,
                            textParts = textParts,
                            isAssistant = true,
                            streaming = streaming,
                            enabled = enabled,
                            fadeColor = Color.White,
                            modifier = Modifier.testTag(ROOT_TAG),
                            content = content,
                        )
                    } else {
                        // 模拟 LazyColumn 把整条消息回收掉
                        Box(Modifier.testTag(UNMOUNTED_TAG))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun rootHeight(): Dp = rule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun childHeight(): Dp = rule.onNodeWithTag(CHILD_TAG).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun expandLabel() = context.getString(R.string.message_collapse_r8_expand)

    private fun collapseLabel() = context.getString(R.string.message_collapse_r8_collapse)

    private fun assertCollapsedHeight() {
        val height = rootHeight()
        assertTrue("折叠态高度 $height 不该低于预览上限 $previewMax", height >= previewMax)
        assertTrue(
            "折叠态高度 $height 超出 $previewMax + 按钮 $toggleBudget，说明正文没被裁",
            height <= previewMax + toggleBudget,
        )
    }

    private fun assertNoToggle() {
        rule.onAllNodesWithText(expandLabel()).assertCountEquals(0)
        rule.onAllNodesWithText(collapseLabel()).assertCountEquals(0)
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun longBodyClipsToPreviewAndManualStateFollowsMessageId() {
        val body = longBody()
        assertTrue(
            "测试正文只有 ${body.count { !it.isWhitespace() }} 个非空白字符，不够长就不该折",
            body.count { !it.isWhitespace() } >= MIN_EXPECTED_CHARS,
        )
        show(textParts = listOf(body))

        // 1. 默认折叠：按钮是“展开全文”，可见高度被裁到一屏，但正文本身完整测出 900dp
        rule.onNodeWithTag(ROOT_TAG).assertIsDisplayed()
        rule.onNodeWithText(expandLabel()).assertIsDisplayed()
        assertCollapsedHeight()
        val measuredBody = childHeight()
        assertEquals("假子节点应当被完整测量", fakeBodyHeight.value.toDouble(), measuredBody.value.toDouble(), 1.0)
        assertTrue("折叠态可见高度 $measuredBody 不该等于全文高度", rootHeight() < measuredBody)
        // 全文语义节点还在：折叠只裁绘制，没有 Substring，复制/选择拿到的仍是全文
        rule.onNodeWithText(body).assertExists()

        val collapsedHeight = rootHeight()

        // 2. 手动展开：状态记进 holder，高度长回来
        rule.onNodeWithText(expandLabel()).performClick()
        rule.waitForIdle()
        assertTrue("点展开后 holder 必须记成展开", MessageCollapseStateHolder.manualState(LONG_ID) == true)
        val expandedHeight = rootHeight()
        assertTrue("展开后高度 $expandedHeight 应当至少放得下全文 $measuredBody", expandedHeight >= measuredBody)
        assertTrue(
            "展开-折叠高度差 ${expandedHeight - collapsedHeight} 太小，等于没展开",
            expandedHeight - collapsedHeight >= 280.dp,
        )
        rule.onNodeWithText(collapseLabel()).performScrollTo().assertIsDisplayed()

        // 3. 整条移出组合再回来：手动状态按 id 记住，不会被 LazyColumn 回收丢掉
        rule.runOnIdle { mounted = false }
        rule.waitForIdle()
        rule.onAllNodesWithTag(ROOT_TAG).assertCountEquals(0)
        rule.runOnIdle { mounted = true }
        rule.waitForIdle()
        assertTrue("回收后应当仍是展开态", rootHeight() >= measuredBody)

        // 4. 再点收起：记成收起，高度回到预览上限
        rule.onNodeWithText(collapseLabel()).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue("点收起后 holder 必须记成收起", MessageCollapseStateHolder.manualState(LONG_ID) == false)
        assertCollapsedHeight()

        // 5. 同一个 holder 换一条 messageId：没表态过，按默认折叠（证明记的是 id 而不是全局布尔）
        rule.runOnIdle { renderingId = OTHER_ID }
        rule.waitForIdle()
        assertNull("另一条 id 不该继承上一条的手动状态", MessageCollapseStateHolder.manualState(OTHER_ID))
        rule.onNodeWithText(expandLabel()).assertIsDisplayed()
        assertCollapsedHeight()
    }

    @Test
    fun streamingLongBodyStaysOpenWithoutToggleThenCollapsesWhenFinished() {
        streaming = true
        show(textParts = listOf(longBody()))

        // 生成中：不折、不给按钮，正文没被裁
        assertNoToggle()
        val streamingHeight = rootHeight()
        assertTrue(
            "流式生成中高度 $streamingHeight 应当放得下全文 ${childHeight()}",
            streamingHeight >= childHeight(),
        )

        // 生成结束：同一段正文自动折起来，按钮出现
        rule.runOnIdle { streaming = false }
        rule.waitForIdle()
        rule.onNodeWithText(expandLabel()).assertIsDisplayed()
        assertCollapsedHeight()
        assertTrue("结束后应当比生成中矮", rootHeight() < streamingHeight)
    }

    @Test
    fun shortBodyKeepsNaturalHeightWithoutToggle() {
        val short = "Short answer.\n\nNothing worth folding here."
        show(textParts = listOf(short), content = {
            Text(short, modifier = Modifier.testTag(CHILD_TAG))
        })

        assertNoToggle()
        val natural = childHeight()
        assertTrue("短正文自然高度 $natural 不该被裁", natural < previewMax)
        val root = rootHeight()
        assertTrue("短正文根高度 $root 应当等于正文高度 $natural（±1dp）", abs(root.value - natural.value) <= 1f)
        assertNull("短正文不该被记手动状态", MessageCollapseStateHolder.manualState(LONG_ID))
        assertTrue(rule.onAllNodesWithText(short).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun disabledSettingLeavesLongBodyUnclippedAndUntouched() {
        show(textParts = listOf(longBody()), enabled = false)

        assertNoToggle()
        assertTrue("关掉折叠后不该裁正文", rootHeight() >= fakeBodyHeight)
        assertNull("关掉折叠时不该写任何手动状态", MessageCollapseStateHolder.manualState(LONG_ID))
    }

    private companion object {
        const val ROOT_TAG = "r8-collapse-root"
        const val CHILD_TAG = "r8-collapse-body"
        const val UNMOUNTED_TAG = "r8-collapse-unmounted"
        const val LONG_ID = "message-long-a"
        const val OTHER_ID = "message-long-b"

        /** 折叠判定是纯逻辑，这里只需要“够长”，具体数值见 [longBody]。 */
        const val MIN_EXPECTED_CHARS = 700
    }
}
