package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class MessageCollapseDefaultsR11Test {
    private val longText = listOf("x".repeat(900))

    @Test fun expandedDefaultStillOffersManualCollapse() {
        val result = decideMessageCollapse(MessageCollapseInput(textParts = longText, collapsedByDefault = false))
        assertFalse(result.collapsed)
        assertTrue(result.showToggle)
        assertEquals(MessageCollapseReason.DEFAULT_EXPANDED, result.reason)
    }

    @Test fun manualChoiceWinsOverEitherDefault() {
        assertTrue(decideMessageCollapse(MessageCollapseInput(
            textParts = longText, collapsedByDefault = false, manualCollapsed = true,
        )).collapsed)
        assertFalse(decideMessageCollapse(MessageCollapseInput(
            textParts = longText, collapsedByDefault = true, manualOpen = true,
        )).collapsed)
    }

    @Test fun expandedDefaultDoesNotCollapseOnStreamCompletion() {
        val streaming = decideMessageCollapse(MessageCollapseInput(
            textParts = longText, collapsedByDefault = false, streaming = true,
        ))
        assertFalse(streaming.collapsed)
        assertFalse(streaming.collapseWhenComplete)
        assertFalse(streaming.showToggle)
    }

    @Test fun shortMessagesAndAnchorNavigationStayUnchanged() {
        assertFalse(decideMessageCollapse(MessageCollapseInput(textParts = listOf("short"))).showToggle)
        assertFalse(decideMessageCollapse(MessageCollapseInput(
            textParts = longText, manualCollapsed = true, anchorRequested = true,
        )).collapsed)
    }

    @Test fun oldSettingsKeepTheirBehaviorAndNewPreferenceRoundTrips() {
        assertTrue(JsonInstant.decodeFromString<DisplaySetting>("{}").collapseLongMessagesByDefault)
        assertFalse(JsonInstant.decodeFromString<DisplaySetting>("{\"collapseLongMessages\":false}").collapseLongMessages)
        val expanded = DisplaySetting(collapseLongMessagesByDefault = false)
        assertEquals(expanded, JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(expanded)))
    }
}
