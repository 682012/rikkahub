package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model

/** Resolve independently: a model can override either the switch or the threshold. */
fun AutoCompressSettings.forModel(model: Model): AutoCompressSettings = copy(
    enabled = model.autoCompressEnabled ?: enabled,
    thresholdPercent = (model.autoCompressThresholdPercent ?: thresholdPercent).coerceIn(10, 100),
)
