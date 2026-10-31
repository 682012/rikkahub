package me.rerere.rikkahub.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

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
        require(version.contains("-fork.")) { "The release is not a RikkaHub fork build" }
        val downloads = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }.map {
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
