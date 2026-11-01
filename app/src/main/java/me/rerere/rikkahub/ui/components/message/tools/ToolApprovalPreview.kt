package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 审批预览滚动容器的 testTag：界面测试用它定位可滚动的正文。 */
const val TOOL_APPROVAL_PREVIEW_TEST_TAG: String = "tool_approval_preview"

/**
 * 审批前展示待执行工具的真实入参。
 *
 * 悬浮窗的待审批卡和聊天里的待审批步骤必须给用户看**完整**的参数：批量工具（一次多个 write/shell）
 * 的参数是一个很长的 JSON，最后几个成员往往在第 300 个字符以后，截断 + Ellipsis 会让"批准"按钮
 * 指向一段用户根本没看到的载荷。所以这里不做 [androidx.compose.ui.text.style.TextOverflow.Ellipsis]、
 * 不设 maxLines，只把高度限制住，超出部分靠内部滚动看全。
 *
 * 悬浮窗里这段正文又嵌在外层的 verticalScroll 中，内层滚动优先处理落在自己身上的手势，
 * 与同文件的 [MessagePreview] 保持同一种做法。
 *
 * @param text 完整入参文本（已由 [effectiveArguments] 解出真实目标工具的参数）
 * @param modifier 外部修饰符
 * @param color 正文颜色，默认跟随 onSurfaceVariant
 * @param scrollState 内部滚动状态，测试可注入以断言"确实滚到底了"
 */
@Composable
fun ToolApprovalPreview(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    scrollState: ScrollState = rememberScrollState(),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .testTag(TOOL_APPROVAL_PREVIEW_TEST_TAG)
            .verticalScroll(scrollState),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}
