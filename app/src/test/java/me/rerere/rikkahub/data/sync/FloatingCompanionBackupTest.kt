package me.rerere.rikkahub.data.sync

import java.io.File
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import org.junit.Assert.*
import org.junit.Test

class FloatingCompanionBackupTest {
    @Test fun idleAndTapRebaseEvenWithoutWallpaper() {
        val s=Settings.dummy().let { it.copy(displaySetting=it.displaySetting.copy(floatingCompanion=FloatingCompanionSettings(
            customImagePath="file:///old/files/floating_companions/a%20one.png",
            customTapImagePath="file:///old/files/floating_companions/tap.gif",
        ))) }
        val n=s.withRestoredWallpaperPath(File("/new/files"))
        assertEquals("file:/new/files/floating_companions/a%20one.png",n.displaySetting.floatingCompanion.customImagePath)
        assertEquals("file:/new/files/floating_companions/tap.gif",n.displaySetting.floatingCompanion.customTapImagePath)
    }
    @Test fun foreignPathsAreNotRebased() {
        val s=Settings.dummy().let { it.copy(displaySetting=it.displaySetting.copy(floatingCompanion=FloatingCompanionSettings(customImagePath="file://remote/floating_companions/a.png"))) }
        assertSame(s,s.withRestoredWallpaperPath(File("/new/files")))
    }
}
