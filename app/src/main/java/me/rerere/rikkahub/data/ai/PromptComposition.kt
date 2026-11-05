package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Counts only. No prompts, page contents, tool arguments or API credentials are retained. */
data class PromptSection(val source: String, val characters: Int)
data class PromptComposition(val assistantId: String, val model: String, val sections: List<PromptSection>, val recordedAt: Long)
object PromptCompositionStore {
    private val current = MutableStateFlow<PromptComposition?>(null)
    val latest = current.asStateFlow()
    fun recordTransformedSystem(assistantId: String, characters: Int) {
        val snapshot = current.value?.takeIf { it.assistantId == assistantId } ?: return
        current.value = snapshot.copy(sections = snapshot.sections + PromptSection("转换后系统提示（含自动注入，总量）", characters))
    }
    fun record(assistantId: String, model: String, sections: List<PromptSection>) {
        current.value = PromptComposition(assistantId, model, sections, System.currentTimeMillis())
    }
}
