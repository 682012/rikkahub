package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

@Composable
internal fun TextArtifactPreview(
    content: String,
    fileName: String,
    modifier: Modifier = Modifier,
    resourceReader: (suspend (String) -> ByteArray)? = null,
    fragment: String? = null,
) {
    when (val format = textArtifactFormat(fileName)) {
        TextArtifactFormat.HTML, TextArtifactFormat.SVG -> InteractiveHtmlPreview(
            html = content, isSvg = format == TextArtifactFormat.SVG, modifier = modifier,
            resourceReader = resourceReader, fragment = fragment,
        )
        TextArtifactFormat.MARKDOWN -> SelectionContainer(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            MarkdownBlock(content = content)
        }
        TextArtifactFormat.TABLE -> {
            val table = remember(content, fileName) {
                parseDelimitedPreview(content, if (fileName.endsWith(".tsv", true)) '\t' else ',')
            }
            Column(modifier) {
                if (table.truncated) Text(stringResource(R.string.r11_preview_table_truncated), Modifier.padding(12.dp))
                val horizontal = rememberScrollState()
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                        itemsIndexed(table.rows) { index, cells ->
                            Column {
                                Row(Modifier.background(if (index == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)) {
                                    cells.forEach { cell ->
                                        Text(cell, Modifier.width(180.dp).padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
        else -> {
            val text = remember(content, format) {
                if (format == TextArtifactFormat.JSON) runCatching {
                    // 显式传 serializer：reified 的 encodeToString 在别的包，不能靠隐式 import
                    Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(content))
                }.getOrDefault(content) else content
            }
            SelectionContainer(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}
