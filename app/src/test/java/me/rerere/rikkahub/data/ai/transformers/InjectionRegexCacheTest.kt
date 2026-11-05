package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.*
import org.junit.Test

class InjectionRegexCacheTest {
    @Test fun caseAndInvalidPatternsAreIsolated() {
        assertTrue(InjectionRegexCache.matches("hello", false, "HELLO"))
        assertFalse(InjectionRegexCache.matches("hello", true, "HELLO"))
        assertFalse(InjectionRegexCache.matches("[", false, "anything"))
    }
    @Test fun cachedRegexNeverCachesMessageMatch() {
        assertTrue(InjectionRegexCache.matches("apple", true, "apple"))
        assertFalse(InjectionRegexCache.matches("apple", true, "orange"))
        repeat(150) { InjectionRegexCache.matches("p$it", true, "no") }
        assertTrue(InjectionRegexCache.matches("apple", true, "apple"))
    }
}
