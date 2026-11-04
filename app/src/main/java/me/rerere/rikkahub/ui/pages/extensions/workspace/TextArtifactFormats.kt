package me.rerere.rikkahub.ui.pages.extensions.workspace

internal enum class TextArtifactFormat { SOURCE, MARKDOWN, TABLE, JSON, HTML, SVG }

internal fun textArtifactFormat(name: String): TextArtifactFormat = when (name.substringAfterLast('.').lowercase()) {
    "html", "htm", "xhtml" -> TextArtifactFormat.HTML
    "svg" -> TextArtifactFormat.SVG
    "md", "markdown", "mdown", "mkd" -> TextArtifactFormat.MARKDOWN
    "csv", "tsv" -> TextArtifactFormat.TABLE
    "json", "ipynb" -> TextArtifactFormat.JSON
    else -> TextArtifactFormat.SOURCE
}

internal data class DelimitedPreview(val rows: List<List<String>>, val truncated: Boolean)

/** RFC4180 quotes, escaped quotes and multiline cells; bounded UI rows and cell width. */
internal fun parseDelimitedPreview(text: String, delimiter: Char, maxRows: Int = 300, maxColumns: Int = 40): DelimitedPreview {
    require(maxRows > 0 && maxColumns > 0)
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var atStart = true
    var truncated = false
    var i = 0
    fun finishCell() {
        if (row.size < maxColumns) row.add(cell.toString()) else truncated = true
        cell.setLength(0); atStart = true
    }
    fun finishRow() { finishCell(); rows.add(row.toList()); row.clear() }
    while (i < text.length) {
        val c = text[i++]
        if (c == '"' && (quoted || atStart)) {
            if (quoted && i < text.length && text[i] == '"') { cell.append('"'); i++ }
            else quoted = !quoted
            atStart = false
        } else if (!quoted && c == delimiter) {
            finishCell()
        } else if (!quoted && (c == '\n' || c == '\r')) {
            if (c == '\r' && i < text.length && text[i] == '\n') i++
            finishRow()
            if (rows.size >= maxRows) { truncated = truncated || i < text.length; break }
        } else {
            if (cell.length < 4096) cell.append(c) else truncated = true
            atStart = false
        }
    }
    if (rows.size < maxRows && (cell.isNotEmpty() || row.isNotEmpty() || !atStart)) finishRow()
    return DelimitedPreview(rows, truncated)
}
