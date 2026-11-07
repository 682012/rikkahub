package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.PromptCacheRetention
import me.rerere.ai.provider.providers.openai.ModelCacheRetentionCapabilities
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AutoCompressSettings
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.utils.formatNumber

// 常见的上下文窗口大小，其余的走自定义输入
internal val ModelRuntimeContextPresets = listOf(128_000, 200_000, 1_000_000)

internal object ModelRuntimeTestTags {
    const val ROOT = "model_runtime_settings"
    const val CONTEXT_INPUT = "model_runtime_context_input"
    const val THRESHOLD_INPUT = "model_runtime_threshold_input"
    const val CONCURRENCY_INPUT = "model_runtime_concurrency_input"
    const val TRIGGER_TOKENS = "model_runtime_trigger_tokens"
}

/**
 * 输入框草稿的解析规则：不猜数字、不做修正。
 *
 * 空串交给调用方按「不设置」处理；"-1"、全角数字、小数、超过 Int 范围的串一律判非法，
 * 避免把 "-1" 过滤成 "1" 这种静默改写。
 */
internal object ModelRuntimeDraft {
    const val THRESHOLD_MIN = 10
    const val THRESHOLD_MAX = 100
    const val CONCURRENCY_MIN = 1
    const val CONCURRENCY_MAX = 64

    fun positiveIntOrNull(raw: String): Int? {
        if (raw.isEmpty() || raw.any { it !in '0'..'9' }) return null
        return raw.toIntOrNull()?.takeIf { it > 0 }
    }

    fun contextLengthOrNull(raw: String): Int? = positiveIntOrNull(raw)

    fun thresholdPercentOrNull(raw: String): Int? =
        positiveIntOrNull(raw)?.takeIf { it in THRESHOLD_MIN..THRESHOLD_MAX }

    fun concurrencyOrNull(raw: String): Int? =
        positiveIntOrNull(raw)?.takeIf { it in CONCURRENCY_MIN..CONCURRENCY_MAX }

    /** 按窗口大小和阈值算出的触发 token 数；用 Long 避免 100% × 大窗口时溢出。 */
    fun triggerTokens(contextLength: Int, percent: Int): Long =
        (contextLength.toLong() * percent.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX) + 99L) / 100L
}

internal fun formatTriggerTokens(tokens: Long): String =
    NumberFormat.getIntegerInstance().format(tokens)

/**
 * 单个模型的运行参数：上下文窗口、自动压缩、并发上限。
 *
 * 只处理草稿和校验，持久化仍由调用方通过 [onModelChange] 写回 Model；
 * 非法草稿不写回，只标红并通过 [onValidityChange] 让外层禁用确认按钮。
 * 输入状态以 model.id 为 key，编辑同一个模型时的 copy 不会重置输入或键盘，
 * 切换到另一个模型则重新从 Model 读回。
 */
