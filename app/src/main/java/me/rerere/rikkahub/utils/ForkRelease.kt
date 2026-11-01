package me.rerere.rikkahub.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

// Stable releases of this maintained branch. Upstream and nightly builds are excluded.
private val FORK_VERSION = Regex("""\d+\.\d+\.\d+-(?:fork\.\d+|r[1-9]\d*)""")

/** GitHub's release response, converted to the existing update UI contract. */
@Serializable
internal data class ForkRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("published_at") val publishedAt: String,
    val body: String? = null,
    val assets: List<ForkReleaseAsset> = emptyList(),
) {
    fun toUpdateInfo(): UpdateInfo {
        val version = tagName.removePrefix("v")
        require(FORK_VERSION.matches(version)) { "The release is not a RikkaHub maintained build" }
        val apkName = Regex("^rikkahub-${Regex.escape(version)}(?:-(?:arm64-v8a|x86_64|universal))?\\.apk$")
        val downloads = assets.filter {
            apkName.matches(it.name) && it.size > 0 &&
                it.downloadUrl == "https://github.com/682012/rikkahub/releases/download/$tagName/${it.name}"
        }.map {
            UpdateDownload(
                name = it.name,
                url = it.downloadUrl,
                size = String.format(Locale.ROOT, "%.1f MiB", it.size / 1048576.0),
            )
        }
        require(downloads.isNotEmpty()) { "The fork release has no APK attached" }
        return UpdateInfo(version, publishedAt, body.orEmpty(), downloads)
    }
}

@Serializable
internal data class ForkReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long,
)
