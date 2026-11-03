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

internal fun conversationSpeed(conversation: Conversation): Double? =
    conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.generationTiming?.speedTokensPerSecond

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
            Text(if(speed==null) "—" else String.format(Locale.US,"%.1f tok/s",speed),
                style=MaterialTheme.typography.labelLarge,modifier=Modifier.testTag("chat-speed-value")
                    .semantics { if(speed==null) contentDescription=unavailable })
        }
    }
}
