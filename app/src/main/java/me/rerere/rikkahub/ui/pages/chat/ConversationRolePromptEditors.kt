package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.inputassist.AssistRolePrompts
import me.rerere.rikkahub.data.model.ConversationRuntimeOptions

@Composable
fun ConversationRolePromptEditors(options: ConversationRuntimeOptions, enabled: Boolean,
    onChange: (ConversationRuntimeOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("辅助模型的角色提示词", style = MaterialTheme.typography.titleSmall)
        RoleEditor("原话优化角色", options.optimizerSystemPrompt, enabled) {
            onChange(options.copy(optimizerSystemPrompt = AssistRolePrompts.normalize(it)))
        }
        RoleEditor("回复审查角色", options.reviewerSystemPrompt, enabled) {
            onChange(options.copy(reviewerSystemPrompt = AssistRolePrompts.normalize(it)))
        }
        Text("各自按会话保存；清空并保存恢复默认。角色不改变原任务边界与输出格式。",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RoleEditor(label: String, value: String, enabled: Boolean, onSave: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    Column {
        OutlinedTextField(value = draft,
            onValueChange = { draft = it.take(AssistRolePrompts.MAX_LENGTH) },
            modifier = Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(label) }, minLines = 3, maxLines = 8,
            placeholder = { Text("例如：你是一位细致的技术编辑……") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = enabled && draft.trim() != value,
                onClick = { onSave(draft) }) { Text("保存") }
            TextButton(enabled = enabled && (draft.isNotEmpty() || value.isNotEmpty()),
                onClick = { draft = ""; onSave("") }) { Text("恢复默认") }
        }
    }
}
