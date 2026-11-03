package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.SelectableContent
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 消息方向对齐（r9：用户消息 End、助手消息 Start）的真实布局回归。
 *
 * 被测对象是生产 [CollapsibleMessageText] 自己的内层 Column 对齐，不是本文件的模仿实现：
 * 本文件只提供**自然宽**的假气泡（自然宽 Box，不 fillMaxWidth）+ 同一根里的头像/操作标记。
 * - 头像标记固定在根 start、操作标记固定在根 end，于是“根边界”可以直接从布局里读出来，
 *   不靠屏幕宽度常量猜；`End` 用 [LayoutDirection] 方向感知求值，RTL 下自然换算成物理左侧。
 * - 用户气泡的 `padding(start = 24dp)` 只影响气泡内沿，所以断言的是 **body 内容边缘**，
 *   不是带 padding 的气泡外沿（两者只在 end 侧重合）。
 *
 * 修前（r8 内层 Column 默认 Start）会红的关键点：
 * - [shortUserBubbleSitsAtEndEdgeWithoutToggle]：两个字的用户气泡右端会贴在根左端 → 断言必然失败；
 * 其余用例覆盖相邻多段、折叠三态、关设置、流式、窄屏大字号、RTL。
 */
@RunWith(AndroidJUnit4::class)
class MessageAlignmentUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var parts by mutableStateOf(listOf(SHORT_USER))
    private var assistant by mutableStateOf(false)
    private var enabled by mutableStateOf(true)
    private var streaming by mutableStateOf(false)
    private var rtl by mutableStateOf(false)
    private var useRealText by mutableStateOf(false)
    private var bodyHeight by mutableStateOf(40.dp)
    private var columnWidth by mutableStateOf<Dp?>(null)
    private var fontScale by mutableStateOf(1f)

    @Before
    fun resetHolder() {
        MessageCollapseStateHolder.reset()
        parts = listOf(SHORT_USER)
        assistant = false
        enabled = true
        streaming = false
        rtl = false
        useRealText = false
        bodyHeight = 40.dp
        columnWidth = null
        fontScale = 1f
    }

    @After
    fun clearHolder() {
        MessageCollapseStateHolder.reset()
    }

    // ------------------------------------------------------------------ harness

    /** 每个用例只调用一次 `setContent`，之后的参数变化一律走 mutableState。 */
    private fun show() {
        rule.setContent {
            val baseDensity = LocalDensity.current
            MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(
                    LocalDensity provides Density(baseDensity.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().testTag(CONTAINER_TAG),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        val widthModifier = columnWidth?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth()
                        Column(
                            modifier = Modifier
                                .testTag(COLUMN_TAG)
                                .then(widthModifier)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            // 头像钉根 start、操作标记钉根 end：这两个点就是“根边界”的实测值
                            Row(
                                modifier = Modifier.fillMaxWidth().testTag(HEADER_TAG),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(MarkerSize).testTag(AVATAR_TAG))
                                Spacer(Modifier.weight(1f))
                                Box(Modifier.size(MarkerSize).testTag(ACTION_TAG))
                            }
                            CollapsibleMessageText(
                                messageId = MESSAGE_ID,
                                textParts = parts,
                                isAssistant = assistant,
                                streaming = streaming,
                                enabled = enabled,
                                fadeColor = Color.White,
                                modifier = Modifier.testTag(MESSAGE_TAG),
                            ) {
                                if (useRealText) {
                                    SelectableContent {
                                        Text(text = parts.first(), modifier = Modifier.testTag(bodyTag(0)))
                                    }
                                } else {
                                    parts.forEachIndexed { index, raw ->
                                        SelectableContent { Bubble(index, raw.take(24)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** 自然宽（`widthIn` 只封顶不填充）+ 固定高度的假气泡，padding 只在 start 侧。 */
    @Composable
    private fun Bubble(index: Int, label: String) {
        Box(
            modifier = Modifier
                .testTag(bubbleTag(index))
                .widthIn(max = BubbleMaxWidth)
                .height(bodyHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = if (assistant) 0.dp else BubblePadStart),
        ) {
            Box(Modifier.testTag(bodyTag(index)).fillMaxHeight()) {
                Text(text = label, maxLines = 1)
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    private fun boundsOf(tag: String): DpRect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun leftOf(tag: String): Float = boundsOf(tag).left.value
    private fun rightOf(tag: String): Float = boundsOf(tag).right.value
    private fun widthOf(tag: String): Float = rightOf(tag) - leftOf(tag)
    private fun heightOf(tag: String): Float = boundsOf(tag).bottom.value - boundsOf(tag).top.value

    // 根的 start/end 物理坐标由标记实测，方向感知，不写死“右边是 end”
    private fun rootStart(): Float = if (rtl) rightOf(AVATAR_TAG) else leftOf(AVATAR_TAG)
    private fun rootEnd(): Float = if (rtl) leftOf(ACTION_TAG) else rightOf(ACTION_TAG)
    private fun startEdge(bounds: DpRect): Float = if (rtl) bounds.right.value else bounds.left.value
    private fun endEdge(bounds: DpRect): Float = if (rtl) bounds.left.value else bounds.right.value
    private fun startEdgeOf(tag: String): Float = startEdge(boundsOf(tag))
    private fun endEdgeOf(tag: String): Float = endEdge(boundsOf(tag))
    private fun close(a: Float, b: Float, tolerance: Float = 1f): Boolean = abs(a - b) <= tolerance

    private fun assertAtEnd(tag: String, what: String) {
        assertTrue(
            "$what 的结束边 ${endEdgeOf(tag)} 应贴合根结束边 ${rootEnd()}（用户消息走 End，rtl=$rtl）",
            close(endEdgeOf(tag), rootEnd()),
        )
    }

    private fun assertAtStart(tag: String, what: String) {
        assertTrue(
            "$what 的起始边 ${startEdgeOf(tag)} 应贴合根起始边 ${rootStart()}（助手消息走 Start，rtl=$rtl）",
            close(startEdgeOf(tag), rootStart()),
        )
    }

    private fun expandLabel() = context.getString(R.string.message_collapse_r8_expand)

    private fun collapseLabel() = context.getString(R.string.message_collapse_r8_collapse)

    private fun assertNoToggle() {
        rule.onAllNodesWithText(expandLabel()).assertCountEquals(0)
        rule.onAllNodesWithText(collapseLabel()).assertCountEquals(0)
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun shortUserBubbleSitsAtEndEdgeWithoutToggle() {
        assistant = false
        parts = listOf(SHORT_USER)
        show()

        assertNoToggle()
        val root = widthOf(HEADER_TAG)
        val body = widthOf(bodyTag(0))
        assertTrue("两字符正文自然宽 $body 应远小于根宽 $root 的一半", body < root / 2f)
        // 修前失败点：内层 Column 默认 Start 时这块会贴根左端，右端对不上
        assertAtEnd(bodyTag(0), "短用户正文")
        assertTrue(
            "短用户正文右端 ${rightOf(bodyTag(0))} 必须等于根右端 ${rightOf(ACTION_TAG)}",
            close(rightOf(bodyTag(0)), rightOf(ACTION_TAG)),
        )
        // 带 padding 的气泡外沿也在同一条 end 边（校验断言取的是内容边而非 padding 外边）
        assertAtEnd(bubbleTag(0), "短用户气泡")
    }

    @Test
    fun shortAssistantBubbleSitsAtStartEdge() {
        assistant = true
        parts = listOf(SHORT_USER)
        show()

        assertNoToggle()
        assertAtStart(bodyTag(0), "短助手正文")
        assertTrue(
            "助手正文 ${startEdgeOf(bodyTag(0))} 不该跑到根起始边 ${rootStart()} 之外",
            startEdgeOf(bodyTag(0)) < rootEnd() - 50f,
        )
    }

    @Test
    fun twoUserSegmentsOfDifferentWidthBothAlignToEnd() {
        assistant = false
        parts = listOf(SHORT_USER, WIDER_USER)
        show()

        val narrow = widthOf(bodyTag(0))
        val wide = widthOf(bodyTag(1))
        assertTrue("两段宽度应当不同：$narrow vs $wide", wide > narrow + 10f)
        assertAtEnd(bodyTag(0), "窄的用户片段")
        assertAtEnd(bodyTag(1), "宽的用户片段")
        assertTrue(
            "两段结束边应一致，实际 ${endEdgeOf(bodyTag(0))} / ${endEdgeOf(bodyTag(1))}",
            close(endEdgeOf(bodyTag(0)), endEdgeOf(bodyTag(1))),
        )
    }

    @Test
    fun longUserBubbleKeepsEndEdgeThroughCollapseExpandRecollapse() {
        assistant = false
        parts = listOf(LONG_BODY)
        bodyHeight = 480.dp
        assertTrue("正文不够长，折叠不会触发", LONG_BODY.count { !it.isWhitespace() } >= MIN_EXPECTED_CHARS)
        show()

        rule.onNodeWithText(expandLabel()).performScrollTo().assertIsDisplayed()
        assertAtEnd(bodyTag(0), "折叠态用户正文")
        val collapsedEdge = endEdgeOf(bodyTag(0))
        val collapsedHeight = heightOf(MESSAGE_TAG)

        rule.onNodeWithText(expandLabel()).performScrollTo().performClick()
        rule.waitForIdle()
        assertAtEnd(bodyTag(0), "展开态用户正文")
        val expandedHeight = heightOf(MESSAGE_TAG)
        assertTrue("展开后高度 $expandedHeight 应明显大于折叠态 $collapsedHeight", expandedHeight > collapsedHeight + 100f)
        val expandedEdge = endEdgeOf(bodyTag(0))

        rule.onNodeWithText(collapseLabel()).performScrollTo().performClick()
        rule.waitForIdle()
        assertAtEnd(bodyTag(0), "再折叠的用户正文")
        val recollapsedEdge = endEdgeOf(bodyTag(0))
        assertTrue(
            "折叠/展开/再折叠结束边 $collapsedEdge/$expandedEdge/$recollapsedEdge 应一致",
            close(collapsedEdge, expandedEdge) && close(expandedEdge, recollapsedEdge),
        )
        assertTrue("再折叠后应回到预览高度", heightOf(MESSAGE_TAG) < expandedHeight - 100f)

        // 按钮跟着气泡靠右：再折叠后按钮文字是“展开全文”，离根结束边只剩按钮自身的 14dp 内边距
        val toggle = rule.onNodeWithText(expandLabel()).getUnclippedBoundsInRoot()
        val gap = rootEnd() - endEdge(toggle)
        assertTrue("展开/收起按钮离根结束边的间距 $gap 应落在 0..20dp", gap >= 0f && gap <= 20f)
        assertTrue("按钮不该留在起始侧", endEdge(toggle) > rootStart() + 100f)
    }

    @Test
    fun collapseDisabledStillKeepsUserBubbleAtEnd() {
        assistant = false
        enabled = false
        parts = listOf(LONG_BODY)
        bodyHeight = 480.dp
        show()

        assertNoToggle()
        assertAtEnd(bodyTag(0), "关掉折叠后的用户正文")
        assertTrue("关掉折叠不该裁正文，实际高 ${heightOf(MESSAGE_TAG)}", heightOf(MESSAGE_TAG) >= 470f)
    }

    @Test
    fun streamingUserBubbleStaysAtEndWhenGenerationFinishes() {
        assistant = false
        streaming = true
        parts = listOf(LONG_BODY)
        bodyHeight = 480.dp
        show()

        assertNoToggle()
        assertAtEnd(bodyTag(0), "流式生成中的用户正文")
        val streamingEdge = endEdgeOf(bodyTag(0))
        val streamingHeight = heightOf(MESSAGE_TAG)

        rule.runOnIdle { streaming = false }
        rule.waitForIdle()
        rule.onNodeWithText(expandLabel()).performScrollTo().assertIsDisplayed()
        assertTrue(
            "生成结束前后结束边 $streamingEdge → ${endEdgeOf(bodyTag(0))} 不该移动",
            close(streamingEdge, endEdgeOf(bodyTag(0))),
        )
        assertAtEnd(bodyTag(0), "结束后的用户正文")
        assertTrue("结束后应折起来并变矮", heightOf(MESSAGE_TAG) < streamingHeight - 50f)
    }

    @Test
    fun narrowColumnAndLargeFontScaleKeepRealUserTextAtEnd() {
        assistant = false
        columnWidth = 280.dp
        fontScale = 1.3f
        useRealText = true
        parts = listOf(SHORT_USER)
        show()

        val column = widthOf(HEADER_TAG)
        assertTrue("窄屏列宽 $column 应约等于 280dp", close(column, 280f, 2f))
        assertAtEnd(bodyTag(0), "280dp/1.3 倍字号下的真实 Text")
        assertTrue("正文自然宽 ${widthOf(bodyTag(0))} 应小于列宽一半", widthOf(bodyTag(0)) < column / 2f)
        val scaledHeight = heightOf(bodyTag(0))

        rule.runOnIdle { fontScale = 1f }
        rule.waitForIdle()
        val baseHeight = heightOf(bodyTag(0))
        assertTrue("1.3 倍字号行高 $scaledHeight 应大于 1.0 倍 $baseHeight", scaledHeight > baseHeight + 2f)
        assertAtEnd(bodyTag(0), "恢复 1.0 倍字号后的真实 Text")
    }

    @Test
    fun rtlPlacesUserAtEndAndAssistantAtStart() {
        rtl = true
        assistant = false
        parts = listOf(SHORT_USER)
        show()

        // RTL 下 End 是物理左侧：这里不硬编码“右”
        assertTrue("RTL 的根结束边应位于物理左侧", rootEnd() < rootStart())
        assertAtEnd(bodyTag(0), "RTL 用户正文")
        assertTrue("RTL 用户正文左端 ${leftOf(bodyTag(0))} 应贴合根左端 ${rootEnd()}", close(leftOf(bodyTag(0)), rootEnd()))

        rule.runOnIdle { assistant = true }
        rule.waitForIdle()
        assertAtStart(bodyTag(0), "RTL 助手正文")
        assertTrue(
            "RTL 助手正文右端 ${rightOf(bodyTag(0))} 应贴合根右端 ${rootStart()}",
            close(rightOf(bodyTag(0)), rootStart()),
        )
    }

    private companion object {
        const val CONTAINER_TAG = "r9-align-container"
        const val COLUMN_TAG = "r9-align-column"
        const val HEADER_TAG = "r9-align-header"
        const val AVATAR_TAG = "r9-align-avatar"
        const val ACTION_TAG = "r9-align-action"
        const val MESSAGE_TAG = "r9-align-message"
        const val MESSAGE_ID = "message-align-a"

        val MarkerSize = 24.dp
        val BubbleMaxWidth = 100.dp
        val BubblePadStart = 24.dp

        /** 两个字符的用户气泡：自然宽远小于根宽一半 */
        const val SHORT_USER = "ok"
        const val WIDER_USER = "a longer user segment"

        fun bubbleTag(index: Int) = "r9-align-bubble-$index"

        fun bodyTag(index: Int) = "r9-align-body-$index"

        /** 10 段 × 71 个非空白字符 = 710，稳过生产阈值 700；同时也满足多行多段规则。 */
        const val SEGMENT = "Block lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet\n\n"

        /** 折叠判定吃真实 textParts，这里给一段确定够长的正文。 */
        val LONG_BODY = SEGMENT.repeat(10)

        const val MIN_EXPECTED_CHARS = 700
    }
}
