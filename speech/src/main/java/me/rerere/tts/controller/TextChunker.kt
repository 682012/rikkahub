package me.rerere.tts.controller

/** Sentence-aware, bounded chunks. Keep word separators and surrogate pairs intact. */
class TextChunker(
    private val maxChunkLength: Int = 150,
    private val firstChunkLength: Int = maxChunkLength,
) {
    fun split(text: String): List<TtsChunk> {
        if (text.isBlank()) return emptyList()
        val chunks = mutableListOf<TtsChunk>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start == text.length) break
            val limit = (if (chunks.isEmpty()) firstChunkLength else maxChunkLength).coerceAtLeast(2)
            var end = (start + limit).coerceAtMost(text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            if (end < text.length) {
                val minimum = start + (limit / 3).coerceAtLeast(1)
                val sentenceEnd = (end - 1 downTo minimum).firstOrNull { text[it] in "。！？!?；;\n" }
                val wordEnd = (end - 1 downTo minimum).firstOrNull { text[it].isWhitespace() || text[it] in "，,、：:" }
                end = (sentenceEnd ?: wordEnd)?.plus(1) ?: end
            }
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) chunks += TtsChunk(index = chunks.size, text = part)
            start = end
        }
        return chunks
    }
}

data class TtsChunk(
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val index: Int,
    val text: String,
)
