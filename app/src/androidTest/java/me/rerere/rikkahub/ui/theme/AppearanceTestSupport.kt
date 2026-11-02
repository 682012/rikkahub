package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertTrue

/**
 * 外观测试的素材与截图工具。
 *
 * 壁纸都是现场生成的 PNG 文件，走的是生产同一条 Coil 解码路径，不依赖网络和相册。
 * 截图同时写进 getExternalFilesDir 和 /data/local/tmp/rk-r6-appearance：
 * UTP 跑完会卸载 debug 包（连 filesDir 一起没），tmp 那份留给主代理回收。
 */
object AppearanceTestSupport {

    private const val W = 240
    private const val H = 480
    private const val SHOT_DIR = "rk-r6-shots"
    private const val EXPORT_DIR = "/data/local/tmp/rk-r6-appearance"

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 纯色壁纸：用来分辨"会话自己的助手背景"和设置里当前选中的助手。 */
    fun solid(name: String, color: Int): String = write(name) { canvas, paint ->
        paint.color = color
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
    }

    /**
     * 极亮 / 极暗壁纸：主体是纯白或纯黑，左侧中部压一块纯红。
     * 红块用来证明这张图真的被画上去了，而不是被不透明页面整块盖住。
     */
    fun extreme(name: String, bright: Boolean): String = write(name) { canvas, paint ->
        paint.color = if (bright) AndroidColor.WHITE else AndroidColor.BLACK
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.color = AndroidColor.RED
        canvas.drawRect(W * 0.15f, H * 0.42f, W * 0.35f, H * 0.58f, paint)
    }

    /** 噪声壁纸：每 12px 一块随机色，中间同样压红块，模拟真实照片那种"忙"的底图。 */
    fun noise(name: String): String = write(name) { canvas, paint ->
        val random = Random(42)
        var y = 0
        while (y < H) {
            var x = 0
            while (x < W) {
                paint.color = AndroidColor.rgb(
                    20 + random.nextInt(200),
                    20 + random.nextInt(200),
                    20 + random.nextInt(200),
                )
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + 12).toFloat(), (y + 12).toFloat(), paint)
                x += 12
            }
            y += 12
        }
        paint.color = AndroidColor.RED
        canvas.drawRect(W * 0.15f, H * 0.42f, W * 0.35f, H * 0.58f, paint)
    }

    private fun write(name: String, draw: (Canvas, Paint) -> Unit): String {
        val dir = File(context.cacheDir, "rk-r6-wallpapers").apply { mkdirs() }
        val file = File(dir, "$name.png")
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), Paint(Paint.ANTI_ALIAS_FLAG))
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        assertTrue("壁纸没写出来: ${file.absolutePath}", file.length() > 0)
        return Uri.fromFile(file).toString()
    }

    /** 按相对坐标取一个像素（0..1），越界自动夹到边缘。 */
    fun sample(image: ImageBitmap, x: Float, y: Float): Color {
        val pixels = image.toPixelMap()
        val px = (image.width * x).toInt().coerceIn(0, image.width - 1)
        val py = (image.height * y).toInt().coerceIn(0, image.height - 1)
        return pixels[px, py]
    }

    fun assertNear(actual: Color, expected: Color, tolerance: Float, message: String) {
        val delta = maxOf(
            kotlin.math.abs(actual.red - expected.red),
            kotlin.math.abs(actual.green - expected.green),
            kotlin.math.abs(actual.blue - expected.blue),
        )
        assertTrue("$message（实际 $actual，期望 $expected）", delta <= tolerance)
    }

    fun capture(rule: ComposeTestRule, name: String): File =
        export(rule.onRoot().captureToImage().asAndroidBitmap(), name)

    /** 对话框是独立窗口，root 会有多个，只能按对话框节点截。 */
    fun capture(rule: ComposeTestRule, node: SemanticsNodeInteraction, name: String): File =
        export(node.captureToImage().asAndroidBitmap(), name)

    private fun export(bitmap: Bitmap, name: String): File {
        val dir = File(context.cacheDir, SHOT_DIR).apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        assertTrue("截图 $name 是空的", file.length() > 0)
        // The external ADB runner exports these after instrumentation. UI assertions do not depend
        // on UiAutomation's command tokenizer, which is not a POSIX shell.
        return file
    }
}
