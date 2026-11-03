package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.rikkahub.utils.WorkspaceArtifactLink

/** Overlay has no navigation stack; its owner supplies an explicit in-app route. */
val LocalWorkspaceArtifactOpener = compositionLocalOf<((String, WorkspaceArtifactLink.WorkspaceFile) -> Unit)?> { null }
