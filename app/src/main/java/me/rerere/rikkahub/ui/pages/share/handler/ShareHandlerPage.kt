package me.rerere.rikkahub.ui.pages.share.handler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Assistant picker for anything shared into (or opened with) the app.
 *
 * Nothing is sent here: the payload is only carried into a new conversation, so the user still
 * reviews it in the input box. Attachments are handed over as-is; the chat page imports them into
 * app storage while the inbound read grant is still alive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareHandlerPage(
    text: String,
    images: List<String> = emptyList(),
    droppedAttachments: Int = 0,
) {
    val vm: ShareHandlerVM = koinViewModel(parameters = { parametersOf(text) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val attachments = remember(images) { images.distinct() }
    // Only real images are decoded. Documents are listed by name, so a provider that hands over an
    // odd URI can never force bitmap decoding of something that is not an image.
    val mimeTypes by produceState<Map<String, String?>>(emptyMap(), attachments) {
        value = withContext(Dispatchers.IO) {
            attachments.associateWith { raw ->
                runCatching { context.contentResolver.getType(raw.toUri()) }.getOrNull()
            }
        }
    }
    // One navigation at a time: a second tap while the assistant is being switched must not
    // push a second new chat, and a failed switch has to release the guard again.
    var opening by remember(vm) { mutableStateOf(false) }
    val failed = stringResource(R.string.r20_share_open_failed)

    fun open(assistantId: kotlin.uuid.Uuid) {
        if (opening) return
        opening = true
        scope.launch {
            val switched = runCatching { vm.updateAssistant(assistantId) }.isSuccess
            if (!switched) {
                opening = false
                toaster.show(failed, type = ToastType.Error)
                return@launch
            }
            val opened = runCatching {
                navigateToChatPage(
                    navigator = navController,
                    initText = vm.shareText.base64Encode(),
                    initFiles = attachments.map { it.toUri() },
                )
            }.isSuccess
            if (!opened) {
                opening = false
                toaster.show(failed, type = ToastType.Error)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.share_handler_page_title))
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.shareText.isNotBlank() || attachments.isNotEmpty() || droppedAttachments > 0) {
                item {
                    Card {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (vm.shareText.isNotBlank()) {
                                // Full shared text, scrollable instead of truncated: nothing the user
                                // shared may be dropped before it reaches the input box.
                                Text(
                                    text = vm.shareText,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }

                            if (attachments.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.r20_share_attachment_count,
                                        attachments.size,
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                attachments.forEach { raw ->
                                    val isImage = mimeTypes[raw]?.startsWith("image/") == true
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (isImage) {
                                            AsyncImage(
                                                model = raw.toUri(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(8.dp)),
                                            )
                                        }
                                        val name = SharedInput.displayName(raw).ifEmpty {
                                            stringResource(R.string.r20_share_attachment_unknown)
                                        }
                                        val mime = mimeTypes[raw]
                                        Text(
                                            text = if (mime.isNullOrBlank()) name else "$name\n$mime",
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }

                            if (droppedAttachments > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.r20_share_attachment_dropped,
                                        droppedAttachments,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            Text(
                                text = stringResource(R.string.r20_share_review_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(settings.assistants, key = { it.id.toString() }) { assistant ->
                Surface(
                    onClick = { open(assistant.id) },
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.medium
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = assistant.name.ifEmpty {
                                    stringResource(R.string.assistant_page_default_assistant)
                                },
                                maxLines = 1
                            )
                        },
                    )
                }
            }
        }
    }
}
