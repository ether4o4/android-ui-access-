package com.ether4o4.odcm.vault

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Bounded reference payload shared by chat and device tasks; never appended as system instructions. */
object VaultReference {
    const val CONTEXT_CHARS = 6500
    private val corePaths = setOf("ODCM/00-Core/INDEX.md", "00-Core/INDEX.md")
    private val words = Regex("[\\p{L}\\p{N}_]+")

    fun render(hits: List<VaultHit>, maxChars: Int = 12_000, excerptChars: Int = 1800,
               coreExcerptChars: Int = excerptChars, query: String = ""): String {
        val result = JSONArray()
        var used = 2 // []
        for (hit in hits.distinctBy { it.path to it.offset }) {
            val row = JSONObject().put("path", hit.path.take(500)).put("offset", hit.offset)
                .put("index_status", hit.status.take(200)).put("kind", hit.knowledge.kind)
                .put("knowledge_status", hit.knowledge.status).put("updated", hit.knowledge.updated)
                .put("source", hit.knowledge.source.take(500))
            val core = hit.path in corePaths
            var length = if (core) coreExcerptChars else excerptChars
            var window = excerptWindow(hit.excerpt, if (core) "" else query, length)
            fun encodeRow(): String {
                val truncated = window.first > 0 || window.first + window.second.length < hit.excerpt.length
                return row.put("excerpt", window.second).put("offset", hit.offset + window.first)
                    .put("excerpt_truncated", truncated)
                    .put("next_offset", if (truncated) hit.offset + window.first + window.second.length else JSONObject.NULL)
                    .put("previous_offset", if (window.first > 0) hit.offset else JSONObject.NULL).toString()
            }
            var encoded = encodeRow()
            val available = maxChars.coerceAtLeast(2) - used - if (result.length() > 0) 1 else 0
            while (encoded.length > available && window.second.isNotEmpty()) {
                length /= 2
                window = excerptWindow(hit.excerpt, if (core) "" else query, length)
                encoded = encodeRow()
            }
            if (encoded.length > available || (window.second.isEmpty() && hit.excerpt.isNotEmpty())) continue
            result.put(row)
            used += encoded.length + if (result.length() > 1) 1 else 0
        }
        return result.toString()
    }

    /** Keep the matching passage. UTF-16 offsets remain compatible with VaultIndex.read. */
    private fun excerptWindow(text: String, query: String, limit: Int): Pair<Int, String> {
        if (limit <= 0) return 0 to ""
        if (text.length <= limit) return 0 to text
        val terms = VaultText.tokens(query).toSet()
        // A longer matched token is usually more specific than a common short word in a natural query.
        val match = if (terms.isEmpty()) null else words.findAll(text)
            .filter { it.value.lowercase(Locale.ROOT) in terms }.maxByOrNull { it.value.length }
        var start = if (match == null) 0 else (match.range.first - limit / 4).coerceIn(0, text.length - limit)
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
        var end = (start + limit).coerceAtMost(text.length)
        if (end > start && text[end - 1].isHighSurrogate()) end--
        return start to text.substring(start, end)
    }
}
