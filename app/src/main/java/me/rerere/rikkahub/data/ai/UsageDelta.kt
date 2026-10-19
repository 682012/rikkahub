package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.TokenUsage

/** Usage on an assistant message is cumulative across approval resumes. Persist only this run's delta. */
internal fun usageDelta(current: TokenUsage?, previous: TokenUsage?): TokenUsage? = current?.let {
    TokenUsage(
        promptTokens = (it.promptTokens - (previous?.promptTokens ?: 0)).coerceAtLeast(0),
        completionTokens = (it.completionTokens - (previous?.completionTokens ?: 0)).coerceAtLeast(0),
        cachedTokens = (it.cachedTokens - (previous?.cachedTokens ?: 0)).coerceAtLeast(0),
        totalTokens = (it.totalTokens - (previous?.totalTokens ?: 0)).coerceAtLeast(0),
    )
}
