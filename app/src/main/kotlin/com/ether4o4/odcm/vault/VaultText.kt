package com.ether4o4.odcm.vault

import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/** Shared text rules. No model, network, or Obsidian-specific frontmatter required. */
object VaultText {
    const val MAX_CHARS = 8_000_000
    const val CHUNK_SIZE = 3000
    private val extensions = setOf(
        "md", "markdown", "txt", "text", "csv", "tsv", "json", "jsonl", "canvas", "yaml", "yml",
        "xml", "html", "htm", "log", "org", "rst", "ini", "toml", "sql", "py", "kt", "java",
        "js", "ts", "tsx", "jsx", "css", "sh", "ps1", "c", "cpp", "h", "rs", "go", "srt", "vtt",
    )
    fun isText(path: String, mime: String = ""): Boolean =
        path.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions || mime.startsWith("text/")

    fun tokens(text: String): List<String> = Regex("[\\p{L}\\p{N}_]+")
        .findAll(text.lowercase(Locale.ROOT)).map { it.value }.distinct().take(24).toList()

    // Only quoted tokens enter FTS, never user-supplied MATCH operators or SQL.
    fun matchQuery(text: String, all: Boolean = false): String =
        tokens(text).joinToString(if (all) " AND " else " OR ") { "\"$it\"" }

    fun chunks(text: String): Sequence<String> = sequence {
        var start = 0
        while (start < text.length) {
            var end = (start + CHUNK_SIZE).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOf('\n', end - 1)
                if (boundary > start + CHUNK_SIZE / 2) end = boundary + 1
                if (end > start && text[end - 1].isHighSurrogate()) end--
            }
            yield(text.substring(start, end))
            start = end
        }
    }

    fun read(input: InputStream): String {
        val reader = InputStreamReader(input, Charsets.UTF_8)
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val n = reader.read(buffer)
            if (n < 0) break
            require(out.length + n <= MAX_CHARS) { "Text exceeds 8 million characters; split this file into smaller notes." }
            out.append(buffer, 0, n)
        }
        return out.toString().removePrefix("\uFEFF")
    }

    fun safeSegment(value: String): String = value.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_")
        .trim().trim('.').take(90).ifBlank { "Untitled" }

    fun validPath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
        '\\' !in path && path.split('/').none { it.isBlank() || it == "." || it == ".." || it.any(Char::isISOControl) }

    fun score(path: String, body: String, query: String): Int {
        val p = path.lowercase(Locale.ROOT)
        val b = body.lowercase(Locale.ROOT)
        val ts = tokens(query)
        return ts.sumOf { (if (it in p) 8 else 0) + (if (it in b) 3 else 0) } +
            (if (query.isNotBlank() && query.lowercase(Locale.ROOT) in b) 15 else 0)
    }
}
