package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长消息正文折叠的判定测试。只测纯函数，不碰 Compose。
 */
class MessageCollapsePolicyTest {

    private fun decide(
        texts: List<String>,
        streaming: Boolean = false,
        enabled: Boolean = true,
        manualOpen: Boolean = false,
        manualCollapsed: Boolean = false,
        anchorRequested: Boolean = false,
    ) = decideMessageCollapse(
        MessageCollapseInput(
            messageId = "msg-1",
            textParts = texts,
            streaming = streaming,
            enabled = enabled,
            manualOpen = manualOpen,
            manualCollapsed = manualCollapsed,
            anchorRequested = anchorRequested,
        )
    )

    private fun longText(chars: Int = 900): String =
        (1..chars).map { ('a' + it % 26) }.joinToString("")

    // ---------- 短消息完全不变 ----------

    @Test
    fun shortMessageIsUntouched() {
        val decision = decide(listOf("好的，这就开始。"))
        assertFalse(decision.collapsed)
        assertFalse(decision.showToggle)
        assertEquals(MessageCollapseReason.SHORT, decision.reason)
    }

    @Test
    fun emptyMessageIsNoText() {
        val decision = decide(listOf("", "   \n  "))
        assertEquals(MessageCollapseReason.NO_TEXT, decision.reason)
        assertFalse(decision.showToggle)
        assertTrue(decision.metrics.isEmpty)
    }

    @Test
    fun twelveShortLinesWithoutParagraphsDoNotCollapse() {
        // 12 行但只有一个段落、字数很少：换行碎片，不该折
        val text = (1..12).joinToString("\n") { "line $it" }
        val decision = decide(listOf(text))
        assertFalse(decision.collapsed)
        assertEquals(MessageCollapseReason.SHORT, decision.reason)
    }

    // ---------- 长消息默认折叠 ----------

    @Test
    fun longAssistantMessageCollapsesByDefault() {
        val decision = decide(listOf(longText(900)))
        assertTrue(decision.collapsed)
        assertTrue(decision.showToggle)
        assertEquals(MessageCollapseReason.AUTO_COLLAPSED, decision.reason)
        assertTrue(decision.metrics.charCount >= MessageCollapseThresholds.MIN_CHARS)
    }

    @Test
    fun longUserMessageAlsoCollapses() {
        val decision = decideMessageCollapse(
            MessageCollapseInput(
                messageId = "user-1",
                textParts = listOf(longText(800)),
                isAssistant = false,
            )
        )
        assertTrue(decision.collapsed)
        assertTrue(decision.showToggle)
    }

    @Test
    fun twelveLinesWithParagraphsCollapse() {
        val paragraph = (1..6).joinToString("\n") { "第 $it 行：这里是一段说明文字，用来凑够行数。" }
        val text = "$paragraph\n\n$paragraph\n\n尾巴"
        val decision = decide(listOf(text))
        assertTrue(decision.metrics.lineCount >= 12)
        assertTrue(decision.metrics.paragraphCount >= 2)
        assertTrue(decision.collapsed)
    }

    // ---------- 多 parts ----------

    @Test
    fun multiTextPartsAreJudgedTogether() {
        val parts = listOf(longText(300), longText(250), longText(300))
        val decision = decide(parts)
        assertEquals(3, decision.metrics.partCount)
        assertTrue(decision.collapsed)
    }

    @Test
    fun multipleShortPartsStayOpen() {
        val decision = decide(listOf("第一段很短的说明。", "第二段也很短。"))
        assertFalse(decision.collapsed)
        assertFalse(decision.showToggle)
    }

    // ---------- 代码块 / 表格 ----------

    @Test
    fun unclosedCodeFenceIsDetected() {
        val metrics = measureMessageText(listOf("看这里：\n```kotlin\nval a = 1\n"))
        assertTrue(metrics.hasUnclosedCodeFence)
    }

    @Test
    fun closedCodeFenceIsBalanced() {
        val metrics = measureMessageText(listOf("```kotlin\nval a = 1\n```\n完成"))
        assertFalse(metrics.hasUnclosedCodeFence)
    }

