package me.rerere.rikkahub.startup

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 冷启动开场动效的选项。
 *
 * [OFF] 什么都不画；[RING] 一圈很轻的轨道环加一个沿环走的光点；[DOTS] 几颗光点从外圈向中心聚合。
 * 两种动效都用 Canvas 画，不引额外依赖。
 */
enum class StartupMotion {
    OFF,
    RING,
    DOTS,
    ;

    val wireName: String
        get() = when (this) {
            OFF -> "off"
            RING -> "ring"
            DOTS -> "dots"
        }

    companion object {
        /** 默认给一个最温和的：轨道环。 */
        val DEFAULT = RING

        /**
         * 从持久化的字符串还原。
         *
         * 空值、脏数据、以后版本才有的取值一律回落到 [DEFAULT]，
         * 这样升级或降级都不会因为一个读不懂的值变成"没有动效"。
         */
        fun fromWire(raw: String?): StartupMotion = when (raw?.trim()?.lowercase()) {
            OFF.wireName -> OFF
            DOTS.wireName -> DOTS
            RING.wireName -> RING
            else -> DEFAULT
        }
    }
}

/**
 * 一次冷启动要不要画开场，以及画哪一种。
 *
 * @property splashExitAnimation 是否接管系统 Splash 的退出动画（只有 Android 12+ 才有系统 Splash）
 * @property overlay 前景要画哪种动效；null 表示完全不叠前景
 */
data class StartupIntroPlan(
    val splashExitAnimation: Boolean,
    val overlay: StartupMotion?,
) {
    val isEmpty: Boolean
        get() = !splashExitAnimation && overlay == null

    companion object {
        val NONE = StartupIntroPlan(splashExitAnimation = false, overlay = null)
    }
}

/**
 * 开场动效的持久化。
 *
 * 单独一个 SharedPreferences 文件，不和 Settings 的 DataStore 混在一起：
 * 第一帧之前就要知道"画不画"，读这个小文件比等 DataStore 起来快，也不会因为设置还在加载而误判。
 * 键只有一个，读写都是一次字符串操作。
 */
object StartupPreferences {
    private const val PREFS_NAME = "r21.startup"
    private const val KEY_MOTION = "startup_motion"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readMotion(context: Context): StartupMotion =
        StartupMotion.fromWire(prefs(context).getString(KEY_MOTION, null))

    fun writeMotion(context: Context, motion: StartupMotion) {
        prefs(context).edit { putString(KEY_MOTION, motion.wireName) }
    }
}

/** 同一个进程里只演一次：配置重建、第二个 Activity 实例都不重播。 */
object StartupIntroGate {
    private val shown = AtomicBoolean(false)

    /** 第一次返回 true，之后都返回 false。 */
    fun claim(): Boolean = shown.compareAndSet(false, true)

    internal fun reset() {
        shown.set(false)
    }
}

/**
 * 纯决策：把设备状态和启动方式收成"画不画"。
 *
 * 这里不碰 Context / Intent，方便用 JVM 单测把每种启动方式都过一遍。
 * 调用方负责把 Intent 的 action 和 extra 先映射成 [isPlainLauncherLaunch] 的两个入参。
 */
object StartupIntroPolicy {
    /**
     * `Settings.Global` 里动画时长倍率的键。
     * SDK 常量是隐藏的，所以直接用字符串，行为等价。
     */
    const val ANIMATOR_DURATION_SCALE = "animator_duration_scale"

    private const val ACTION_MAIN = "android.intent.action.MAIN"

    /** 系统把动画关掉（倍率为 0）时，整段开场直接跳过，不留静态残影。 */
    fun isReducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale <= 0.1f

    /**
     * 只有从桌面图标点进来的普通启动才算"开场"时机。
     * 分享、通知、深链接、快捷方式带 extra 进来的时候一律不画，免得挡在用户本来就急着看的内容前面。
     */
    fun isPlainLauncherLaunch(action: String?, hasDeepLinkExtras: Boolean): Boolean {
        if (hasDeepLinkExtras) return false
        return action == null || action == ACTION_MAIN
    }

    fun planStartupIntro(
        motion: StartupMotion,
        reduceMotion: Boolean,
        coldStart: Boolean,
        plainLaunch: Boolean,
        alreadyShownInProcess: Boolean,
        splashExitSupported: Boolean,
    ): StartupIntroPlan {
        // 热启动 / 配置重建：内容是接着的，不该再演一遍
        if (!coldStart) return StartupIntroPlan.NONE
        // 分享 / 通知 / 深链接 / 快捷方式：不要挡在这些跳转前面
        if (!plainLaunch) return StartupIntroPlan.NONE
        // 同一进程已经演过
        if (alreadyShownInProcess) return StartupIntroPlan.NONE
        // 系统关了动画
        if (reduceMotion) return StartupIntroPlan.NONE
        if (motion == StartupMotion.OFF) return StartupIntroPlan.NONE

        return StartupIntroPlan(
            // Android 12 以下没有系统 Splash 的退出动画可用，只剩前景那一段
            splashExitAnimation = splashExitSupported,
            overlay = motion,
        )
    }
}

/** 读系统动画倍率；读不到（厂商改过、权限限制）就按 1 处理。 */
fun Context.animatorDurationScale(): Float = runCatching {
    Settings.Global.getFloat(contentResolver, StartupIntroPolicy.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)
