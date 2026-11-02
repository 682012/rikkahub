package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.*
import org.junit.Test

class LastKnownDistroCacheTest {
    @Test fun transientFailureRetainsTextButARealChangeUpdatesIt() {
        val cache = LastKnownDistroCache()
        assertEquals("Ubuntu", cache.resolve("local|1|bash", "Ubuntu"))
        assertEquals("Ubuntu", cache.resolve("local|1|bash", null))
        assertEquals("Debian", cache.resolve("local|1|bash", "Debian"))
    }
    @Test fun separateEnvironmentsNeverShareUnverifiedValues() {
        val cache = LastKnownDistroCache()
        cache.resolve("local|1|bash", "Ubuntu")
        assertNull(cache.resolve("http|1|bash", null))
        assertNull(cache.resolve("local|2|bash", null))
    }
    @Test fun cacheRemainsBounded() {
        val cache = LastKnownDistroCache(2)
        cache.resolve("1", "one");cache.resolve("2", "two");cache.resolve("3", "three")
        assertNull(cache.resolve("1", null))
    }
}
