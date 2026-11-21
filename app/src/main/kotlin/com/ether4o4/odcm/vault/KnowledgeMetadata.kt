package com.ether4o4.odcm.vault

import org.json.JSONObject
import java.util.Locale

/** A deliberately small frontmatter contract, not a general YAML interpreter. */
data class KnowledgeMetadata(
    val kind: String = "",
    val status: String = "",
    val updated: String = "",
    val source: String = "",
) {
    fun json(): JSONObject = JSONObject().put("kind", kind).put("status", status)
        .put("updated", updated).put("source", source)

    fun priority(path: String): Int {
        val relative = path.removePrefix("ODCM/")
        val sourceNote = kind == "source" || relative.startsWith("90-Sources/")
        val compartment = when {
            sourceNote -> -20
            kind == "core" || kind == "preference" -> 14
            kind == "workflow" || kind == "project" -> 10
            kind == "knowledge" || kind == "app-map" -> 6
            else -> 0
        }
        val evidence = when (status) {
            "confirmed" -> 8
            "observed" -> 4
            "candidate", "unverified" -> -4
            "failed", "conflicted" -> -12
            "superseded" -> -24
            else -> 0
        }
        return compartment + evidence
    }

    companion object {
        private val kinds = setOf("core", "preference", "project", "knowledge", "workflow", "app-map", "session", "source")
        private val statuses = setOf("confirmed", "observed", "candidate", "unverified", "superseded", "failed", "conflicted")
        private val fieldsAllowed = setOf("odcm_schema", "kind", "status", "updated", "source")

        fun parse(markdown: String): KnowledgeMetadata {
            val lines = markdown.removePrefix("\uFEFF").take(8192).lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trim() != "---") return KnowledgeMetadata()
            val fields = mutableMapOf<String, String>()
            var closed = false
            for (line in lines) {
                if (line.trim() == "---") { closed = true; break }
                // Indented/list/nested YAML is intentionally not interpreted as top-level metadata.
                if (line.startsWith(' ') || line.startsWith('\t')) continue
                val key = line.substringBefore(':', "").trim()
                if (key in fieldsAllowed)
                    fields[key] = scalar(line.substringAfter(':').trim())
            }
            if (!closed || fields["odcm_schema"] != "1") return KnowledgeMetadata()
            val kind = fields["kind"].orEmpty().lowercase(Locale.ROOT).takeIf { it in kinds }.orEmpty()
            val status = fields["status"].orEmpty().lowercase(Locale.ROOT).takeIf { it in statuses } ?: "unverified"
            return KnowledgeMetadata(kind, status, fields["updated"].orEmpty().take(40), fields["source"].orEmpty().take(500))
        }

        fun fromJson(value: String?): KnowledgeMetadata = runCatching {
            if (value.isNullOrBlank()) return@runCatching KnowledgeMetadata()
            val obj = JSONObject(value)
            KnowledgeMetadata(obj.optString("kind"), obj.optString("status"), obj.optString("updated"), obj.optString("source"))
        }.getOrDefault(KnowledgeMetadata())

        private fun scalar(value: String): String = when {
            value.startsWith('"') && value.endsWith('"') -> runCatching {
                JSONObject("{\"value\":$value}").getString("value")
            }.getOrDefault("")
            value.startsWith('\'') && value.endsWith('\'') && value.length >= 2 -> value.substring(1, value.lastIndex).replace("''", "'")
            else -> value.take(1000)
        }
    }
}