@Composable
fun ModelRuntimeSettingsForm(
    model: Model,
    onModelChange: (Model) -> Unit,
    globalAutoCompress: AutoCompressSettings = AutoCompressSettings(),
    /**
     * 只有 OpenAI 系渠道认识缓存保留字段，其它渠道不显示这一项。
     * 具体能选哪些档位由渠道 API 决定（Chat Completions 没有 12h，Responses 有）。
     */
    cacheRetentionCapabilities: ModelCacheRetentionCapabilities =
        ModelCacheRetentionCapabilities(),
    modifier: Modifier = Modifier,
    onValidityChange: (Boolean) -> Unit = {},
) {
    val isChat = model.type == ModelType.CHAT

    var contextCustom by rememberSaveable(model.id) {
        mutableStateOf(model.contextLength !in ModelRuntimeContextPresets)
    }
    var contextText by rememberSaveable(model.id) { mutableStateOf(model.contextLength.toString()) }
    var thresholdText by rememberSaveable(model.id) {
        mutableStateOf(model.autoCompressThresholdPercent?.toString().orEmpty())
    }
    var concurrencyText by rememberSaveable(model.id) {
        mutableStateOf(model.maxConcurrentRequests?.toString().orEmpty())
    }

    val contextValid = !isChat ||
        !contextCustom ||
        ModelRuntimeDraft.contextLengthOrNull(contextText) != null
    val thresholdValid = !isChat ||
        thresholdText.isBlank() ||
        ModelRuntimeDraft.thresholdPercentOrNull(thresholdText) != null
    val concurrencyValid = concurrencyText.isBlank() ||
        ModelRuntimeDraft.concurrencyOrNull(concurrencyText) != null
    val valid = contextValid && thresholdValid && concurrencyValid

    val latestOnValidityChange by rememberUpdatedState(onValidityChange)
    LaunchedEffect(model.id, valid) { latestOnValidityChange(valid) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ModelRuntimeTestTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isChat) {
            Text(
                stringResource(R.string.setting_provider_page_context_length),
                style = MaterialTheme.typography.titleSmall,
            )
            // null 表示自定义
            ConnectedChoiceButtons(
                options = ModelRuntimeContextPresets + null,
                selected = if (contextCustom) null else model.contextLength,
                onSelect = { preset ->
                    contextCustom = preset == null
                    if (preset != null) {
                        contextText = preset.toString()
                        onModelChange(model.copy(contextLength = preset))
                    }
                },
            ) { preset, selected ->
                ConnectedChoiceLabel(
                    text = preset?.formatNumber()
                        ?: stringResource(R.string.setting_provider_page_context_length_custom),
                    selected = selected,
                )
            }
            AnimatedVisibility(visible = contextCustom) {
                OutlinedTextField(
                    value = contextText,
                    onValueChange = { raw ->
                        // 原样保留用户输入，只把合法正整数写回，非法时保持旧值并标红
                        contextText = raw
                        ModelRuntimeDraft.contextLengthOrNull(raw)?.let { length ->
                            onModelChange(model.copy(contextLength = length))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ModelRuntimeTestTags.CONTEXT_INPUT),
                    label = { Text(stringResource(R.string.setting_provider_page_context_length)) },
                    suffix = { Text("tokens") },
                    supportingText = {
                        if (!contextValid) {
                            Text(stringResource(R.string.model_runtime_invalid_positive))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !contextValid,
                )
            }

            Text(
                stringResource(R.string.model_runtime_auto_compress_title),
                style = MaterialTheme.typography.titleSmall,
            )
            ConnectedChoiceButtons(
                options = listOf<Boolean?>(null, true, false),
                selected = model.autoCompressEnabled,
                onSelect = { enabled ->
                    // null 跟全局，只改这一项，不动上下文和并发
                    onModelChange(model.copy(autoCompressEnabled = enabled))
                },
            ) { enabled, selected ->
                ConnectedChoiceLabel(
                    text = when (enabled) {
                        null -> stringResource(
                            if (globalAutoCompress.enabled) {
                                R.string.model_runtime_auto_compress_follow_on
                            } else {
                                R.string.model_runtime_auto_compress_follow_off
                            }
                        )

                        true -> stringResource(R.string.model_runtime_auto_compress_on)
                        false -> stringResource(R.string.model_runtime_auto_compress_off)
                    },
                    selected = selected,
                )
            }

            val effectivePercent = if (thresholdValid) {
                thresholdText.toIntOrNull() ?: globalAutoCompress.thresholdPercent
            } else {
                model.autoCompressThresholdPercent ?: globalAutoCompress.thresholdPercent
            }
            val effectiveContext = if (contextCustom) {
                ModelRuntimeDraft.contextLengthOrNull(contextText) ?: model.contextLength
            } else {
                model.contextLength
            }
            val triggerText = stringResource(
                R.string.model_runtime_auto_compress_trigger,
                formatTriggerTokens(
                    ModelRuntimeDraft.triggerTokens(effectiveContext, effectivePercent)
                ),
            )
            OutlinedTextField(
                value = thresholdText,
                onValueChange = { raw ->
                    thresholdText = raw
                    if (raw.isBlank()) {
                        // 空白 = 跟全局，写回 null，不动其它独立项
                        onModelChange(model.copy(autoCompressThresholdPercent = null))
                    } else {
                        ModelRuntimeDraft.thresholdPercentOrNull(raw)?.let { percent ->
                            onModelChange(model.copy(autoCompressThresholdPercent = percent))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ModelRuntimeTestTags.THRESHOLD_INPUT),
                label = { Text(stringResource(R.string.model_runtime_auto_compress_threshold)) },
                placeholder = {
                    Text(
                        stringResource(
                            R.string.model_runtime_auto_compress_threshold_placeholder,
                            globalAutoCompress.thresholdPercent,
                        )
                    )
                },
                supportingText = {
                    if (thresholdValid) {
                        Text(
                            text = if (model.autoCompressEnabled ?: globalAutoCompress.enabled) triggerText
                            else stringResource(R.string.model_runtime_auto_compress_disabled),
                            modifier = Modifier.testTag(ModelRuntimeTestTags.TRIGGER_TOKENS),
                        )
                    } else {
                        Text(stringResource(R.string.model_runtime_invalid_percent))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = !thresholdValid,
            )
        }

        OutlinedTextField(
            value = concurrencyText,
            onValueChange = { raw ->
                concurrencyText = raw
                if (raw.isBlank()) {
                    // 空白 = 无额外上限
                    onModelChange(model.copy(maxConcurrentRequests = null))
                } else {
                    ModelRuntimeDraft.concurrencyOrNull(raw)?.let { limit ->
                        onModelChange(model.copy(maxConcurrentRequests = limit))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ModelRuntimeTestTags.CONCURRENCY_INPUT),
            label = { Text(stringResource(R.string.model_runtime_concurrency_title)) },
            placeholder = { Text(stringResource(R.string.model_runtime_concurrency_placeholder)) },
            supportingText = {
                Text(
                    if (concurrencyValid) {
                        stringResource(R.string.model_runtime_concurrency_desc)
                    } else {
                        stringResource(
                            R.string.model_runtime_invalid_range,
                            ModelRuntimeDraft.CONCURRENCY_MIN,
                            ModelRuntimeDraft.CONCURRENCY_MAX,
                        )
                    }
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = !concurrencyValid,
        )

        if (isChat && cacheRetentionCapabilities.supportsAny) {
            Text(
                stringResource(R.string.model_cache_retention_title),
                style = MaterialTheme.typography.titleSmall,
            )
            ConnectedChoiceButtons(
                options = cacheRetentionCapabilities.selectable(),
                selected = model.promptCacheRetention,
                onSelect = { retention ->
                    onModelChange(model.copy(promptCacheRetention = retention))
                },
            ) { retention, _ ->
                stringResource(
                    when (retention) {
                        PromptCacheRetention.DEFAULT -> R.string.model_cache_retention_default
                        PromptCacheRetention.THIRTY_MINUTES -> R.string.model_cache_retention_30m
                        PromptCacheRetention.TWELVE_HOURS -> R.string.model_cache_retention_12h
                        PromptCacheRetention.ONE_DAY -> R.string.model_cache_retention_24h
                    }
                )
            }
            Text(
                stringResource(R.string.model_cache_retention_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
