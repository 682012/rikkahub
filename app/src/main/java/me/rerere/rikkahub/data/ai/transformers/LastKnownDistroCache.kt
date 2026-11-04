package me.rerere.rikkahub.data.ai.transformers

/** A transient failed probe must not erase a previously read distro from the prompt prefix. */
internal class LastKnownDistroCache(private val maxEntries: Int = 64) {
    private val entries = object : LinkedHashMap<String, String>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > maxEntries
    }
    @Synchronized fun cached(environmentKey: String): String? = entries[environmentKey]

    @Synchronized fun resolve(environmentKey: String, observed: String?): String? {
        if (!observed.isNullOrBlank()) entries[environmentKey] = observed
        return entries[environmentKey]
    }
}
