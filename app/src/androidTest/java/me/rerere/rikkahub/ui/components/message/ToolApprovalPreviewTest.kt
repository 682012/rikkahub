package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.TOOL_APPROVAL_PREVIEW_TEST_TAG
import me.rerere.rikkahub.ui.components.message.tools.ToolApprovalPreview
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 审批预览的界面测试：批量工具的真实载荷必须完整可见。
 *
 * 回归的问题：悬浮窗待审批卡和聊天待审批步骤都把入参截成 `take(300)` + `maxLines = 3` + Ellipsis，
 * 一次批量调用里排在后面的 write/shell 完全看不到，用户点"批准"等于盲签。这里锁三件事：
 * 1. 语义树里能拿到**完整**正文（尾部成员没有被切掉）；
 * 2. 容器是真的可滚动，并且能滚到底（不是靠 `heightIn` 把后半截裁掉）；
 * 3. 320dp 窄屏下容器不超宽、滚到底后最后一段 write 仍然可见。
 *
 * 另外补一条集成用例：注册渲染器没有 Summary 的工具（外部 MCP/批量脚本走的就是默认渲染器）
 * 待审批时，聊天步骤也必须把入参展示出来，而不是只有标题栏上的批准按钮。
 *
 * 注意：没有 Android SDK 的机器上写不了这些用例的运行结果，本文件只按现有 production API 写好，
 * 由 CI 的主线程 AndroidTest 实际执行，这里不声称已经 passed。
 */
