package me.rerere.rikkahub

import android.content.res.Configuration
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class UpstreamIdentityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nameMatchesUpstreamAcrossSupportedLocales() {
        for (tag in listOf("en", "zh-CN", "zh-TW", "ja", "ko-KR", "de", "ar", "ru")) {
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
            val localized = context.createConfigurationContext(configuration)
            assertEquals(tag, "RikkaHub", localized.getString(R.string.app_name))
        }
    }

    @Test
    fun releaseIdentityKeepsTheExistingApplication() {
        assertEquals("3.1.1-r16", BuildConfig.VERSION_NAME)
        assertEquals("3000063", BuildConfig.VERSION_CODE.toString())
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertTrue(context.packageName.startsWith("me.rerere.rikkahub.fork"))
        assertEquals(R.string.app_name, context.applicationInfo.labelRes)
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
    }

    @Test
    fun upstreamAdaptiveIconHasAllLayers() {
        val icon = context.getDrawable(R.mipmap.ic_launcher)
        assertTrue(icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        if (Build.VERSION.SDK_INT >= 33) assertNotNull(icon.monochrome)
    }
}
