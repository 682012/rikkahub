package me.rerere.rikkahub.ui.pages.mediacreation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.media.export.*
import java.io.File

/** Explicit local export, independent of remote generation parameters; dismissing cancels the encoder. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaExportDialog(file: File, mime: String, sourceWidth: Int?, sourceHeight: Int?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf("PNG") }
    val ratio = (sourceWidth ?: 1024).toFloat() / (sourceHeight ?: 1024).coerceAtLeast(1)
    var width by remember { mutableStateOf(if (ratio >= 1) "1024" else (1024 * ratio).toInt().coerceAtLeast(16).toString()) }
    var height by remember { mutableStateOf(if (ratio <= 1) "1024" else (1024 / ratio).toInt().coerceAtLeast(16).toString()) }
    var fit by remember { mutableStateOf(ExportFit.FIT) }
    var motion by remember { mutableStateOf(MotionStyle.ZOOM_IN) }
    var seconds by remember { mutableIntStateOf(3) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<MediaExportResult?>(null) }
    val animated = format == "GIF" || format == "MP4"
    val close = { job?.cancel(); onDismiss() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.r20_export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.r20_export_local_note), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("PNG", "JPEG", "WebP", "GIF", "MP4").forEach { choice ->
                        FilterChip(selected = format == choice, enabled = !busy, onClick = { format = choice; result = null }, label = { Text(choice) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(width, { width = it.filter(Char::isDigit).take(4); result = null }, Modifier.weight(1f),
                        enabled = !busy, label = { Text(stringResource(R.string.r20_media_export_width)) }, singleLine = true)
                    OutlinedTextField(height, { height = it.filter(Char::isDigit).take(4); result = null }, Modifier.weight(1f),
                        enabled = !busy, label = { Text(stringResource(R.string.r20_media_export_height)) }, singleLine = true)
                }
                if (!animated) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(fit == ExportFit.FIT, { fit = ExportFit.FIT }, enabled = !busy,
                            label = { Text(stringResource(R.string.r20_media_export_fit_contain)) })
                        FilterChip(fit == ExportFit.CROP, { fit = ExportFit.CROP }, enabled = !busy,
                            label = { Text(stringResource(R.string.r20_media_export_fit_cover)) })
                    }
                } else {
                    Text(stringResource(R.string.r20_export_motion_limit), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(MotionStyle.ZOOM_IN to R.string.r20_export_zoom, MotionStyle.PAN_RIGHT to R.string.r20_export_pan,
                            MotionStyle.FADE_IN to R.string.r20_export_fade).forEach { (style, label) ->
                            FilterChip(motion == style, { motion = style }, enabled = !busy, label = { Text(stringResource(label)) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (2..5).forEach { duration -> FilterChip(seconds == duration, { seconds = duration }, enabled = !busy, label = { Text("${duration}s") }) }
                    }
                }
                if (mime == "image/gif" || mime == "image/webp") Text(stringResource(R.string.r20_export_first_frame), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                result?.let { output ->
                    Text("${output.mimeType} · ${output.width}×${output.height}" + (output.durationMillis?.let { " · ${it / 1000.0}s" } ?: ""))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { runCatching { saveMediaToGallery(context, output.file, output.mimeType) }.onFailure { error = it.message } } }) { Text(stringResource(R.string.media_creation_page_save_to_gallery)) }
                        TextButton(onClick = { runCatching { shareMedia(context, output.file, output.mimeType) }.onFailure { error = it.message } }) { Text(stringResource(R.string.share)) }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.r20_export_close)) } },
        confirmButton = {
            Button(onClick = {
                if (busy) job?.cancel() else {
                    error = null; result = null; busy = true; progress = 0f
                    job = scope.launch {
                        try {
                            val size = ExportSize(width.toIntOrNull() ?: 0, height.toIntOrNull() ?: 0)
                            val listener = ExportProgressListener { fraction, _ -> scope.launch { progress = fraction } }
                            result = if (animated) MediaExportEngine.exportMotion(context, file,
                                MotionSpec(motion, size, seconds, if (format == "GIF") MotionOutput.GIF else MotionOutput.MP4), listener)
                            else MediaExportEngine.exportImage(context, file,
                                ImageExportTarget(when (format) { "JPEG" -> ExportImageFormat.JPEG; "WebP" -> ExportImageFormat.WEBP; else -> ExportImageFormat.PNG }, size, fit), listener)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: context.getString(R.string.r20_export_failed) }
                        finally { busy = false }
                    }
                }
            }) { Text(stringResource(if (busy) R.string.r20_export_cancel else R.string.r20_export_start)) }
        },
    )
}
