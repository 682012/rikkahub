package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.lastContextTokens
import java.util.Locale

@Composable
internal fun ChatStudioStatus(conversation: Conversation, settings: Settings, onClick: () -> Unit) {
    val last = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val used = conversation.lastContextTokens()
    val capacity = settings.findModelById(last?.modelId)?.contextLength?.takeIf { it > 0 }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp).testTag("chat-studio-status"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上次上下文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        used == null -> "待报告"
                        capacity != null -> "${compact(used)} / ${compact(capacity)}"
                        else -> compact(used)
                    },
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                )
                Text("本对话 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (used != null && capacity != null) {
                LinearProgressIndicator(
                    progress = { (used.toFloat() / capacity).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = if (used.toLong() * 100 >= capacity.toLong() * 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun compact(n: Int): String = if (n >= 1000) String.format(Locale.US, "%.1fK", n / 1000.0) else n.toString()
