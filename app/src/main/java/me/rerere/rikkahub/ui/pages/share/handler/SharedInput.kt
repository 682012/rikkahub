package me.rerere.rikkahub.ui.pages.share.handler

import android.content.Intent
import android.net.Uri
import java.net.URLDecoder

/**
 * Inbound sharing / open-with handling.
 *
 * Only what the user explicitly shared is used: the intent's own text payload, its clip data and its
 * content URIs. Nothing here interprets URI query/fragment, resolves file paths, browses providers or
 * trims the payload silently - the text is always carried over in full.
 */
object SharedInput {

    /** Attachments kept from a single intent. Further ones are reported by the UI, never dropped silently. */
    const val MAX_ATTACHMENTS = 12

    /** Hard ceiling on how many candidate URIs are inspected, so a huge malicious intent stays cheap. */
    private const val MAX_SCANNED_URIS = 64

    /**
     * Subject becomes a leading line only when the body does not already carry it: some senders put the
     * title in both fields, and copying it twice makes the shared text look duplicated to the model.
     */
    fun normalizeText(text: String, subject: String?): String {
        val title = subject?.trim().orEmpty()
        return when {
            title.isEmpty() -> text
            text.isEmpty() -> title
            text.contains(title) -> text
            else -> "$title\n\n$text"
        }
    }

    /**
     * Link-only shares (browsers, some readers) hand the URL over as data/clip instead of EXTRA_TEXT.
     * Such links are appended once when missing, so a URL is never lost and never doubled.
     */
    fun appendMissingUrls(body: String, urls: List<String>): String {
        val extra = urls.map(String::trim).filter { it.isNotEmpty() && !body.contains(it) }.distinct()
        if (extra.isEmpty()) return body
        return (listOf(body).filter(String::isNotEmpty) + extra).joinToString("\n\n")
    }

    /**
     * True for authorities owned by this app. An inbound intent must never be able to point back at our
     * own providers (file provider, documents provider, ...): this process can reach them without any
     * grant, so accepting such a URI would let another app exfiltrate private app files by proxy.
     */
    fun isOwnAuthority(authority: String?, packageName: String): Boolean {
        val normalized = authority?.trim()?.lowercase().orEmpty()
        val ownPackage = packageName.trim().lowercase()
        if (normalized.isEmpty() || ownPackage.isEmpty()) return false
        return normalized == ownPackage || normalized.startsWith("$ownPackage.")
    }

    /** content:// only - raw file paths and http(s) links are never opened as files. */
    fun isAllowedUri(uri: Uri, packageName: String): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        val authority = uri.authority
        if (authority.isNullOrBlank()) return false
        return !isOwnAuthority(authority, packageName)
    }

    /** Human readable attachment name, derived from the URI only (no provider query, no file path). */
    fun displayName(uriString: String): String {
        val withoutQuery = uriString.substringBefore('?').substringBefore('#').trimEnd('/')
        val segment = withoutQuery.substringAfterLast('/')
        val decoded = runCatching {
            // Escape '+' first: it is a legal file name character, not a space.
            URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        }.getOrElse { segment }
        return decoded.ifBlank { withoutQuery.substringAfter(':').trimStart('/') }
    }

    /** Full text of the share: EXTRA_PROCESS_TEXT, EXTRA_TEXT or clip text, plus link-only payloads. */
    fun readText(intent: Intent): String {
        val text = runCatching {
            if (intent.action == Intent.ACTION_PROCESS_TEXT) {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            } else {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            }
        }.getOrNull() ?: runCatching {
            intent.clipData?.let { clip ->
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString("\n\n")
            }
        }.getOrNull().orEmpty()
        val subject = runCatching { intent.getStringExtra(Intent.EXTRA_SUBJECT) }.getOrNull()
        val body = normalizeText(text, subject)
        val links = allUris(intent)
            .filter { it.scheme?.lowercase() in LINK_SCHEMES }
            .map(Uri::toString)
        return appendMissingUrls(body, links)
    }

    /**
     * Content URIs offered by the intent. Our own providers are refused, so a third-party app cannot
     * make us read private app files on its behalf.
     */
    fun readUris(intent: Intent, packageName: String): List<Uri> = allUris(intent)
        .filter { isAllowedUri(it, packageName) }
        .distinctBy(Uri::toString)
        .take(MAX_SCANNED_URIS)

    @Suppress("DEPRECATION")
    private fun allUris(intent: Intent): List<Uri> {
        val found = LinkedHashMap<String, Uri>()
        fun offer(uri: Uri?) {
            if (uri != null) found.putIfAbsent(uri.toString(), uri)
        }
        runCatching { offer(intent.data) }
        runCatching {
            when (val raw = intent.extras?.get(Intent.EXTRA_STREAM)) {
                is Uri -> offer(raw)
                is Collection<*> -> raw.forEach { offer(it as? Uri) }
                is Array<*> -> raw.forEach { offer(it as? Uri) }
            }
        }
        runCatching {
            intent.clipData?.let { clip -> repeat(clip.itemCount) { offer(clip.getItemAt(it).uri) } }
        }
        return found.values.toList()
    }

    private val LINK_SCHEMES = setOf("http", "https")
}
