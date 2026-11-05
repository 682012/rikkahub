package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole

/** Only the empty inline image created while waiting for stream bytes; never probes external URLs. */
internal fun UIMessagePart.Image.isEmptyStreamImage(): Boolean {
    if (!url.startsWith("data:image/", ignoreCase = true)) return false
    val separator = url.indexOf(',')
    return separator >= 0 && url.substring(separator + 1).isBlank()
}

/** Terminal cleanup also applies to older assistant placeholders; user attachments remain untouched. */
fun UIMessage.withoutEmptyGeneratedImages(): UIMessage {
    if (role != MessageRole.ASSISTANT) return this
    val retained = parts.filterNot { it is UIMessagePart.Image && it.isEmptyStreamImage() }
    return if (retained.size == parts.size) this else copy(parts = retained)
}
