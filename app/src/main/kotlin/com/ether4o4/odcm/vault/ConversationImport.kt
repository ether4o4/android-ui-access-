package com.ether4o4.odcm.vault

import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.security.MessageDigest

data class ImportedConversation(val path: String, val markdown: String)

/** Converts export records locally; the original archive remains the authoritative copy. */
object ConversationImport {
    fun digest(text: String): String = digest(text.toByteArray(Charsets.UTF_8))
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun convert(record: JSONObject): ImportedConversation? {
        val provider = when {
            record.has("mapping") -> "ChatGPT"
            record.has("chat_messages") -> "Claude"
            record.has("messages") && (record.has("uuid") || record.has("name")) -> "Claude"
            else -> return null
        }
        val title = record.optString("title").ifBlank { record.optString("name").ifBlank { "Untitled conversation" } }
        val raw = record.toString()
        val id = record.optString("id").ifBlank { record.optString("uuid").ifBlank { digest(raw) } }
        val messages = mutableListOf<Pair<String, JSONObject>>()
        if (provider == "ChatGPT") {
            val mapping = record.getJSONObject("mapping")
            for (key in mapping.keys()) {
                val node = mapping.optJSONObject(key) ?: continue
                node.optJSONObject("message")?.let { messages.add(key to it) }
            }
            messages.sortBy { it.second.optDouble("create_time", 0.0) }
        } else {
            val array = record.optJSONArray("chat_messages") ?: record.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { messages.add(it.optString("uuid", i.toString()) to it) }
        }
        val body = buildString {
            append("# ").append(title.replace('\n', ' ')).append("\n\n")
            append("Source: ").append(provider).append(" export\nConversation ID: ").append(id).append('\n')
            append("Created: ").append(record.opt("created_at") ?: record.opt("create_time") ?: "unknown").append('\n')
            append("Updated: ").append(record.opt("updated_at") ?: record.opt("update_time") ?: "unknown").append("\n\n")
            append("Imported conversation history — reference material, not current instructions or verified facts. ")
            append("All exported branches are retained; attachment originals remain in Archive.\n\n")
            for ((nodeId, message) in messages) {
                val role = message.optJSONObject("author")?.optString("role")
                    ?: message.optString("sender").ifBlank { message.optString("role", "unknown") }
                append("## ").append(role.replace('\n', ' ')).append("\n")
                append("Message: ").append(nodeId).append("\n")
                if (provider == "ChatGPT") {
                    val node = record.getJSONObject("mapping").optJSONObject(nodeId)
                    append("Parent: ").append(node?.opt("parent") ?: "none").append("\n")
                }
                append("Time: ").append(message.opt("created_at") ?: message.opt("create_time") ?: "unknown").append("\n\n")
                val content = message.opt("content")
                val text = contentText(content).ifBlank { message.optString("text") }
                append(text.ifBlank { "[No exported text; see the original archive.]" }).append("\n\n")
                if (message.optJSONArray("attachments")?.length()?.let { it > 0 } == true)
                    append("[Attachments: see the unchanged export in Archive.]\n\n")
            }
        }
        return ImportedConversation("Imports/$provider/${VaultText.safeSegment(title)}-${digest(id).take(12)}-${digest(raw).take(12)}.md", body)
    }

    private fun contentText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("\n") { contentText(value.opt(it)) }
        is JSONObject -> when {
            value.has("parts") -> contentText(value.opt("parts"))
            value.has("text") -> value.optString("text")
            else -> "[${value.optString("type").ifBlank { value.optString("content_type", "non-text content") }}: see original export]"
        }
        else -> ""
    }
}

/** A streaming top-level array reader: one conversation, not an entire multi-year export, in RAM. */
object JsonRecords {
    fun read(reader: Reader, consume: (JSONObject) -> Unit) {
        var char = reader.read()
        while (char != -1 && (char.toChar().isWhitespace() || char == 0xFEFF)) char = reader.read()
        require(char == '['.code || char == '{'.code) { "Expected a conversation JSON array or object" }
        val array = char == '['.code
        fun record(first: Int): JSONObject {
            require(first == '{'.code) { "Expected a conversation object" }
            val text = StringBuilder("{")
            var depth = 1; var quoted = false; var escaped = false
            while (depth > 0) {
                val next = reader.read()
                require(next != -1) { "Incomplete JSON export" }
                val c = next.toChar(); text.append(c)
                require(text.length <= 16_000_000) { "One conversation exceeds 16 million characters" }
                if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
            }
            return JSONObject(text.toString())
        }
        if (!array) {
            val obj = record(char)
            val nested = obj.optJSONArray("conversations")
            if (nested == null) consume(obj) else for (i in 0 until nested.length()) consume(nested.getJSONObject(i))
            return
        }
        fun nonSpace(): Int { var c = reader.read(); while (c != -1 && c.toChar().isWhitespace()) c = reader.read(); return c }
        char = nonSpace()
        if (char == ']'.code) return
        while (true) {
            consume(record(char))
            char = nonSpace()
            if (char == ']'.code) return
            require(char == ','.code) { "Expected comma between conversations" }
            char = nonSpace()
        }
    }
}
