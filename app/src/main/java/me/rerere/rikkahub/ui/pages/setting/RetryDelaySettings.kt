package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.RetryDelayMode
import me.rerere.rikkahub.ui.components.ui.CardGroupScope

/**
 * "网络 → 自动重试"里等待时长的几个常用预设，外加自定义区间。
 *
 * 这些预设只是往 [NetworkSetting] 里写固定的几个数，没有任何隐藏语义：
 * [FIXED_30]/[FIXED_60] 每次等同样的秒数，[RANGE_30_60] 每次在 30~60 之间随机取一个整秒值。
 */
internal enum class RetryDelayPreset {
    FIXED_30,
    FIXED_60,
    RANGE_30_60,
    CUSTOM,
}

/** 当前设置落在哪个预设上；都不是就是 [RetryDelayPreset.CUSTOM]，界面会展开两个输入框。 */
internal fun retryDelayPresetOf(setting: NetworkSetting): RetryDelayPreset {
    val delay = setting.normalizedRetryDelay()
    return when {
        delay.mode == RetryDelayMode.FIXED && delay.fixedSeconds == 30 -> RetryDelayPreset.FIXED_30
        delay.mode == RetryDelayMode.FIXED && delay.fixedSeconds == 60 -> RetryDelayPreset.FIXED_60
        delay.mode == RetryDelayMode.RANGE &&
            delay.minSeconds == 30 && delay.maxSeconds == 60 -> RetryDelayPreset.RANGE_30_60

        else -> RetryDelayPreset.CUSTOM
    }
}

/**
 * 把预设写进设置。
 *
 * 切到 [RetryDelayPreset.CUSTOM] 时把当前生效的等待搬进区间字段（固定值变成 min == max 的区间），
 * 这样"点一下自定义"不会顺手改掉用户已经在用的等待时长。
 */
internal fun NetworkSetting.withRetryDelayPreset(preset: RetryDelayPreset): NetworkSetting {
    val delay = normalizedRetryDelay()
    return when (preset) {
        RetryDelayPreset.FIXED_30 -> copy(
            retryDelayMode = RetryDelayMode.FIXED,
            retryDelaySeconds = 30,
        )

        RetryDelayPreset.FIXED_60 -> copy(
            retryDelayMode = RetryDelayMode.FIXED,
            retryDelaySeconds = 60,
        )

        RetryDelayPreset.RANGE_30_60 -> copy(
            retryDelayMode = RetryDelayMode.RANGE,
            retryDelayMinSeconds = 30,
            retryDelayMaxSeconds = 60,
        )

        RetryDelayPreset.CUSTOM -> when (delay.mode) {
            RetryDelayMode.FIXED -> copy(
                retryDelayMode = RetryDelayMode.RANGE,
                retryDelayMinSeconds = delay.fixedSeconds,
                retryDelayMaxSeconds = delay.fixedSeconds,
            )

            RetryDelayMode.RANGE -> copy(
                retryDelayMinSeconds = delay.minSeconds,
                retryDelayMaxSeconds = delay.maxSeconds,
            )
        }
    }
}

/**
 * 自定义区间在界面上应该显示的两端。
 *
 * 固定在用的时候区间字段还留着默认的 30/60，直接把原始字段摆出来会和实际等待对不上，
 * 所以固定模式一律按"同一个值的区间"显示。
 */
internal fun NetworkSetting.editableRetryRange(): IntRange {
    val delay = normalizedRetryDelay()
    return when (delay.mode) {
        RetryDelayMode.FIXED -> delay.fixedSeconds..delay.fixedSeconds
        RetryDelayMode.RANGE -> delay.minSeconds..delay.maxSeconds
    }
}

/**
 * 一次秒数输入收敛后的结果。
 *
 * @property text 应该显示在输入框里的文本
 * @property value 应该落库的秒数；null 表示这次输入只是"清空待重输"，不改动已保存的设置
 */
internal data class RetrySecondsInput(val text: String, val value: Int?)

/**
 * 秒数输入：只留数字、最多 3 位、夹在 1~600，显示文本和落库值出自同一个数。
 */
internal fun sanitizeRetrySecondsInput(raw: String): RetrySecondsInput {
    val digits = raw.filter { it in '0'..'9' }.take(3)
    if (digits.isEmpty()) return RetrySecondsInput(text = "", value = null)
    val value = digits.toInt().coerceIn(
        NetworkSetting.MIN_RETRY_DELAY_SECONDS,
        NetworkSetting.MAX_RETRY_DELAY_SECONDS,
    )
    return RetrySecondsInput(text = value.toString(), value = value)
}

