package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.datastore.SubagentSettings

internal fun isSubagentToolAllowed(name: String, settings: SubagentSettings): Boolean =
    !name.startsWith("subagent_") &&
        (settings.includeWorkspace || !name.startsWith("workspace_")) &&
        (settings.includeMcp || !name.startsWith("mcp__")) &&
        (settings.includeSearch || name !in setOf("search_web", "scrape_web")) &&
        (settings.includeChatHistory || name !in setOf("recent_chats", "conversation_search", "conversation_read"))
