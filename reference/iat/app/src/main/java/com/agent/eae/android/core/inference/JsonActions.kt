package com.agent.eae.android.core.inference

import com.agent.eae.android.core.AgentAction
import org.json.JSONException
import org.json.JSONObject

/**
 * Decoding layer between raw model text and [AgentAction].
 *
 * Small local models drift: they fence their output, chat before the JSON, rename keys, or
 * double-encode `args` as a string. Everything here is deliberately permissive on input while
 * keeping the prompt itself short enough for a 1B-7B context.
 */
object JsonActions {
    const val ACTION_SCHEMA_HINT: String =
        """{"thought": "one short sentence", "action": "tool_name | final_response", "args": {}}"""

    private const val MAX_ECHO_CHARS = 200
    private const val MAX_THOUGHT_CHARS = 400

    private val ACTION_KEYS = listOf("action", "tool", "tool_name", "name")
    private val ARG_KEYS = listOf("args", "arguments", "parameters", "params", "input")
    private val THOUGHT_KEYS = listOf("thought", "thoughts", "reasoning")

    private val WHITESPACE = Regex("\\s+")

    fun buildSystemPrompt(
        activeSchemasJson: String,
        memoryContext: String,
        workspaceNote: String,
    ): String {
        val sb = StringBuilder()
        sb.append("You are EAE, an autonomous agent running on an Android phone.\n")
        sb.append("You act one step at a time. Reply with ONLY one JSON object, in this shape:\n")
        sb.append(ACTION_SCHEMA_HINT).append('\n')
        sb.append("Rules:\n")
        sb.append("- No prose, no markdown fences, no second JSON object. The whole reply is the object.\n")
        sb.append("- \"thought\": one short sentence.\n")
        sb.append("- \"action\": exactly one tool name from TOOLS, or \"")
        sb.append(AgentAction.FINAL)
        sb.append("\" when you can answer now.\n")
        sb.append("- \"args\": a JSON object matching that tool's schema. Use {} when the tool takes none.\n")
        sb.append("- For \"").append(AgentAction.FINAL)
        sb.append("\" put the full user-facing answer in args.text.\n")
        sb.append("- Use \"").append(AgentAction.FINAL)
        sb.append("\" immediately for greetings, small talk, direct questions, explanations, summaries, rewrites, and writing tasks.\n")
        sb.append("- Use tools only when the task needs phone state, workspace files, memory, scheduling, HTTP, Python, notifications, app launch, URL opening, or sharing.\n")
        sb.append("- Use \"").append(AgentAction.SEARCH_TOOLS)
        sb.append("\" only when a tool is truly required but no shown tool matches yet. Its args must be {\"query\": \"needed capability\"}.\n")
        sb.append("- For GitHub, use http_request against https://api.github.com. Use Authorization: Bearer {{secret:GITHUB_TOKEN}} and Accept: application/vnd.github+json.\n")
        sb.append("- GitHub examples: GET /repos/owner/repo, GET /repos/owner/repo/contents/path?ref=branch, GET /search/repositories?q=query, GET /repos/owner/repo/issues, POST /repos/owner/repo/issues.\n")
        sb.append("- If a TOOL RESULT lists matching tools and the user only asked what tools exist, answer with final_response summarizing those tool names; do not run one.\n")
        sb.append("- Never call search_tools for a greeting or normal answer. Never invent a tool name and never guess a tool result.\n")
        sb.append("\nTOOLS:\n")
        sb.append(activeSchemasJson.ifBlank { "[]" }).append('\n')
        if (memoryContext.isNotBlank()) {
            sb.append("\nMEMORY (retrieved, may be stale):\n")
            sb.append(memoryContext.trim()).append('\n')
        }
        if (workspaceNote.isNotBlank()) {
            sb.append("\nWORKSPACE:\n")
            sb.append(workspaceNote.trim()).append('\n')
        }
        return sb.toString()
    }

    /**
     * Returns the first balanced `{...}` span in [raw], or null.
     *
     * Braces inside string literals do not affect depth, and a backslash escape hides the next
     * character. Unbalanced candidates are skipped, so a stray `{` in the model's chatter before the
     * real object does not poison the scan.
     */
    fun extractFirstJsonObject(raw: String): String? {
        var start = raw.indexOf('{')
        while (start >= 0) {
            val end = scanBalanced(raw, start)
            if (end > start) return raw.substring(start, end + 1)
            start = raw.indexOf('{', start + 1)
        }
        return null
    }

    fun parse(raw: String): AgentAction? {
        val span = extractFirstJsonObject(raw)
            ?: recoverObjectClosedWithParen(raw)
            ?: return null
        val obj = try {
            JSONObject(span)
        } catch (e: JSONException) {
            return null
        }
        val action = firstString(obj, ACTION_KEYS) ?: return null
        val thought = firstString(obj, THOUGHT_KEYS).orEmpty()
        return AgentAction(
            thought = thought.take(MAX_THOUGHT_CHARS),
            action = action,
            args = argsOf(obj),
        )
    }

    fun repairPrompt(raw: String): String {
        val collapsed = raw.trim().replace(WHITESPACE, " ")
        val echo = if (collapsed.length > MAX_ECHO_CHARS) {
            collapsed.take(MAX_ECHO_CHARS) + "..."
        } else {
            collapsed.ifEmpty { "(empty reply)" }
        }
        return buildString {
            append("Your last reply was not a single valid JSON object, so nothing could be executed.\n")
            append("It began with: ").append(echo).append('\n')
            append("Reply again with ONLY this object and nothing else - no prose, no fences:\n")
            append(ACTION_SCHEMA_HINT)
        }
    }

    /**
     * Some small models produce an almost-valid object but close the outer object with ')' instead
     * of '}'. Recover that exact typo before giving up and showing raw text.
     */
    private fun recoverObjectClosedWithParen(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf(')')
        if (start < 0 || end <= start) return null
        val candidate = raw.substring(start, end) + "}"
        return try {
            JSONObject(candidate)
            candidate
        } catch (e: JSONException) {
            null
        }
    }

    private fun scanBalanced(s: String, from: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                        if (depth < 0) return -1
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun firstString(obj: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            val value = stringAt(obj, key)
            if (value != null) return value
        }
        return null
    }

    /** Only accepts real scalars: [JSONObject.optString] would stringify a nested object. */
    private fun stringAt(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val value = obj.opt(key)) {
            is String -> value.trim().ifEmpty { null }
            is Number, is Boolean -> value.toString()
            else -> null
        }
    }

    private fun argsOf(obj: JSONObject): JSONObject {
        for (key in ARG_KEYS) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key)
            if (value is JSONObject) return value
            if (value is String) {
                val nested = extractFirstJsonObject(value) ?: continue
                val parsed = try {
                    JSONObject(nested)
                } catch (e: JSONException) {
                    null
                }
                if (parsed != null) return parsed
            }
        }
        return JSONObject()
    }
}
