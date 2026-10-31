package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkReleaseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `GitHub fork release uses its APK and ignores checksum assets`() {
        val payload = """{
          "tag_name":"v3.1.1-fork.39", "published_at":"2026-11-01T00:00:00Z",
          "body":"SSH and rendering updates", "draft":false,
          "assets":[
            {"name":"rikkahub-3.1.1-fork.39.apk","browser_download_url":"https://github.com/682012/rikkahub/releases/download/v3.1.1-fork.39/rikkahub-3.1.1-fork.39.apk","size":19214408},
            {"name":"SHA256SUMS","browser_download_url":"https://example.test/checksums","size":100}
          ]
        }"""
        val info = json.decodeFromString<ForkRelease>(payload).toUpdateInfo()
        assertEquals("3.1.1-fork.39", info.version)
        assertEquals(1, info.downloads.size)
        assertEquals("18.3 MiB", info.downloads.single().size)
        assertTrue(Version.compare(info.version, "3.1.1-fork.38") > 0)
        assertTrue(Version.compare("3.1.1-fork.38", "3.1.1-fork.39") < 0)
    }

    @Test
    fun `an upstream stable release cannot become a fork update`() {
        assertThrows(IllegalArgumentException::class.java) {
            ForkRelease("3.1.1", "2026-10-24T12:03:22Z").toUpdateInfo()
        }
    }

    @Test
    fun `release without an APK is not offered as an update`() {
        assertThrows(IllegalArgumentException::class.java) {
            ForkRelease("v3.1.1-fork.40", "2026-11-01T00:00:00Z").toUpdateInfo()
        }
    }
}
