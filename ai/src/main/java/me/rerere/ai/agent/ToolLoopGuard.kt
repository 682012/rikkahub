package me.rerere.ai.agent

import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

/** Detect unchanged repeated reads; never cache tool results or suppress an actual fresh read. */
internal class ToolLoopGuard {
    private var previousBatch: String? = null
    private var unchangedBatches = 0

    fun observe(calls: List<UIMessagePart.Tool>, mode: (String) -> ToolExecutionMode?): UIMessagePart.Notice? {
        if (calls.isEmpty() || calls.any { mode(it.toolName) != ToolExecutionMode.PARALLEL_DEDUPLICATED }) {
            reset()
            return null
        }
        // Non-text outputs can change without a text signal; be conservative around images and signatures.
        if (calls.any { call -> call.output.any { it !is UIMessagePart.Text } }) {
            reset()
            return null
        }
        val signatures = ArrayList<String>(calls.size)
        for (call in calls) {
            val args = canonicalToolArguments(call.input)
            if (args == null) { reset(); return null }
            signatures += "${call.toolName}:$args:${digest(call.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })}"
        }
        val signature = signatures.joinToString("\n")
        if (previousBatch == signature) unchangedBatches++ else unchangedBatches = 1
        previousBatch = signature
        if (unchangedBatches != 3 && unchangedBatches % 8 != 0) return null
        return UIMessagePart.Notice(
            title = "Repeated unchanged reads",
            text = "The last $unchangedBatches tool batches repeated the same read arguments and returned unchanged results. " +
                "Each read really executed; no stale result was substituted. Use the evidence already available, " +
                "change the query or investigate a different hypothesis rather than repeating the identical batch. " +
                "If verifying an external change is necessary, fresh reads remain available.",
        )
    }

    private fun reset() { previousBatch = null; unchangedBatches = 0 }
    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
