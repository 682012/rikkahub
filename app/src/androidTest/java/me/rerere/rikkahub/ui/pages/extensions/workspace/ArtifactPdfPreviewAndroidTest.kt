package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真机/模拟器上对真实 PDF 跑一遍 [PdfArtifactSession]：
 * 页数、按可见区域收窄的渲染尺寸、位图回收前后的行为、非 PDF 文件的失败归类。
 *
 * 这里不测 Compose 布局（预览 UI 的像素断言没意义，容易变成"看着绿其实没验证"）。
 */
class ArtifactPdfPreviewAndroidTest {

    @Test
    fun rendersRealPdfPageWithinResolutionBudget() {
        val file = writePdf()
        PdfArtifactSession(file).use { session ->
            assertEquals(1, session.pageCount)

            // MediaBox 200x100，可见区域 800x800：按宽度收窄、受放大倍数上限约束
            val bitmap = session.render(0, 800, 800)
            try {
                assertEquals(600, bitmap.width)
                assertEquals(300, bitmap.height)
                assertTrue(bitmap.width * bitmap.height <= 4_000_000)

                // 白底 + 黑色字形：必须真的有内容画上去
                var ink = false
                var x = 0
                while (x < bitmap.width && !ink) {
                    var y = 0
                    while (y < bitmap.height) {
                        if (bitmap.getPixel(x, y) != Color.WHITE) {
                            ink = true
                            break
                        }
                        y += 4
                    }
                    x += 4
                }
                assertTrue("渲染结果里应当有文字像素", ink)
            } finally {
                bitmap.recycle()
            }

            // 小窗口：等比缩小到窗口宽度里
            val small = session.render(0, 100, 100)
            try {
                assertEquals(100, small.width)
                assertEquals(50, small.height)
            } finally {
                small.recycle()
            }
        }
    }

    @Test
    fun pageOutOfRangeAndClosedSessionAreRejected() {
        val file = writePdf()
        val session = PdfArtifactSession(file)
        try {
            assertThrows(java.io.IOException::class.java) { session.render(5, 400, 400) }
        } finally {
            session.close()
        }
        assertEquals(0, session.pageCount)
        assertThrows(IllegalStateException::class.java) { session.render(0, 400, 400) }
    }

    @Test
    fun nonPdfFileFailsWithCorruptClassification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "artifact-preview-not-a-pdf.pdf")
        file.writeBytes("PK\u0003\u0004 definitely not a pdf".toByteArray(Charsets.US_ASCII))
        try {
            val error = runCatching { PdfArtifactSession(file).use { it.pageCount } }.exceptionOrNull()
            assertNotNull("非 PDF 文件不应该被当成可预览文档打开", error)
            assertEquals(PdfPreviewFailure.CORRUPT, pdfPreviewFailureOf(error!!))
        } finally {
            file.delete()
        }
    }

    private fun writePdf(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "artifact-preview-sample.pdf")
        file.writeBytes(buildMinimalPdf())
        return file
    }

    /** 手写一个合法的最小 PDF（1 页 + Helvetica 文本 + 正确 xref 偏移），不依赖任何第三方库 */
    private fun buildMinimalPdf(): ByteArray {
        val content = "BT /F1 24 Tf 20 40 Td (Hello R7) Tj ET"
        val bodies = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )

        val document = StringBuilder("%PDF-1.4\n")
        val offsets = IntArray(bodies.size)
        bodies.forEachIndexed { index, body ->
            offsets[index] = document.length
            document.append("${index + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xrefOffset = document.length
        document.append("xref\n0 ${bodies.size + 1}\n")
        document.append("0000000000 65535 f \n")
        offsets.forEach { document.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        document.append("trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R >>\n")
        document.append("startxref\n").append(xrefOffset).append("\n%%EOF\n")
        return document.toString().toByteArray(Charsets.US_ASCII)
    }
}
