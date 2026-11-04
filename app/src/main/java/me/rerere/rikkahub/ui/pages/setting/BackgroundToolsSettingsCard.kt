package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.BackgroundToolSettings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem

@Composable
internal fun BackgroundToolsSettingsCard(settings: BackgroundToolSettings, onChange: (BackgroundToolSettings) -> Unit) {
    var timeout by remember(settings.foregroundTimeoutSeconds) { mutableStateOf(settings.foregroundTimeoutSeconds.toString()) }
    CardGroup(title = { Text(stringResource(R.string.r11_background_tools_title)) }) {
        switchItem(
            checked = settings.enabled,
            onCheckedChange = { onChange(settings.copy(enabled = it)) },
            headlineContent = { Text(stringResource(R.string.r11_background_tools_enabled)) },
            supportingContent = { Text(stringResource(R.string.r11_background_tools_desc)) },
        )
        if (settings.enabled) {
            formItem(headlineContent = { Text(stringResource(R.string.r11_background_tools_wait)) }) {
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all(Char::isDigit)) {
                            timeout = value
                            value.toIntOrNull()?.takeIf { it in 1..3600 }?.let {
                                onChange(settings.copy(foregroundTimeoutSeconds = it))
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.r11_background_tools_seconds)) },
                    supportingText = { Text(stringResource(R.string.r11_background_tools_wait_desc)) },
                    isError = timeout.toIntOrNull() !in 1..3600,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
