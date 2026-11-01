package me.rerere.rikkahub.utils

/**
 * Our short rN suffix is a revision number, not a SemVer alphabetic identifier.
 * Normalize only for update ordering: r2 < r10, while the display stays 3.1.1-r2.
 * The shared SemVer implementation remains unchanged. For the same upstream core,
 * rN succeeds the old fork.N series; a newer upstream core still takes precedence.
 */
internal fun compareForkVersions(left: String, right: String): Int =
    Version.compare(left.normalizedRevision(), right.normalizedRevision())

private val shortRevision = Regex("""^(\d+\.\d+\.\d+)-r(\d+)$""")

private fun String.normalizedRevision(): String {
    val match = shortRevision.matchEntire(this) ?: return this
    return "${match.groupValues[1]}-r.${match.groupValues[2]}"
}
