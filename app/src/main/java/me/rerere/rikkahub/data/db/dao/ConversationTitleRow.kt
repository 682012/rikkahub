package me.rerere.rikkahub.data.db.dao

/** Small scoped projection used by archive history; never loads message bodies. */
data class ConversationTitleRow(val id: String, val title: String)