@RunWith(AndroidJUnit4::class)
class ToolApprovalPreviewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val approveLabel: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.chat_message_tool_approve)

    private val denyLabel: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.chat_message_tool_deny)

    // ---- 数据：一次包含多个成员的批量调用，最后一个成员排在 300 字符之后 ----

    /** 最后一个成员的路径片段：老实现 `take(300)` 之后它就消失了。 */
    private val lastWriteMarker = "LAST_WRITE_9f3c_final.md"

    private fun longBatchPayload(): String = buildString {
        appendLine("{")
        appendLine("  \"tool_calls\": [")
        for (i in 1..12) {
            appendLine("    {")
            appendLine("      \"tool_call_id\": \"call_$i\",")
            appendLine("      \"tool_name\": \"write_file\",")
            append("      \"arguments\": {\"file_path\": \"/workspace/notes/part_$i.md\", \"content\": \"")
            repeat(6) { append("第 $i 个成员的填充正文 $it。") }
            appendLine("\"}")
            appendLine("    },")
        }
        appendLine(
            "    { \"tool_call_id\": \"call_last\", \"tool_name\": \"write_file\", " +
                "\"arguments\": {\"file_path\": \"/workspace/notes/$lastWriteMarker\", \"content\": \"final\"} }"
        )
        appendLine("  ]")
        append("}")
    }

    // ---- 工具方法 ----

    /** 滚动状态由测试注入，读到的是 production 组件真正用的那一个。 */
    private fun scrollSnapshot(state: ScrollState): Pair<Int, Int> {
        var value = -1
        var max = -1
        composeRule.runOnIdle {
            value = state.value
            max = state.maxValue
        }
        return value to max
    }

    /**
     * 通过容器的 ScrollBy 语义滚到底。
     *
     * 这个动作内部是 launch 出去的协程，返回 true 时还没滚完，所以轮询到稳定再返回。
     */
    private fun scrollPreviewToBottom(state: ScrollState) {
        composeRule.onNodeWithTag(TOOL_APPROVAL_PREVIEW_TEST_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 20_000f) }
        composeRule.waitForIdle()
        repeat(40) {
            val (value, max) = scrollSnapshot(state)
            if (value == max) return
            Thread.sleep(50)
            composeRule.waitForIdle()
        }
    }

    // ---- 用例 ----

    @Test
    fun longBatchPayload_keepsWholeTextInSemantics_includingLastWriteBeyond300Chars() {
        val payload = longBatchPayload()
        // 先确认这条数据真的能复现问题：老实现的 300 字符之后才有最后一个 write
        assertTrue("载荷长度应该超过老的 300 字符截断点", payload.length > 300)
        assertFalse(
            "最后一个 write 必须落在老实现的截断点之后，否则这条用例测不到回归",
            payload.take(300).contains(lastWriteMarker),
        )

        composeRule.setContent {
            MaterialExpressiveTheme {
                ToolApprovalPreview(text = payload)
            }
        }
        composeRule.waitForIdle()

        // 语义树里是完整正文（没有 take(300)）
        composeRule.onNodeWithText(payload, substring = true).assertExists()
        // 尾部成员也在
        composeRule.onNodeWithText(lastWriteMarker, substring = true).assertExists()
    }

    @Test
    fun longBatchPayload_containerReallyScrollsToBottom() {
        val payload = longBatchPayload()
        val state = ScrollState(0)
        composeRule.setContent {
            MaterialExpressiveTheme {
                ToolApprovalPreview(text = payload, scrollState = state)
            }
        }
        composeRule.waitForIdle()

        val (startValue, startMax) = scrollSnapshot(state)
        assertEquals("初始位置应该在最上面", 0, startValue)
        assertTrue("正文必须真的超出容器高度，否则滚动断言没有意义", startMax > 0)

        // 高度被限制住（180dp 上限），不是靠裁掉后半截
        val containerHeightPx = composeRule.onNodeWithTag(TOOL_APPROVAL_PREVIEW_TEST_TAG)
            .fetchSemanticsNode().size.height
        val maxHeightPx = with(composeRule.density) { 180.dp.roundToPx() }
        assertTrue(
            "预览容器高度 $containerHeightPx px 应不超过 180dp($maxHeightPx px)",
            containerHeightPx <= maxHeightPx + 2,
        )

        scrollPreviewToBottom(state)

        val (endValue, endMax) = scrollSnapshot(state)
        assertEquals("滚到底后滚动位置应等于最大值", endMax, endValue)
        composeRule.onNodeWithText(lastWriteMarker, substring = true).assertIsDisplayed()
    }

    @Test
    fun longBatchPayload_isReadableAt320dpWidth() {
        val payload = longBatchPayload()
        val state = ScrollState(0)
        composeRule.setContent {
            MaterialExpressiveTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    ToolApprovalPreview(text = payload, scrollState = state)
                }
            }
        }
        composeRule.waitForIdle()

        // 窄屏下容器不横向溢出
        val containerWidthPx = composeRule.onNodeWithTag(TOOL_APPROVAL_PREVIEW_TEST_TAG)
            .fetchSemanticsNode().size.width
        val expectedWidthPx = with(composeRule.density) { 320.dp.roundToPx() }
        assertTrue(
            "320dp 宽下预览容器宽度 $containerWidthPx px 不应超过 $expectedWidthPx px",
            containerWidthPx <= expectedWidthPx + 2,
        )

        scrollPreviewToBottom(state)

        // 滚到底后最后一段 write 在 320dp 宽下依然可读（换行，不横向裁掉）
        val lastWrite = composeRule.onNodeWithText(lastWriteMarker, substring = true)
        lastWrite.assertIsDisplayed()
        val lastWriteWidthPx = lastWrite.fetchSemanticsNode().size.width
        assertTrue(
            "最后一段 write 的文本宽度 $lastWriteWidthPx px 不应超过容器宽度 $expectedWidthPx px",
            lastWriteWidthPx <= expectedWidthPx + 2,
        )
    }

    @Test
    fun pendingToolStepWithoutRendererSummary_stillShowsFullPendingArguments() {
        val payload = longBatchPayload()
        // 注册表里没有这个名字 -> 默认渲染器（hasSummary = false），正是出问题的分支
        val tool = UIMessagePart.Tool(
            toolCallId = "call_batch",
            toolName = "batch_write_unknown_tool",
            input = payload,
            approvalState = ToolApprovalState.Pending,
        )

        composeRule.setContent {
            MaterialExpressiveTheme {
                ChainOfThought(steps = listOf(tool)) { step ->
                    ChatMessageToolStep(
                        tool = step,
                        loading = false,
                        onToolApproval = { _, _, _ -> },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 标题栏上有批准/拒绝，下面就必须有对应载荷（步骤待审批时强制展开）
        composeRule.onNodeWithContentDescription(approveLabel).assertExists()
        composeRule.onNodeWithContentDescription(denyLabel).assertExists()
        composeRule.onNodeWithText(lastWriteMarker, substring = true).assertExists()
    }
}
