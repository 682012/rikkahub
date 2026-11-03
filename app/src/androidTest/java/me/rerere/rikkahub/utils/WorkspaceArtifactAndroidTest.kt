package me.rerere.rikkahub.utils

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import me.rerere.rikkahub.ui.pages.extensions.workspace.applyWorkspaceArtifactPreviewSettings
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备侧验证：工作区产物只能用 FileProvider 的 content:// + 临时读授权交给外部应用，
 * HTML 预览的 WebSettings 必须收死。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceArtifactAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 和 WorkspaceArtifactPage 里 exportArtifactToCache 落点一致：cacheDir 下，FileProvider 已声明 cache-path */
    private fun cacheArtifact(name: String): File {
        val dir = File(context.cacheDir, "workspace_artifact").apply { mkdirs() }
        return File(dir, name).apply { writeText("hello") }
    }

    @Test
    fun fileProviderUsesContentSchemeForCacheArtifact() {
        val file = cacheArtifact("报告 1.txt")
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        // 不允许把宿主路径塞进 URI
        assertFalse(uri.toString().contains(context.cacheDir.absolutePath))
    }

    @Test
    fun fileProviderRejectsFilesOutsideConfiguredPaths() {
        val outside = File("/data/local/tmp/artifact_probe.txt")
        val threw = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outside)
        }.isFailure
        assertTrue("FileProvider 必须拒绝不在 file_paths.xml 里的目录", threw)
    }

    @Test
    fun mimeTypeMappingForArtifacts() {
        assertEquals("image/png", mimeTypeOf("/workspace/chart.png"))
        assertEquals("application/pdf", mimeTypeOf("/workspace/报告.pdf"))
        assertEquals("text/html", mimeTypeOf("/workspace/index.html"))
        assertEquals("*/*", mimeTypeOf("/workspace/README"))
    }

    @Test
    fun htmlPreviewSettingsKeepScriptsAndLocalFilesOff() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val webView = WebView(context)
        try {
            val settings = webView.settings
            settings.applyWorkspaceArtifactPreviewSettings()
            assertFalse("预览必须禁脚本", settings.javaScriptEnabled)
            assertFalse("预览不能让 HTML 读宿主文件", settings.allowFileAccess)
            assertFalse("预览不能让 HTML 读 content://", settings.allowContentAccess)
            assertTrue("预览不能自动请求网络资源", settings.blockNetworkLoads)
            assertFalse("预览不做文件级的跨域放行", settings.allowFileAccessFromFileURLs)
            assertFalse("预览不做全局跨域放行", settings.allowUniversalAccessFromFileURLs)
        } finally {
            webView.destroy()
        }
        }
    }

    @Test
    fun externalOpenIntentCarriesOnlyReadGrant() {
        // 行为契约：交给外部应用的 Intent 必须是 content:// + FLAG_GRANT_READ_URI_PERMISSION，
        // 不带 persistable 授权，也不用 file://
        val file = cacheArtifact("export.txt")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertEquals("content", intent.data?.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun workspaceLinkParsingIsSharedWithUiLayer() {
        val link = WorkspaceArtifactLink.parse("sandbox:/workspace/%E6%8A%A5%E5%91%8A%201.pdf")
        assertTrue(link is WorkspaceArtifactLink.WorkspaceFile)
        assertEquals("/workspace/报告 1.pdf", (link as WorkspaceArtifactLink.WorkspaceFile).path)
        assertEquals(WorkspaceArtifactKind.PDF, WorkspaceArtifactLink.kindOf(link.path))
        assertTrue(Uri.parse("content://x/y").scheme == "content")
    }
}
