package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.MultiImageSendSettings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem

@Composable
internal fun MultiImageSendSettingsCard(
    settings: MultiImageSendSettings,
    onChange: ((MultiImageSendSettings) -> MultiImageSendSettings) -> Unit,
) {
    val current = settings.normalized()
    var thresholdText by remember(current.threshold) { mutableStateOf(current.threshold.toString()) }
    var batchText by remember(current.batchSize) { mutableStateOf(current.batchSize.toString()) }
    CardGroup(title = { Text(stringResource(R.string.r17_images_title)) }) {
        switchItem(
            checked = current.enabled,
            onCheckedChange = { enabled -> onChange { it.copy(enabled = enabled) } },
            headlineContent = { Text(stringResource(R.string.r17_images_enabled)) },
            supportingContent = { Text(stringResource(R.string.r17_images_description)) },
        )
        if (current.enabled) {
            formItem(headlineContent = { Text(stringResource(R.string.r17_images_threshold)) }) {
                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { text ->
                        if (text.length <= 3 && text.all { it in '0'..'9' }) {
                            thresholdText = text
                            text.toIntOrNull()?.takeIf { it in 0..MultiImageSendSettings.MAX_IMAGES }?.let { count ->
                                onChange { it.copy(threshold = count) }
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.r17_images_count)) },
                    supportingText = { Text(stringResource(R.string.r17_images_threshold_hint, current.threshold)) },
                    isError = thresholdText.toIntOrNull() !in 0..MultiImageSendSettings.MAX_IMAGES,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            formItem(headlineContent = { Text(stringResource(R.string.r17_images_batch_size)) }) {
                OutlinedTextField(
                    value = batchText,
                    onValueChange = { text ->
                        if (text.length <= 3 && text.all { it in '0'..'9' }) {
                            batchText = text
                            text.toIntOrNull()?.takeIf { it in 1..MultiImageSendSettings.MAX_IMAGES }?.let { count ->
                                onChange { it.copy(batchSize = count) }
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.r17_images_count)) },
                    supportingText = { Text(stringResource(R.string.r17_images_batch_hint)) },
                    isError = batchText.toIntOrNull() !in 1..MultiImageSendSettings.MAX_IMAGES,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
