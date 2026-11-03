package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.FloatingCompanionLimits
import java.io.File
import java.net.URI

/** Rebase only single-level folders owned by this app; other files/network URLs remain untouched. */
internal fun Settings.withRestoredWallpaperPath(filesDir: File): Settings {
    fun relocate(source: String?, directory: String): String? {
        if(source==null) return null
        val uri=runCatching { URI(source) }.getOrNull() ?: return source
        if(uri.scheme!="file" || !uri.host.isNullOrEmpty()) return source
        val file=uri.path?.let(::File) ?: return source
        if(file.parentFile?.name!=directory || file.name in listOf("", ".", "..")) return source
        return File(File(filesDir,directory),file.name).toURI().toString()
    }
    val companion=displaySetting.floatingCompanion
    val wallpaper=relocate(displaySetting.appearance.wallpaper,"appearance_wallpapers")
    val idle=relocate(companion.customImagePath,FloatingCompanionLimits.DIRECTORY)
    val tap=relocate(companion.customTapImagePath,FloatingCompanionLimits.DIRECTORY)
    if(wallpaper==displaySetting.appearance.wallpaper && idle==companion.customImagePath && tap==companion.customTapImagePath) return this
    return copy(displaySetting=displaySetting.copy(
        appearance=displaySetting.appearance.copy(wallpaper=wallpaper),
        floatingCompanion=companion.copy(customImagePath=idle,customTapImagePath=tap),
    ))
}
