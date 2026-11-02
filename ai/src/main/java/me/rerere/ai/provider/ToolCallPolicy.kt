package me.rerere.ai.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keep tool declarations in the cache prefix, but prohibit invocation for a text-only summary. */
internal fun JsonObject.applyToolCallPolicy(params: TextGenerationParams, claude: Boolean = false): JsonObject {
    if (!params.disableToolCalls || this["tools"] == null) return this
    val none = if (claude) buildJsonObject { put("type", "none") } else JsonPrimitive("none")
    // Applied AFTER custom body: a saved tool_choice override must not re-enable summary tools.
    return JsonObject(this + ("tool_choice" to none))
}

val ProviderSetting.canDisableToolCallsWithDefinitions: Boolean
    get() = this is ProviderSetting.OpenAI || this is ProviderSetting.Claude
