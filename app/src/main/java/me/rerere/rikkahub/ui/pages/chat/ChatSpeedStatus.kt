package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.theme.chatPanelStrong
import java.util.Locale

/** 折叠摘要行里的速度文本标记；详细面板仍用 [ChatSpeedValueTag]，两者不会撞 tag。 */
internal const val ChatHeaderSpeedTag = "chat-header-speed"

/** 详细面板（展开态）里的速度数值标记。 */
internal const val ChatSpeedValueTag = "chat-speed-value"

/** 没有可测量速度时的占位符，摘要行与详细面板共用，避免两处写法漂移。 */
internal const val ChatSpeedPlaceholder = "—"

internal fun conversationSpeed(conversation: Conversation): Double? =
    conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.generationTiming?.speedTokensPerSecond

/**
 * 速度文本的唯一来源：摘要行和详细面板都走这里，避免出现 “52.0 tok/s” 与 “52.0 t/s” 两种写法。
 * null 表示还没有可信的流式计时，显示占位符而不是猜一个数。
 */
internal fun conversationSpeedText(speed: Double?): String =
    if (speed == null) ChatSpeedPlaceholder else String.format(Locale.US, "%.1f tok/s", speed)

/** Visible in every layout, independent from the context dashboard switch. */
@Composable
internal fun ChatSpeedStatus(conversation: Conversation) {
    val speed=conversationSpeed(conversation)
    val unavailable=stringResource(R.string.chat_speed_unavailable)
    Surface(color=chatPanelStrong(MaterialTheme.colorScheme.surfaceContainerLow),contentColor=MaterialTheme.colorScheme.onSurface,
        shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.SpaceBetween) {
            Text(stringResource(R.string.chat_speed_label),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(conversationSpeedText(speed),
                style=MaterialTheme.typography.labelLarge,modifier=Modifier.testTag(ChatSpeedValueTag)
                    .semantics { if(speed==null) contentDescription=unavailable })
        }
    }
}