/** 设置页上的一句话说明。区间两端相等时按固定等待说，不写"随机"。 */
@Composable
internal fun retryDelaySummary(setting: NetworkSetting): String {
    val delay = setting.normalizedRetryDelay()
    return when (delay.mode) {
        RetryDelayMode.FIXED -> stringResource(
            R.string.retry_settings_delay_desc_fixed,
            delay.fixedSeconds,
        )

        RetryDelayMode.RANGE -> if (delay.minSeconds == delay.maxSeconds) {
            stringResource(R.string.retry_settings_delay_desc_fixed, delay.minSeconds)
        } else {
            stringResource(
                R.string.retry_settings_delay_desc_range,
                delay.minSeconds,
                delay.maxSeconds,
            )
        }
    }
}

/**
 * 网络设置页里的"重试等待"一组项：预设 + 自定义区间的两个输入框。
 *
 * 和 CardGroup 里其它 item 一样不是 @Composable：它只往 [CardGroupScope] 里登记内容，
 * 文案全部在各自的 content lambda 里取，才能跟着语言和主题重组。
 */
internal fun CardGroupScope.retryDelaySettingItems(
    setting: NetworkSetting,
    onChange: (NetworkSetting) -> Unit,
) {
    formItem(
        headlineContent = { Text(stringResource(R.string.retry_settings_delay_title)) },
        supportingContent = { Text(retryDelaySummary(setting)) },
    ) {
        // Editing is UI state, not inferred from the selected values. A custom 30..60
        // range must not turn its own fields off or make the Custom button a no-op.
        var customEditing by rememberSaveable {
            mutableStateOf(retryDelayPresetOf(setting) == RetryDelayPreset.CUSTOM)
        }
        val preset = if (customEditing) RetryDelayPreset.CUSTOM else retryDelayPresetOf(setting)
        val options = RetryDelayPreset.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = preset == option,
                    onClick = {
                        customEditing = option == RetryDelayPreset.CUSTOM
                        if (preset != option) onChange(setting.withRetryDelayPreset(option))
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(retryDelayPresetLabel(option)) }
            }
        }
        if (customEditing) {
            val range = setting.editableRetryRange()
            RetrySecondsField(
                label = stringResource(R.string.retry_settings_custom_min),
                seconds = range.first,
                onSecondsChange = { newMin ->
                    onChange(setting.copy(
                        retryDelayMode = RetryDelayMode.RANGE,
                        retryDelayMinSeconds = newMin,
                        retryDelayMaxSeconds = maxOf(newMin, range.last),
                    ))
                },
            )
            RetrySecondsField(
                label = stringResource(R.string.retry_settings_custom_max),
                seconds = range.last,
                supporting = stringResource(R.string.retry_settings_custom_desc),
                onSecondsChange = { newMax ->
                    onChange(setting.copy(
                        retryDelayMode = RetryDelayMode.RANGE,
                        retryDelayMinSeconds = minOf(newMax, range.first),
                        retryDelayMaxSeconds = newMax,
                    ))
                },
            )
        }
    }
}

@Composable
private fun retryDelayPresetLabel(preset: RetryDelayPreset): String = when (preset) {
    RetryDelayPreset.FIXED_30 -> stringResource(R.string.retry_settings_preset_fixed_30)
    RetryDelayPreset.FIXED_60 -> stringResource(R.string.retry_settings_preset_fixed_60)
    RetryDelayPreset.RANGE_30_60 -> stringResource(R.string.retry_settings_preset_range)
    RetryDelayPreset.CUSTOM -> stringResource(R.string.retry_settings_preset_custom)
}

@Composable
private fun RetrySecondsField(
    label: String,
    seconds: Int,
    supporting: String? = null,
    onSecondsChange: (Int) -> Unit,
) {
    var text by remember(seconds) { mutableStateOf(seconds.toString()) }
    OutlinedTextField(
        value = text,
        label = { Text(label) },
        supportingText = supporting?.let { value -> { Text(value) } },
        onValueChange = { raw ->
            val input = sanitizeRetrySecondsInput(raw)
            text = input.text
            input.value?.let(onSecondsChange)
        },
        modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
            if (!focus.isFocused && text.isEmpty()) text = seconds.toString()
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
