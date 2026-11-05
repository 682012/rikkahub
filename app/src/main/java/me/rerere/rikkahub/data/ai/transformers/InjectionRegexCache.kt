package me.rerere.rikkahub.data.ai.transformers

/** Only compiled patterns are cached; never cache a user's message or an assistant's match result. */
internal object InjectionRegexCache {
    private data class Key(val pattern: String, val caseSensitive: Boolean)
    private val patterns = LinkedHashMap<Key, Regex?>(16, 0.75f, true)
    fun matches(pattern: String, caseSensitive: Boolean, text: String): Boolean {
        val key = Key(pattern, caseSensitive)
        val regex = synchronized(patterns) {
            if (patterns.containsKey(key)) patterns[key] else {
                val compiled = runCatching {
                    Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
                }.getOrNull()
                patterns[key] = compiled
                if (patterns.size > 128) patterns.entries.iterator().let { it.next(); it.remove() }
                compiled
            }
        }
        return regex?.containsMatchIn(text) == true
    }
}
