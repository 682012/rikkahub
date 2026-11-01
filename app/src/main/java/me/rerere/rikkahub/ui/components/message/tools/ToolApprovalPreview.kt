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
 * 审批前完整展示真实参数；长批次不截断，固定视窗内滚动查看。
 * [scrollState] 可由界面测试注入，验证尾部参数可以滚动到达。
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
