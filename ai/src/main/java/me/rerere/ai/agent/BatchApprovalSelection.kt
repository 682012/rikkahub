package me.rerere.ai.agent

/** Internal UI-to-service payload. Never read this value from model tool arguments. */
object BatchApprovalSelection {
    private const val PREFIX = "rikka:approve-members:"
    fun encode(indices: Set<Int>): String {
        require(indices.isNotEmpty() && indices.all { it in 0..15 })
        return PREFIX + indices.sorted().joinToString(",")
    }
    /** null means the ordinary approve-all action; malformed selections fail closed. */
    fun decode(value: String): Set<Int>? {
        if (!value.startsWith(PREFIX)) return null
        val tokens = value.removePrefix(PREFIX).split(',')
        require(tokens.size in 1..16) { "Invalid batch approval selection" }
        val indices = tokens.map { it.toIntOrNull() ?: error("Invalid batch member index") }.toSet()
        require(indices.all { it in 0..15 }) { "Invalid batch member index" }
        return indices
    }
}
