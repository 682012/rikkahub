package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallpaperToneUiTest {
    @get:Rule val rule=createComposeRule()
    @Test fun blueWallpaperCoordinatesPanelsAndToggleRestoresOriginalColor() {
        val source=AppearanceTestSupport.solid("r7-blue",android.graphics.Color.rgb(115,144,210))
        var enabled by mutableStateOf(true)
        var tone:Color?=null;var surface:Color?=null
        val scheme=darkColorScheme()
        rule.setContent {
            val settings=Settings.dummy().copy(displaySetting=DisplaySetting(appearance=AppearanceSettings(wallpaper=source,wallpaperAdaptiveTint=enabled)))
            MaterialExpressiveTheme(colorScheme=scheme) {
                AppAppearanceHost(settings) {
                    val current=MaterialTheme.colorScheme.surfaceContainer
                    val sampled=LocalWallpaperTone.current
                    SideEffect { surface=current;tone=sampled }
                    Column(Modifier.fillMaxSize()) {
                        CardGroup(title={ Text("后台生成") }) { item(headlineContent={Text("角色与通知")}) }
                        Surface(color=appearanceSurface(current),contentColor=MaterialTheme.colorScheme.onSurface) {Text("对比保护")}
                    }
                }
            }
        }
        rule.waitUntil(10000){tone!=null && surface!=scheme.surfaceContainer}
        rule.onNodeWithText("后台生成").assertIsDisplayed()
        rule.runOnIdle {assertTrue(surface!!.blue>surface!!.red);enabled=false}
        rule.waitUntil(10000){surface==scheme.surfaceContainer}
        rule.onNodeWithText("对比保护").assertIsDisplayed()
        AppearanceTestSupport.capture(rule,"r7-wallpaper-label-contrast")
    }
}
