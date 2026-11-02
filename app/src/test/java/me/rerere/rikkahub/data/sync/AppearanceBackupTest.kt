package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppearanceBackupTest {
    private fun settings(path: String) = Settings.dummy().copy(displaySetting = DisplaySetting(appearance = AppearanceSettings(wallpaper = path)))
    @Test fun ownedWallpaperRebasesToNewApplicationDirectory() {
        val next = settings("file:///old/app/files/appearance_wallpapers/picture%20one.png")
            .withRestoredWallpaperPath(File("/new/app/files"))
        assertEquals("file:/new/app/files/appearance_wallpapers/picture%20one.png", next.displaySetting.appearance.wallpaper)
    }
    @Test fun networkAndUnownedFilesNeverGetRewritten() {
        for (path in listOf("https://example.test/a.png", "file:///old/app/files/upload/a.png", "file://remote/appearance_wallpapers/a.png")) {
            val original = settings(path)
            assertSame(original, original.withRestoredWallpaperPath(File("/new/app/files")))
        }
    }
}
