package me.rerere.rikkahub.data.ai

import java.io.IOException
import me.rerere.ai.util.HttpException
import org.junit.Assert.*
import org.junit.Test

class CompressionToolChoiceTest {
    @Test fun fallbackIsLimitedToExplicitToolChoiceCompatibilityFailures() {
        assertTrue(compressionToolChoiceUnsupported(HttpException("tool_choice is not supported")))
        assertTrue(compressionToolChoiceUnsupported(HttpException("invalid parameter: tool_choice")))
        assertFalse(compressionToolChoiceUnsupported(IOException("tool_choice unsupported")))
        assertFalse(compressionToolChoiceUnsupported(HttpException("invalid model")))
    }
    @Test fun credentialsLimitsAndPermissionErrorsNeverTriggerAChangedRequest() {
        for (message in listOf("invalid api key for tool_choice", "unauthorized tool_choice invalid", "permission tool_choice unsupported", "rate limit tool_choice invalid")) {
            assertFalse(compressionToolChoiceUnsupported(HttpException(message)))
        }
    }
}
