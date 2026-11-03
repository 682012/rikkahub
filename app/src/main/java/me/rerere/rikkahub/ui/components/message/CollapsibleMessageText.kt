package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R

/** 折叠后真实渲染的首屏高度（约一屏），不是按字数截断 */
private val CollapsedPreviewMaxHeight = 280.dp

/** 底部渐隐条高度，够提示"下面还有"又不遮正文 */
private val CollapseFadeHeight = 28.dp

/** 展开/收起按钮的触控尺寸 */
private val CollapseToggleMinTarget = 48.dp

/**
 * 会话级的折叠状态登记表。
 *
 * 按 messageId（节点 id 字符串）记住用户的**手动**选择，切换分支/滚动回收后仍然有效；
 * 另外给跳转锚点、消息查找留一个"这条请先展开"的入口 —— 否则滚过去只会看到被裁掉的一段。
 *
 * 放在单例里而不是 `remember`，是因为 LazyColumn 会把滑出屏幕的消息整条回收，
 * 本地 remember 的展开状态会跟着丢掉。这里只在内存里，不落盘。
 */
object MessageCollapseStateHolder {
    private var manualStates by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var forcedOpen by mutableStateOf<Set<String>>(emptySet())

    /** null = 用户没表态过，走默认策略 */
    fun manualState(messageId: String): Boolean? = manualStates[messageId]

    fun isForcedOpen(messageId: String): Boolean = messageId in forcedOpen

    fun setManual(messageId: String, expanded: Boolean) {
        if (messageId.isEmpty()) return
        manualStates = (manualStates + (messageId to expanded)).entries.toList().takeLast(600).associate { it.toPair() }
    }

    /** 跳转消息锚点 / 查找结果命中时调用，先展开再滚 */
    fun requestExpand(messageId: String) {
        if (messageId.isEmpty()) return
        forcedOpen = forcedOpen + messageId
    }

    fun requestExpand(nodeId: Any) = requestExpand(nodeId.toString())

    fun clearForcedOpen(messageId: String) {
        if (messageId in forcedOpen) forcedOpen = forcedOpen - messageId
    }

    fun forget(messageId: String) {
        manualStates = manualStates - messageId
        forcedOpen = forcedOpen - messageId
    }

    /** 切会话时可整体清掉 */
    fun reset() {
        manualStates = emptyMap()
        forcedOpen = emptySet()
    }
}

/**
 * 长消息正文折叠容器。
 *
 * 只裁**显示高度**，正文本身完整交给 [content]（Markdown 照旧整段渲染）：
 * - 不会把没写完的代码块、宽表格从字符串中间切开，`Copy`/选择依旧拿全文；
 * - 折叠态依然完整 measure（不是 lazy），长消息的排版开销照付，换来首屏高度可控；
 * - 展开/收起按钮在裁切区之外，48dp 触控，不压在正文/图片/工具审批上。
 */
@Composable
internal fun CollapsibleMessageText(
    messageId: String,
    textParts: List<String>,
    isAssistant: Boolean,
    streaming: Boolean,
    enabled: Boolean,
    fadeColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val manual = MessageCollapseStateHolder.manualState(messageId)
    val anchorRequested = MessageCollapseStateHolder.isForcedOpen(messageId)
    val decision = remember(messageId, textParts, isAssistant, streaming, enabled, manual, anchorRequested) {
        decideMessageCollapse(
            MessageCollapseInput(
                messageId = messageId,
                textParts = textParts,
                isAssistant = isAssistant,
                streaming = streaming,
                enabled = enabled,
                manualOpen = manual == true,
                manualCollapsed = manual == false,
                anchorRequested = anchorRequested,
            )
        )
    }
    val collapsed = decision.collapsed
    Column(
        modifier = modifier.fillMaxWidth(),
        // 用户消息气泡靠右，"展开全文"也跟着靠右
        horizontalAlignment = if (isAssistant) Alignment.Start else Alignment.End,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (collapsed) Modifier.clipToMaxHeight(CollapsedPreviewMaxHeight)
                    else Modifier
                )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                // Alignment is not inherited through the full-width clipping wrapper.
                // Keep each natural-width user bubble at End, including short/streaming text.
                horizontalAlignment = if (isAssistant) Alignment.Start else Alignment.End,
                // 和消息列 4dp 的间距保持一致（多段正文不再被合成一个紧贴的块）
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
            if (collapsed) {
                // 只渐变到气泡底色，不拦截点击（没有 pointerInput），选择/长按照常
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(CollapseFadeHeight)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, fadeColor),
                            )
                        )
                )
            }
        }
        if (decision.showToggle) {
            CollapseToggleButton(
                expanded = !collapsed,
                // 点一下就是翻转：折叠着 → 记住"展开"；展开着 → 记住"收起"
                onClick = {
                    MessageCollapseStateHolder.clearForcedOpen(messageId)
                    MessageCollapseStateHolder.setManual(messageId, expanded = collapsed)
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CollapseToggleButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = remember { RoundedCornerShape(14.dp) }
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .heightIn(min = CollapseToggleMinTarget)
            .widthIn(min = CollapseToggleMinTarget)
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = stringResource(
                if (expanded) R.string.message_collapse_r8_collapse
                else R.string.message_collapse_r8_expand
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * 只裁可见高度，测量仍然走全文。
 *
 * 子内容按"最大高度无上限"测量：Markdown 正常排版，宽表格/代码块不会被压成半行，
 * 也不会因为它内部还有滚动容器而产生测量抖动。然后布局高度取 [maxHeight] 的上限，
 * 再配合 [clipToBounds] 让超出部分不绘制、也收不到触摸。
 *
 * 代价（实话实说）：这不是 lazy、也不是懒加载 —— 折叠态正文依旧完整组合和测量，
 * 只是绘制被裁掉。好处是首屏高度稳定、流式时不会因为不断换高度而抖。
 */
private fun Modifier.clipToMaxHeight(maxHeight: Dp): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val maxPx = maxHeight.roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        )
        val width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = placeable.height.coerceAtMost(maxPx).coerceAtLeast(0)
        layout(width, height) {
            placeable.placeRelative(0, 0)
        }
    }
