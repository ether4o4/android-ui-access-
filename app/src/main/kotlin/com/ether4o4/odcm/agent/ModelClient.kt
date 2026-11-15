package com.ether4o4.odcm.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One decision from the model: the first tool call, its parsed arguments, and any plain text. */
class ModelDecision(
    val toolUseId: String?,
    val toolName: String?,
    val toolArgs: Map<String, Any?>,
    val plainText: String,
    val stopReason: String?,
)

class ModelException(message: String) : Exception(message)

/**
 * Direct Claude Messages API client over OkHttp with Android's built-in JSON.
 * No SDK, no reflection, nothing heavy loaded at runtime. Holds one task's message history.
 */
class ModelClient(
    private val apiKey: String,
    private val model: String,
    private val effort: String,
    private val systemPrompt: String,
) {
    private val history = JSONArray()

    fun addUserText(text: String) {
        history.put(message("user", JSONArray().put(block("text") { put("text", text) })))
    }

    fun addToolResult(toolUseId: String, result: String, isError: Boolean) {
        val tr = block("tool_result") {
            put("tool_use_id", toolUseId)
            put("content", result)
            if (isError) put("is_error", true)
        }
        history.put(message("user", JSONArray().put(tr)))
    }

    fun next(): ModelDecision {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("output_config", JSONObject().put("effort", normalizedEffort()))
            .put("system", JSONArray().put(
                block("text") {
                    put("text", systemPrompt)
                    put("cache_control", JSONObject().put("type", "ephemeral"))
                },
            ))
            .put("tools", Tools.json)
            .put("messages", history)

        val response = post(body)
        val content = response.optJSONArray("content") ?: JSONArray()
        // Echo the assistant turn (including any tool_use blocks) so the next request is valid.
        history.put(message("assistant", content))

        val text = StringBuilder()
        var toolId: String? = null
        var toolName: String? = null
        var toolArgs: Map<String, Any?> = emptyMap()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text"))
                "tool_use" -> if (toolId == null) {
                    toolId = b.optString("id")
                    toolName = b.optString("name")
                    toolArgs = toMap(b.optJSONObject("input") ?: JSONObject())
                }
            }
        }
        return ModelDecision(toolId, toolName, toolArgs, text.toString().trim(), response.optString("stop_reason", null))
    }

    private fun post(body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                http.newCall(request).execute().use { resp ->
                    val payload = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) return JSONObject(payload)
                    // 429 and 5xx are worth one retry; everything else is final.
                    if (resp.code == 429 || resp.code >= 500) {
                        lastError = IOException("HTTP ${resp.code}")
                        if (attempt == 0) { Thread.sleep(1500); return@repeat }
                    }
                    throw ModelException(apiError(resp.code, payload))
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt == 0) { Thread.sleep(1000); return@repeat }
            }
        }
        throw ModelException("Network error: ${lastError?.message}")
    }

    private fun apiError(code: Int, payload: String): String {
        val msg = runCatching { JSONObject(payload).optJSONObject("error")?.optString("message") }.getOrNull()
        return "Claude API error $code" + (msg?.let { ": $it" } ?: "")
    }

    private fun normalizedEffort(): String =
        if (effort.lowercase() in setOf("low", "medium", "high")) effort.lowercase() else "low"

    private fun message(role: String, content: JSONArray) =
        JSONObject().put("role", role).put("content", content)

    private inline fun block(type: String, build: JSONObject.() -> Unit) =
        JSONObject().put("type", type).apply(build)

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2026-10-21"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // One shared client: HTTP/2, connection pooling, lower latency and memory than per-task clients.
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /** Shallow + nested JSONObject/JSONArray to Kotlin maps/lists, for tool inputs. */
        fun toMap(o: JSONObject): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            for (key in o.keys()) map[key] = unwrap(o.get(key))
            return map
        }

        private fun unwrap(value: Any?): Any? = when (value) {
            JSONObject.NULL -> null
            is JSONObject -> toMap(value)
            is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
            else -> value
        }
    }
}
