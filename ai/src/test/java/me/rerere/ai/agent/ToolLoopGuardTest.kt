package me.rerere.ai.agent

import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ToolLoopGuardTest {
    private fun call(id: String = "one", input: String = "{}", output: String = "same") =
        UIMessagePart.Tool(id, "read", input, output = listOf(UIMessagePart.Text(output)))
    @Test fun `warns on three unchanged reads and ignores call id differences`() {
        val guard = ToolLoopGuard()
        assertNull(guard.observe(listOf(call("a"))) { ToolExecutionMode.PARALLEL_DEDUPLICATED })
        assertNull(guard.observe(listOf(call("b"))) { ToolExecutionMode.PARALLEL_DEDUPLICATED })
        assertNotNull(guard.observe(listOf(call("c"))) { ToolExecutionMode.PARALLEL_DEDUPLICATED })
    }
    @Test fun `changed output args or write barrier resets the repetition streak`() {
        val guard = ToolLoopGuard()
        repeat(2) { guard.observe(listOf(call())) { ToolExecutionMode.PARALLEL_DEDUPLICATED } }
        assertNull(guard.observe(listOf(call(output="changed"))) { ToolExecutionMode.PARALLEL_DEDUPLICATED })
        assertNull(guard.observe(listOf(call(input="{\"new\":true}"))) { ToolExecutionMode.PARALLEL_DEDUPLICATED })
        assertNull(guard.observe(listOf(call())) { ToolExecutionMode.SERIAL })
        assertNull(guard.observe(listOf(call())) { ToolExecutionMode.PARALLEL_DEDUPLICATED })
    }
    @Test fun `polls and nontext outputs cannot be misclassified as unchanged reads`() {
        val guard = ToolLoopGuard()
        repeat(12) { assertNull(guard.observe(listOf(call())) { ToolExecutionMode.PARALLEL }) }
        val image = call().copy(output = listOf(UIMessagePart.Image("file:///same-path.png")))
        repeat(12) { assertNull(guard.observe(listOf(image)) { ToolExecutionMode.PARALLEL_DEDUPLICATED }) }
    }
    @Test fun `object key ordering does not evade a repeated batch warning`() {
        val guard=ToolLoopGuard()
        assertNull(guard.observe(listOf(call(input="{\"a\":1,\"b\":2}"))) { ToolExecutionMode.PARALLEL_DEDUPLICATED })
        assertNull(guard.observe(listOf(call(input="{\"b\":2,\"a\":1}"))) { ToolExecutionMode.PARALLEL_DEDUPLICATED })
        assertNotNull(guard.observe(listOf(call(input="{\"a\":1,\"b\":2}"))) { ToolExecutionMode.PARALLEL_DEDUPLICATED })
    }
}
