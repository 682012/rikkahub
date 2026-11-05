package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.ai.PromptCompositionStore
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun PromptCompositionCard() {
    val composition by PromptCompositionStore.latest.collectAsStateWithLifecycle()
    CardGroup(title = { Text("最近请求的提示词组成") }) {
        val current = composition
        if (current == null) item(headlineContent = { Text("发送一条消息后显示") })
        else {
            item(headlineContent = { Text(current.model) }, supportingContent = {
                Text("按 UTF-16 字符计数，不等于 token；这里只保留计数，不保存提示词正文。")
            })
            current.sections.forEach { section -> item(
                headlineContent = { Text(section.source) },
                trailingContent = { Text("${section.characters} 字符") },
            ) }
        }
    }
}
