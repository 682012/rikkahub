package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.Settings
import java.io.File
import java.net.URI

/** Only our single-level owned wallpaper folder can be rebased; network/assistant/other files stay intact. */
internal fun Settings.withRestoredWallpaperPath(filesDir: File): Settings {
    val appearance = displaySetting.appearance
    val source = appearance.wallpaper ?: return this
    val uri = runCatching { URI(source) }.getOrNull() ?: return this
    if (uri.scheme != "file" || !uri.host.isNullOrEmpty()) return this
    val file = uri.path?.let(::File) ?: return this
    if (file.parentFile?.name != "appearance_wallpapers" || file.name in listOf("", ".", "..")) return this
    val path = File(File(filesDir, "appearance_wallpapers"), file.name).toURI().toString()
    return copy(displaySetting = displaySetting.copy(appearance = appearance.copy(wallpaper = path)))
}
