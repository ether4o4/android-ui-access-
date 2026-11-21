package com.ether4o4.odcm.vault

import org.json.JSONArray
import org.json.JSONObject

/** Bounded reference payload shared by chat and device tasks; never appended as system instructions. */
object VaultReference {
    const val CONTEXT_CHARS = 6500

    fun render(hits: List<VaultHit>, maxChars: Int = 12_000, excerptChars: Int = 1800,
               coreExcerptChars: Int = excerptChars): String {
        val result = JSONArray()
        var used = 2 // []
        for (hit in hits.distinctBy { it.path to it.offset }) {
            val row = JSONObject().put("path", hit.path.take(500)).put("offset", hit.offset)
                .put("index_status", hit.status.take(200)).put("kind", hit.knowledge.kind)
                .put("knowledge_status", hit.knowledge.status).put("updated", hit.knowledge.updated)
                .put("source", hit.knowledge.source.take(500))
            val length = if (hit.path in setOf("ODCM/00-Core/INDEX.md", "00-Core/INDEX.md")) coreExcerptChars else excerptChars
            var excerpt = hit.excerpt.take(length)
            fun encodeRow(): String = row.put("excerpt", excerpt).put("excerpt_truncated", excerpt.length < hit.excerpt.length)
                .put("next_offset", if (excerpt.length < hit.excerpt.length) hit.offset + excerpt.length else JSONObject.NULL).toString()
            var encoded = encodeRow()
            val available = maxChars.coerceAtLeast(2) - used - if (result.length() > 0) 1 else 0
            while (encoded.length > available && excerpt.isNotEmpty()) {
                excerpt = excerpt.take(excerpt.length / 2)
                encoded = encodeRow()
            }
            if (encoded.length > available || (excerpt.isEmpty() && hit.excerpt.isNotEmpty())) continue
            result.put(row)
            used += encoded.length + if (result.length() > 1) 1 else 0
        }
        return result.toString()
    }
}