    @Test
    fun longCodeBlockCollapsesWithoutStringCut() {
        val code = "```kotlin\n" + (1..120).joinToString("\n") { "val v$it = compute($it)" } + "\n```"
        val decision = decide(listOf(code))
        assertTrue(decision.collapsed)
        // 只是显示裁切：字符统计仍是全文
        assertEquals(code.length, decision.metrics.charCount)
    }

    @Test
    fun wideTableIsDetected() {
        val table = "| A | B | C |\n| --- | --- | --- |\n| 1 | 2 | 3 |"
        assertTrue(measureMessageText(listOf(table)).hasTable)
    }

    @Test
    fun plainTextIsNotTable() {
        assertFalse(measureMessageText(listOf("a | b 只是文本")).hasTable)
    }

    // ---------- 流式 ----------

    @Test
    fun streamingNeverCollapses() {
        val decision = decide(listOf(longText(1200)), streaming = true)
        assertFalse(decision.collapsed)
        assertFalse(decision.showToggle)
        assertEquals(MessageCollapseReason.STREAMING, decision.reason)
        // 生成结束后应自动折起来
        assertTrue(decision.collapseWhenComplete)
    }

    @Test
    fun streamingShortTextHasNothingToCollapse() {
        val decision = decide(listOf("正在输"), streaming = true)
        assertFalse(decision.collapsed)
        assertFalse(decision.collapseWhenComplete)
    }

    @Test
    fun chunkGrowthDoesNotChangeDecisionWhileStreaming() {
        val a = decide(listOf(longText(800)), streaming = true)
        val b = decide(listOf(longText(1600)), streaming = true)
        assertEquals(a.collapsed, b.collapsed)
        assertEquals(a.showToggle, b.showToggle)
    }

    // ---------- 用户手动状态 ----------

    @Test
    fun manualExpandIsKeptAfterCompletion() {
        val decision = decide(listOf(longText(900)), manualOpen = true)
        assertFalse(decision.collapsed)
        assertTrue(decision.showToggle)
        assertEquals(MessageCollapseReason.MANUAL_OPEN, decision.reason)
    }

    @Test
    fun manualExpandWinsOverStreaming() {
        val decision = decide(listOf(longText(900)), streaming = true, manualOpen = true)
        assertFalse(decision.collapsed)
        assertEquals(MessageCollapseReason.MANUAL_OPEN, decision.reason)
    }

    @Test
    fun manualCollapseIsRemembered() {
        val decision = decide(listOf(longText(900)), manualCollapsed = true)
        assertTrue(decision.collapsed)
        assertTrue(decision.showToggle)
        assertEquals(MessageCollapseReason.MANUAL_COLLAPSED, decision.reason)
    }

    @Test
    fun manualExpandOnShortMessageShowsNoToggle() {
        val decision = decide(listOf("短消息"), manualOpen = true)
        assertFalse(decision.collapsed)
        assertFalse(decision.showToggle)
    }

    // ---------- 开关与锚点 ----------

    @Test
    fun disabledSettingRestoresOldBehavior() {
        val decision = decide(listOf(longText(2000)), enabled = false)
        assertFalse(decision.collapsed)
        assertFalse(decision.showToggle)
        assertEquals(MessageCollapseReason.DISABLED, decision.reason)
    }

    @Test
    fun anchorRequestExpandsLongMessage() {
        val decision = decide(listOf(longText(900)), anchorRequested = true)
        assertFalse(decision.collapsed)
        assertTrue(decision.showToggle)
        assertEquals(MessageCollapseReason.FORCED_OPEN, decision.reason)
    }

    // ---------- DisplaySetting 安全读取 ----------


    @Test
    fun settingDefaultsToEnabledWhenFieldMissing() {
        assertTrue(readCollapseLongMessagesSetting(null))
        assertTrue(readCollapseLongMessagesSetting(CollapseSettingWithoutField()))
    }

    @Test
    fun settingReadsRealValueWhenFieldExists() {
        assertFalse(readCollapseLongMessagesSetting(CollapseSettingWithField(false)))
        assertTrue(readCollapseLongMessagesSetting(CollapseSettingWithField(true)))
    }
}

/** 反射读取用的假 DisplaySetting：有字段 / 没字段两种 */
class CollapseSettingWithField(val collapseLongMessages: Boolean)

class CollapseSettingWithoutField(val somethingElse: Int = 0)
