package com.ether4o4.odcm.agent

import com.ether4o4.odcm.data.ModelProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One decision from the model: at most one validated tool call and any plain text. */
class ModelDecision(
    val toolUseId: String?,
    val toolName: String?,
    val toolArgs: Map<String, Any?>,
    val plainText: String,
    val stopReason: String?,
)

class ModelException(message: String) : Exception(message)

/**
 * Claude Messages and OpenAI-compatible Chat Completions over OkHttp and built-in JSON.
 * No SDK, no reflection, nothing heavy loaded at runtime. Holds one task's message history.
 */
class ModelClient(
    private val apiKey: String,
    private val model: String,
    private val effort: String,
    private val systemPrompt: String,
    private val provider: ModelProvider = ModelProvider.ANTHROPIC,
    private val callFactory: Call.Factory = http,
    private val endpoint: String = provider.defaultEndpoint,
) : TaskModel {
    private val history = JSONArray()
    private val anthropic = provider == ModelProvider.ANTHROPIC

    init {
        val url = endpoint.toHttpUrlOrNull()
            ?: throw ModelException("Enter a valid model endpoint URL.")
        val authorityHasCredentials = endpoint.substringAfter("://").substringBefore('/').contains('@')
        if (authorityHasCredentials || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw ModelException("The model endpoint cannot contain credentials, a query, or a fragment.")
        }
        if (url.scheme != "https" && !(url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "::1"))) {
            throw ModelException("Remote model endpoints require HTTPS. HTTP is allowed only for localhost.")
        }
        if (provider.requiresKey && apiKey.isBlank()) throw ModelException("Add the ${provider.label} API key in Settings.")
        if (apiKey.any { it.code !in 32..126 }) throw ModelException("The API key contains invalid characters. Paste the key again in Settings.")
        if (model.isBlank()) throw ModelException("Enter a model name in Settings.")
    }

    override fun addUserText(text: String) {
        if (anthropic) history.put(message("user", JSONArray().put(block("text") { put("text", text) })))
        else history.put(JSONObject().put("role", "user").put("content", text))
    }

    override fun addToolResult(toolUseId: String, result: String, isError: Boolean) {
        if (!anthropic) {
            history.put(JSONObject().put("role", "tool").put("tool_call_id", toolUseId)
                .put("content", if (isError) "Tool error: $result" else result))
            return
        }
        val tr = block("tool_result") {
            put("tool_use_id", toolUseId)
            put("content", result)
            if (isError) put("is_error", true)
        }
        history.put(message("user", JSONArray().put(tr)))
    }

    override suspend fun next(): ModelDecision {
        currentCoroutineContext().ensureActive()
        val response = post(if (anthropic) anthropicBody() else chatBody())
        currentCoroutineContext().ensureActive()
        return try {
            if (anthropic) anthropicDecision(response) else chatDecision(response)
        } catch (e: JSONException) {
            throw ModelException("The model returned a malformed response; no action was executed.")
        }
    }

    private fun anthropicBody(): JSONObject = JSONObject()
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
            .put("tool_choice", JSONObject().put("type", "auto").put("disable_parallel_tool_use", true))
            .put("messages", history)

    private fun chatBody(): JSONObject {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (i in 0 until history.length()) messages.put(history.get(i))
        val tools = JSONArray()
        for (i in 0 until Tools.json.length()) {
            val tool = Tools.json.getJSONObject(i)
            tools.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", tool.getString("name"))
                .put("description", tool.getString("description"))
                .put("parameters", tool.getJSONObject("input_schema"))))
        }
        return JSONObject().put("model", model).put("messages", messages).put("tools", tools)
            .put("tool_choice", "auto").put("parallel_tool_calls", false)
            .put("stream", false).put("max_tokens", 4096)
    }

    private fun anthropicDecision(response: JSONObject): ModelDecision {
        val content = response.optJSONArray("content") ?: JSONArray()
        if ((0 until content.length()).count { content.getJSONObject(it).optString("type") == "tool_use" } > 1) {
            throw ModelException("The model returned multiple actions; no action was executed.")
        }
        val text = StringBuilder()
        var toolId: String? = null
        var toolName: String? = null
        var toolArgs: Map<String, Any?> = emptyMap()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text"))
                "tool_use" -> if (toolId == null) {
                    toolId = requiredString(b, "id")
                    toolName = requiredString(b, "name")
                    requireKnownTool(toolName)
                    toolArgs = toMap(b.getJSONObject("input"))
                }
            }
        }
        // Only valid turns enter history, including their original tool-use blocks.
        history.put(message("assistant", content))
        return ModelDecision(toolId, toolName, toolArgs, text.toString().trim(), response.opt("stop_reason") as? String)
    }

    private fun chatDecision(response: JSONObject): ModelDecision {
        val choices = response.getJSONArray("choices")
        if (choices.length() != 1) throw ModelException("The model returned an unexpected number of decisions; no action was executed.")
        val choice = choices.getJSONObject(0)
        val assistant = choice.getJSONObject("message")
        if (assistant.optString("role") != "assistant" || !assistant.isNull("function_call")) {
            throw ModelException("The model returned an unsupported response; no action was executed.")
        }
        val content = assistant.opt("content")
        val text = when (content) {
            null, JSONObject.NULL -> ""
            is String -> content
            else -> throw ModelException("The model returned unsupported content; no action was executed.")
        }
        val calls = when (val value = assistant.opt("tool_calls")) {
            null, JSONObject.NULL -> JSONArray()
            is JSONArray -> value
            else -> throw ModelException("The model returned malformed actions; no action was executed.")
        }
        if (calls.length() > 1) throw ModelException("The model returned multiple actions; no action was executed.")
        var toolId: String? = null
        var toolName: String? = null
        var toolArgs: Map<String, Any?> = emptyMap()
        if (calls.length() == 1) {
            val call = calls.getJSONObject(0)
            if (requiredString(call, "type") != "function") throw ModelException("The model returned an unsupported action; no action was executed.")
            toolId = requiredString(call, "id")
            val function = call.getJSONObject("function")
            toolName = requiredString(function, "name")
            requireKnownTool(toolName)
            val arguments = function.get("arguments") as? String
                ?: throw ModelException("The model returned malformed action arguments; no action was executed.")
            toolArgs = toMap(parseObject(arguments))
        } else if (choice.optString("finish_reason") == "tool_calls") {
            throw ModelException("The model omitted its action; no action was executed.")
        }
        // Preserve tool IDs and provider extensions needed to replay the assistant turn.
        history.put(assistant)
        return ModelDecision(toolId, toolName, toolArgs, text.trim(), choice.opt("finish_reason") as? String)
    }

    private fun requiredString(value: JSONObject, name: String): String =
        (value.get(name) as? String)?.takeIf { it.isNotBlank() }
            ?: throw ModelException("The model returned an incomplete action; no action was executed.")

    private fun requireKnownTool(name: String) {
        if ((0 until Tools.json.length()).none { Tools.json.getJSONObject(it).getString("name") == name }) {
            throw ModelException("The model requested an unknown action; no action was executed.")
        }
    }

    private fun parseObject(value: String): JSONObject {
        try {
            val tokens = JSONTokener(value)
            val result = tokens.nextValue() as? JSONObject ?: throw JSONException("Expected object")
            if (tokens.nextClean() != '\u0000') throw JSONException("Unexpected trailing content")
            return result
        } catch (e: JSONException) {
            // A parser's own exception can include private response text or credentials.
            throw ModelException("The model returned malformed JSON; no action was executed.")
        }
    }

    private suspend fun post(body: JSONObject): JSONObject {
        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
        if (anthropic) {
            builder.addHeader("x-api-key", apiKey).addHeader("anthropic-version", API_VERSION)
        } else if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
        val request = builder.build()
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                val response = callFactory.newCall(request).awaitPayload()
                currentCoroutineContext().ensureActive()
                if (response.code in 200..299) return parseObject(response.payload)
                // 429 and 5xx are worth one retry; everything else is final.
                if ((response.code == 429 || response.code >= 500) && attempt == 0) {
                    delay(1500)
                    return@repeat
                }
                throw ModelException(apiError(response.code))
            } catch (e: IOException) {
                if (attempt == 0) { delay(1000); return@repeat }
            }
        }
        throw ModelException("Could not connect to ${provider.label}. Check the endpoint and connection.")
    }

    private data class HttpPayload(val code: Int, val payload: String)

    /** Cancellation also closes a response body that is still arriving from the network. */
    private suspend fun Call.awaitPayload(): HttpPayload = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val payload = response.use { HttpPayload(it.code, it.body?.string().orEmpty()) }
                    if (continuation.isActive) continuation.resume(payload)
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    // Do not expose server error text: it may echo API keys, request data, or full endpoint URLs.
    private fun apiError(code: Int): String = "${provider.label} API error $code. " + when (code) {
        401 -> "Check the selected provider's API key."
        402 -> "The provider requires credits or the account's allowance is exhausted."
        403 -> "This key or account cannot access the requested model."
        404 -> "Check the model name and endpoint."
        429 -> "The provider's rate limit was reached. Try again later."
        else -> "The request could not be completed."
    }

    private fun normalizedEffort(): String =
        if (effort.lowercase() in setOf("low", "medium", "high")) effort.lowercase() else "low"

    private fun message(role: String, content: JSONArray) =
        JSONObject().put("role", role).put("content", content)

    private inline fun block(type: String, build: JSONObject.() -> Unit) =
        JSONObject().put("type", type).apply(build)

    companion object {
        private const val API_VERSION = "2026-10-21"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // One shared client: HTTP/2, connection pooling, lower latency and memory than per-task clients.
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .followRedirects(false)
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
