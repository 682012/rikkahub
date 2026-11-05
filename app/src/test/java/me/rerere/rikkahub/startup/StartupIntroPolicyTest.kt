package me.rerere.rikkahub.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷启动开场的纯状态决策：不碰 Compose，也不碰 Activity，只把"什么时候画"钉死。
 */
class StartupIntroPolicyTest {

    private fun planFor(
        motion: StartupMotion = StartupMotion.DEFAULT,
        reduceMotion: Boolean = false,
        coldStart: Boolean = true,
        plainLaunch: Boolean = true,
        alreadyShownInProcess: Boolean = false,
        splashExitSupported: Boolean = true,
    ): StartupIntroPlan = StartupIntroPolicy.planStartupIntro(
        motion = motion,
        reduceMotion = reduceMotion,
        coldStart = coldStart,
        plainLaunch = plainLaunch,
        alreadyShownInProcess = alreadyShownInProcess,
        splashExitSupported = splashExitSupported,
    )

    @Test
    fun `default is the gentle ring`() {
        assertEquals(StartupMotion.RING, StartupMotion.DEFAULT)
    }

    @Test
    fun `cold launcher start draws ring and hands over the system splash exit`() {
        val result = planFor()

        assertFalse(result.isEmpty)
        assertEquals(StartupMotion.RING, result.overlay)
        assertTrue(result.splashExitAnimation)
    }

    @Test
    fun `off turns both layers off`() {
        val result = planFor(motion = StartupMotion.OFF)

        assertTrue(result.isEmpty)
        assertNull(result.overlay)
        assertFalse(result.splashExitAnimation)
    }

    @Test
    fun `reduced motion skips everything instead of freezing a still frame`() {
        val result = planFor(motion = StartupMotion.DOTS, reduceMotion = true)

        assertTrue(result.isEmpty)
    }

    @Test
    fun `recreated activity never replays the intro`() {
        // 配置重建 / 从后台被系统回收后重建：savedInstanceState 不为空
        assertTrue(planFor(coldStart = false).isEmpty)
    }

    @Test
    fun `share notification and link launches are left alone`() {
        assertTrue(planFor(plainLaunch = false).isEmpty)
    }

    @Test
    fun `a second activity in the same process does not replay`() {
        assertTrue(planFor(alreadyShownInProcess = true).isEmpty)
    }

    @Test
    fun `pre android 12 keeps the compose overlay only`() {
        val result = planFor(splashExitSupported = false)

        assertFalse(result.splashExitAnimation)
        assertEquals(StartupMotion.RING, result.overlay)
    }

    @Test
    fun `dots selection is honoured`() {
        assertEquals(StartupMotion.DOTS, planFor(motion = StartupMotion.DOTS).overlay)
    }

    @Test
    fun `plain launch detection`() {
        assertTrue(StartupIntroPolicy.isPlainLauncherLaunch(action = null, hasDeepLinkExtras = false))
        assertTrue(
            StartupIntroPolicy.isPlainLauncherLaunch(
                action = "android.intent.action.MAIN",
                hasDeepLinkExtras = false,
            )
        )
        assertFalse(
            StartupIntroPolicy.isPlainLauncherLaunch(
                action = "android.intent.action.SEND",
                hasDeepLinkExtras = false,
            )
        )
        assertFalse(
            StartupIntroPolicy.isPlainLauncherLaunch(
                action = "android.intent.action.VIEW",
                hasDeepLinkExtras = false,
            )
        )
        // 快捷方式来的 action 也是 MAIN，但带了会话/工件 extra
        assertFalse(
            StartupIntroPolicy.isPlainLauncherLaunch(
                action = "android.intent.action.MAIN",
                hasDeepLinkExtras = true,
            )
        )
    }

    @Test
    fun `system animations off is treated as reduced motion`() {
        assertTrue(StartupIntroPolicy.isReducedMotion(0f))
        assertFalse(StartupIntroPolicy.isReducedMotion(0.5f))
        assertFalse(StartupIntroPolicy.isReducedMotion(1f))
    }

    @Test
    fun `unreadable stored value falls back to the default`() {
        assertEquals(StartupMotion.RING, StartupMotion.fromWire(null))
        assertEquals(StartupMotion.RING, StartupMotion.fromWire(""))
        assertEquals(StartupMotion.RING, StartupMotion.fromWire("paper"))
    }

    @Test
    fun `stored value is trimmed and case insensitive`() {
        assertEquals(StartupMotion.OFF, StartupMotion.fromWire(" off "))
        assertEquals(StartupMotion.DOTS, StartupMotion.fromWire("DOTS"))
        assertEquals(StartupMotion.RING, StartupMotion.fromWire("Ring"))
    }

    @Test
    fun `wire names round trip`() {
        StartupMotion.entries.forEach { motion ->
            assertEquals(motion, StartupMotion.fromWire(motion.wireName))
        }
    }

    @Test
    fun `gate lets only the first caller through`() {
        StartupIntroGate.reset()

        assertTrue(StartupIntroGate.claim())
        assertFalse(StartupIntroGate.claim())
    }
}
