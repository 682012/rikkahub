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
    fun `short suffix release keeps its visible version and exact APK`() {
        val release = validRevision()
        val info = release.toUpdateInfo()
        assertEquals("3.1.1-r1", info.version)
        assertEquals("rikkahub-3.1.1-r1.apk", info.downloads.single().name)
        assertTrue(compareForkVersions(info.version, "3.1.1-fork.43") > 0)
    }

    @Test
    fun `all supported APK variants are offered but metadata is not`() {
        val release = validRevision()
        val names = listOf("rikkahub-3.1.1-r1.apk", "rikkahub-3.1.1-r1-arm64-v8a.apk",
            "rikkahub-3.1.1-r1-x86_64.apk", "rikkahub-3.1.1-r1-universal.apk", "SHA256SUMS")
        val assets = names.map { name -> ForkReleaseAsset(name, "$downloadBase/$name", 1024) }
        assertEquals(4, release.copy(assets = assets).toUpdateInfo().downloads.size)
    }

    @Test
    fun `nightly upstream and abandoned brand tags are rejected`() {
        for (tag in listOf("nightly", "3.1.1", "v3.1.1", "v1.0.0", "v3.1.1-mod.44",
            "v3.1.1-r0", "v3.1.1-r1-beta", "prefix-fork.44")) {
            assertThrows(tag, IllegalArgumentException::class.java) {
                validRevision().copy(tagName = tag).toUpdateInfo()
            }
        }
    }

    @Test
    fun `unrelated or mismatched assets cannot become an update`() {
        val release = validRevision()
        val good = release.assets.single()
        val invalid = listOf(
            good.copy(name = "nianyu-1.0.0.apk"),
            good.copy(name = "rikkahub-3.1.1-r2.apk"),
            good.copy(downloadUrl = "https://example.test/${good.name}"),
            good.copy(downloadUrl = good.downloadUrl.replace("/682012/", "/rikkahub/")),
            good.copy(downloadUrl = good.downloadUrl.replace("github.com", "github.com.example.test")),
            good.copy(downloadUrl = good.downloadUrl.replace("v3.1.1-r1/", "v3.1.1-r2/")),
            good.copy(downloadUrl = good.downloadUrl + "?redirect=other"),
            good.copy(size = 0),
        )
        for (asset in invalid) assertThrows(IllegalArgumentException::class.java) {
            release.copy(assets = listOf(asset)).toUpdateInfo()
        }
    }

    private val downloadBase = "https://github.com/682012/rikkahub/releases/download/v3.1.1-r1"

    private fun validRevision() = ForkRelease(
        "v3.1.1-r1", "2026-11-02T00:00:00Z", "Upstream identity and short revision",
        listOf(ForkReleaseAsset("rikkahub-3.1.1-r1.apk", "$downloadBase/rikkahub-3.1.1-r1.apk", 1048576)),
    )

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
