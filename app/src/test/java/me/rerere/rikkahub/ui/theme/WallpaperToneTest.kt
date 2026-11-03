package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class WallpaperToneTest {
    @Test fun emptyAndMonochromeKeepOriginalTheme() {
        assertNull(wallpaperTone(intArrayOf()))
        assertNull(wallpaperTone(intArrayOf(0xff000000.toInt(),0xffffffff.toInt(),0xff888888.toInt())))
        val scheme=darkColorScheme()
        assertSame(scheme,scheme.withWallpaperTone(null,true))
    }
    @Test fun dominantBlueWinsAndTransparentPixelsAreIgnored() {
        val result=wallpaperTone(IntArray(40) { if(it<30) 0xff687da4.toInt() else 0xffdd8844.toInt() })!!
        assertTrue(result.blue>result.red)
        assertNull(wallpaperTone(intArrayOf(0x006060ff)))
    }
    @Test fun lightAndDarkSurfaceLuminanceIsPreserved() {
        val tone=Color(0xff90afe0)
        for(scheme in listOf(lightColorScheme(),darkColorScheme())) {
            val next=scheme.withWallpaperTone(tone,true)
            // sRGB Color is quantized to 8 bits; allow one channel-step worth of luminance error.
            assertTrue("luminance ${scheme.surfaceContainer.luminance()} -> ${next.surfaceContainer.luminance()}", abs(next.surfaceContainer.luminance()-scheme.surfaceContainer.luminance())<0.005f)
            assertEquals(scheme.onSurface,next.onSurface)
            assertEquals(scheme.onBackground,next.onBackground)
            assertTrue(surfaceContrast(next.surfaceContainer,next.onSurface)>4.5f)
            assertNotEquals(scheme.surfaceContainer,next.surfaceContainer)
        }
    }
    @Test fun disabledSwitchKeepsSchemeAndAmoledStaysBlack() {
        val scheme=darkColorScheme()
        assertSame(scheme,scheme.withWallpaperTone(Color.Blue,false))
        assertEquals(Color.Black,harmonizeSurface(Color.Black,Color.Blue))
    }
    @Test fun tintedPanelsRemainSafeOverEitherWallpaperExtreme() {
        for(scheme in listOf(lightColorScheme(),darkColorScheme())) {
            val tinted=scheme.withWallpaperTone(Color(0xff758bd1),true)
            val alpha=safeSurfaceOpacity(tinted.surfaceContainerHigh,tinted.onSurface,.6f)
            assertTrue(alpha in .6f..1f)
        }
    }
}
