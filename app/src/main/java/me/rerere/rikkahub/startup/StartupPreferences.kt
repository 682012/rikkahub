package me.rerere.rikkahub.startup

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 冷启动开场动效的选项。
 *
 * 内建风格都是用 Canvas 画的，不引额外依赖：
 * [RIKKA] 六瓣风车花（默认）；[RING] 轨道环 + 沿环走的光点；[DOTS] 几颗光点向中心聚合；[AURORA] 一团很淡的柔光散开。
 * [CUSTOM] 不用内建画法，改放用户导入的图片/动图/小视频（见 [StartupCustomIntro]）；
 * 素材缺失或解码失败时回落到 [DEFAULT]，不会空着不画。
 */
enum class StartupMotion {
    OFF,
    RIKKA,
    RING,
    DOTS,
    AURORA,
    CUSTOM,
    ;

    val wireName: String
        get() = when (this) {
            OFF -> "off"
            RIKKA -> "rikka"
            RING -> "ring"
            DOTS -> "dots"
            AURORA -> "aurora"
            CUSTOM -> "custom"
        }

    /** 只有这些是能直接画出来的内建风格。 */
    val isBuiltIn: Boolean
        get() = this == OFF || this == RIKKA || this == RING || this == DOTS || this == AURORA

    companion object {
        /** 默认六花；已经保存的旧风格不迁移、不覆盖。 */
        val DEFAULT = RIKKA

        /**
         * 从持久化的字符串还原。
         *
         * 空值、脏数据、以后版本才有的取值一律回落到 [DEFAULT]，
         * 这样升级或降级都不会因为一个读不懂的值变成"没有动效"。
         */
        fun fromWire(raw: String?): StartupMotion = when (raw?.trim()?.lowercase()) {
            OFF.wireName -> OFF
            RIKKA.wireName -> RIKKA
            DOTS.wireName -> DOTS
            RING.wireName -> RING
            AURORA.wireName -> AURORA
            CUSTOM.wireName -> CUSTOM
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
    private const val KEY_CUSTOM_PATH = "startup_custom_path"
    private const val KEY_CUSTOM_KIND = "startup_custom_kind"
    private const val KEY_CUSTOM_DURATION = "startup_custom_duration"
    private const val KEY_CUSTOM_SCALE = "startup_custom_scale"
    private const val KEY_CUSTOM_FIT = "startup_custom_fit"

    /** 内建风格的时长（m3）。和自定义素材的时长分开存：换素材不会改掉内建那一段。 */
    private const val KEY_BUILTIN_DURATION = "startup_builtin_duration"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readMotion(context: Context): StartupMotion =
        StartupMotion.fromWire(prefs(context).getString(KEY_MOTION, null))

    fun writeMotion(context: Context, motion: StartupMotion) {
        prefs(context).edit { putString(KEY_MOTION, motion.wireName) }
    }

    /**
     * 读内建风格时长。
     *
     * 没存过 → [StartupIntroRules.BUILTIN_DURATION_MS]（默认可见的 1500ms）；
     * 存过但坏了 → 同样回落，并由 [StartupIntroRules.clampBuiltinDuration] 收进范围。
     */
    fun readBuiltinDuration(context: Context): Int =
        StartupIntroRules.clampBuiltinDuration(prefs(context).getInt(KEY_BUILTIN_DURATION, 0))

    fun writeBuiltinDuration(context: Context, durationMs: Int) {
        prefs(context).edit {
            putInt(KEY_BUILTIN_DURATION, StartupIntroRules.clampBuiltinDuration(durationMs))
        }
    }

    /**
     * 读自定义素材。这里只读几个字符串/整数，不碰文件本身：
     * 冷启动第一帧之前就要知道"画不画"，`File.exists()` 和目录遍历放主线程没必要。
     * 文件是否还在、能不能解码由绘制层在后台判断，失败就回落到内建风格。
     */
    fun readCustom(context: Context): StartupCustomIntro? {
        val prefs = prefs(context)
        val path = prefs.getString(KEY_CUSTOM_PATH, null)?.trim().orEmpty()
        if (path.isEmpty()) return null
        val kind = StartupMediaKind.fromWire(prefs.getString(KEY_CUSTOM_KIND, null)) ?: return null
        return StartupCustomIntro(
            path = path,
            kind = kind,
            durationMs = StartupIntroRules.clampDuration(prefs.getInt(KEY_CUSTOM_DURATION, 0), kind),
            scalePercent = StartupIntroRules.clampScale(prefs.getInt(KEY_CUSTOM_SCALE, 0)),
            fit = StartupFit.fromWire(prefs.getString(KEY_CUSTOM_FIT, null)),
        )
    }

    fun writeCustom(context: Context, custom: StartupCustomIntro) {
        prefs(context).edit {
            putString(KEY_CUSTOM_PATH, custom.path)
            putString(KEY_CUSTOM_KIND, custom.kind.wireName)
            putInt(KEY_CUSTOM_DURATION, StartupIntroRules.clampDuration(custom.durationMs, custom.kind))
            putInt(KEY_CUSTOM_SCALE, StartupIntroRules.clampScale(custom.scalePercent))
            putString(KEY_CUSTOM_FIT, custom.fit.wireName)
        }
    }

    /** 清除素材时同时把风格退回默认，避免"自定义但没素材"这种半坏状态留在设置里。 */
    fun clearCustom(context: Context) {
        prefs(context).edit {
            remove(KEY_CUSTOM_PATH)
            remove(KEY_CUSTOM_KIND)
            remove(KEY_CUSTOM_DURATION)
            remove(KEY_CUSTOM_SCALE)
            remove(KEY_CUSTOM_FIT)
            putString(KEY_MOTION, StartupMotion.DEFAULT.wireName)
        }
    }

    /** 只改时长/缩放/适配时用：素材本身不动。 */
    fun updateCustomTuning(context: Context, durationMs: Int, scalePercent: Int, fit: StartupFit) {
        val current = readCustom(context) ?: return
        writeCustom(
            context,
            current.copy(
                durationMs = StartupIntroRules.clampDuration(durationMs, current.kind),
                scalePercent = StartupIntroRules.clampScale(scalePercent),
                fit = fit,
            ),
        )
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

/**
 * 把"用户选了什么"收成一份可以直接画的场景。
 *
 * 纯函数，不碰 Context / 文件系统，JVM 单测覆盖每条回落路径：
 * - 选了关闭 → 什么都不画；
 * - 选了自定义但没有素材（没导入、上次导入失败、文件被系统清掉）→ 回落到默认内建风格；
 * - 时长、缩放一律收进安全范围，坏值绝不让画面停住。
 */
object StartupIntroSceneResolver {
    fun resolve(
        motion: StartupMotion,
        custom: StartupCustomIntro?,
        customDurationMs: Int = 0,
        customScalePercent: Int = StartupIntroRules.DEFAULT_SCALE,
        customFit: StartupFit = StartupFit.DEFAULT,
        builtinDurationMs: Int = 0,
    ): StartupIntroScene {
        // 内建那一段的时长：同一份设置同时供预览和冷启动使用（m3 起用户可调）。
        val builtinDuration = StartupIntroRules.clampBuiltinDuration(builtinDurationMs)
        if (motion == StartupMotion.OFF) {
            return StartupIntroScene(style = StartupMotion.OFF, custom = null, durationMs = builtinDuration)
        }
        if (motion != StartupMotion.CUSTOM) {
            return StartupIntroScene(style = motion, custom = null, durationMs = builtinDuration)
        }
        val path = custom?.path?.trim().orEmpty()
        val kind = custom?.kind
        if (path.isEmpty() || kind == null) {
            // 素材不可用：内建默认风格兜底，绝不留一层空白盖在聊天页上
            return StartupIntroScene(style = StartupMotion.DEFAULT, custom = null, durationMs = builtinDuration)
        }
        val duration = StartupIntroRules.clampDuration(
            if (customDurationMs > 0) customDurationMs else custom.durationMs,
            kind,
        )
        return StartupIntroScene(
            style = StartupMotion.DEFAULT,
            custom = StartupCustomIntro(
                path = path,
                kind = kind,
                durationMs = duration,
                scalePercent = StartupIntroRules.clampScale(
                    if (customScalePercent > 0) customScalePercent else custom.scalePercent
                ),
                fit = customFit,
            ),
            durationMs = duration,
        )
    }
}
