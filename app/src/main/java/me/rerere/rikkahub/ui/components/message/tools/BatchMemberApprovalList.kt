package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart

/** Only the members that actually need user approval are shown here. */
@Composable
fun BatchMemberApprovalList(tool: UIMessagePart.Tool, onApproveMember: (Int) -> Unit) {
    if (tool.toolName != "tool_batch") return
    val calls = (tool.inputAsJson() as? JsonObject)?.get("calls") as? JsonArray ?: return
    val required = tool.approvalRequiredMembers ?: calls.indices.toSet()
    calls.forEachIndexed { index, value ->
        if (index !in required) return@forEachIndexed
        val call = value as? JsonObject ?: return@forEachIndexed
        val name = (call["name"] as? JsonPrimitive)?.content ?: return@forEachIndexed
        val args = call["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        Column(Modifier.fillMaxWidth()) {
            Text("${index + 1}. ${ToolApprovalDisplay.previewText(name, args)}",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onApproveMember(index) }) { Text("只批准这条") }
        }
    }
}
