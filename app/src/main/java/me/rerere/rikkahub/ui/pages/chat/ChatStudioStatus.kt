package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatUiStyle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.lastContextTokens
import me.rerere.rikkahub.ui.theme.chatPanel
import java.util.Locale

/**
 * 上下文状态条：同一份数据，三种风格三种呈现。
 *
 * - STUDIO：浮岛卡片，带进度条，和标题栏同一条视觉线；
 * - CLASSIC：r5 的整条面板；
 * - FOCUS：单行极简，只在标题栏按钮进去看统计时才有机会出现。
 */
@Composable
internal fun ChatStudioStatus(
    conversation: Conversation,
    settings: Settings,
    style: ChatUiStyle = ChatUiStyle.STUDIO,
    onClick: () -> Unit,
) {
    val last = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val used = conversation.lastContextTokens()
    val capacity = settings.findModelById(last?.modelId)?.contextLength?.takeIf { it > 0 }
    val island = style == ChatUiStyle.STUDIO
    val compact = style == ChatUiStyle.FOCUS
    val horizontalPadding = if (island) 10.dp else 16.dp
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = if (island) 0.dp else 4.dp)
            .heightIn(min = 48.dp)
            .testTag("chat-studio-status"),
        shape = RoundedCornerShape(if (island) 18.dp else 16.dp),
        color = chatPanel(MaterialTheme.colorScheme.surfaceContainerLow),
        border = if (island) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_style_last_context),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when {
                        used == null -> stringResource(R.string.chat_style_context_pending)
                        capacity != null -> "${compact(used)} / ${compact(capacity)}"
                        else -> compact(used)
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.chat_style_this_conversation),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!compact && used != null && capacity != null) {
                LinearProgressIndicator(
                    progress = { (used.toFloat() / capacity).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = if (used.toLong() * 100 >= capacity.toLong() * 90) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

private fun compact(n: Int): String = if (n >= 1000) String.format(Locale.US, "%.1fK", n / 1000.0) else n.toString()
