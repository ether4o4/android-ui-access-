package com.ether4o4.odcm.guide

/**
 * Reads and writes a note as a Markdown file with a small YAML-subset frontmatter block.
 * Obsidian shows the block as properties; we parse only what we write (strings, ints, and
 * simple `[a, b]` lists), so there is no YAML library and nothing reflective at runtime.
 */
object Frontmatter {

    fun serialize(note: GuideNote): String = buildString {
        append("---\n")
        line("object_id", note.id)
        line("object_type", note.type.name.lowercase())
        line("object_status", note.status.name.lowercase())
        if (note.title.isNotEmpty()) line("title", note.title)
        note.app?.let { line("app", it) }
        note.screen?.let { line("screen", it) }
        if (note.triggers.isNotEmpty()) listLine("triggers", note.triggers)
        note.elementId?.let { line("element_id", it) }
        note.elementText?.let { line("element_text", it) }
        note.lastCoords?.let { listLine("last_coords", listOf(it.first.toString(), it.second.toString())) }
        line("successes", note.successes.toString())
        line("failures", note.failures.toString())
        if (note.updated.isNotEmpty()) line("updated", note.updated)
        append("---\n")
        if (note.body.isNotEmpty()) { append('\n'); append(note.body.trimEnd()); append('\n') }
    }

    fun parse(text: String): GuideNote? {
        val norm = text.replace("\r\n", "\n")
        if (!norm.startsWith("---")) return null
        val end = norm.indexOf("\n---", 3)
        if (end < 0) return null
        val header = norm.substring(norm.indexOf('\n') + 1, end)
        val body = norm.substring(end + 4).trim('\n')

        val map = HashMap<String, String>()
        val lists = HashMap<String, List<String>>()
        for (raw in header.split('\n')) {
            val lineText = raw.trim()
            if (lineText.isEmpty() || lineText.startsWith("#")) continue
            val colon = lineText.indexOf(':')
            if (colon < 0) continue
            val key = lineText.substring(0, colon).trim()
            val value = lineText.substring(colon + 1).trim()
            if (value.startsWith("[") && value.endsWith("]")) {
                lists[key] = value.substring(1, value.length - 1)
                    .split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
            } else {
                map[key] = unquote(value)
            }
        }
        val id = map["object_id"]?.ifBlank { null } ?: return null
        val coords = lists["last_coords"]?.takeIf { it.size == 2 }
            ?.let { (it[0].toIntOrNull() ?: return@let null) to (it[1].toIntOrNull() ?: return@let null) }
        return GuideNote(
            id = id,
            type = NoteType.from(map["object_type"]),
            status = NoteStatus.from(map["object_status"]),
            title = map["title"].orEmpty(),
            app = map["app"],
            screen = map["screen"],
            triggers = lists["triggers"].orEmpty(),
            elementId = map["element_id"],
            elementText = map["element_text"],
            lastCoords = coords,
            successes = map["successes"]?.toIntOrNull() ?: 0,
            failures = map["failures"]?.toIntOrNull() ?: 0,
            updated = map["updated"].orEmpty(),
            body = body,
        )
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append(": ").append(quoteIfNeeded(value)).append('\n')
    }

    private fun StringBuilder.listLine(key: String, values: List<String>) {
        append(key).append(": [").append(values.joinToString(", ") { quoteIfNeeded(it) }).append("]\n")
    }

    private fun quoteIfNeeded(v: String): String =
        if (v.isEmpty() || v.any { it in ":[],#\"'" } || v != v.trim()) "\"" + v.replace("\"", "'") + "\"" else v

    private fun unquote(v: String): String =
        if (v.length >= 2 && (v.first() == '"' || v.first() == '\'') && v.last() == v.first())
            v.substring(1, v.length - 1) else v
}
