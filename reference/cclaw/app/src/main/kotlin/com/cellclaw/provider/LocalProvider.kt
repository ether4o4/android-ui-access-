package com.cellclaw.provider

import com.cellclaw.tools.ToolApiDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Provider for local GGUF models served by an OpenAI-compatible server
 * (llama.cpp's llama-server running in Termux, or Ollama / LM Studio on
 * the same network). No API key required; the endpoint is configurable.
 *
 * Tool calling requires the server to translate OpenAI-style tool
 * definitions through the model's chat template — for llama-server that
 * means launching with --jinja and a model whose template supports tools
 * (Qwen, Llama 3.x, Hermes, etc.).
 */
class LocalProvider @Inject constructor() : Provider {

    override val name = "local"

    private var endpoint: String = DEFAULT_ENDPOINT
    private var model: String = DEFAULT_MODEL
    private var apiKey: String = ""

    // Local inference is slow on phone hardware; allow long generations.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // Maps sanitized names (dots replaced with underscores) back to original tool names
    private val toolNameMap = mutableMapOf<String, String>()

    fun configure(endpoint: String, model: String = DEFAULT_MODEL, apiKey: String = "") {
        this.endpoint = endpoint.ifBlank { DEFAULT_ENDPOINT }
        this.model = model.ifBlank { DEFAULT_MODEL }
        this.apiKey = apiKey
    }

    override suspend fun complete(request: CompletionRequest): CompletionResponse =
        withContext(Dispatchers.IO) {
            val body = buildRequestBody(request)
            val builder = Request.Builder()
                .url(chatCompletionsUrl(endpoint))
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
            if (apiKey.isNotBlank()) {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = client.newCall(builder.build()).execute()
            val responseBody = response.body?.string()
                ?: throw ProviderException("Empty response body")

            if (!response.isSuccessful) {
                throw ProviderException(formatServerError(response.code, responseBody))
            }

            parseResponse(json.parseToJsonElement(responseBody).jsonObject)
        }

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        val body = buildRequestBody(request, stream = true)
        val builder = Request.Builder()
            .url(chatCompletionsUrl(endpoint))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: Exception) {
            emit(StreamEvent.Error("Cannot reach local server at $endpoint: ${e.message}"))
            return@flow
        }
        if (!response.isSuccessful) {
            val err = response.body?.string().orEmpty()
            emit(StreamEvent.Error(formatServerError(response.code, err)))
            return@flow
        }

        val source = response.body?.source()
        if (source == null) {
            emit(StreamEvent.Error("Empty response stream"))
            return@flow
        }

        val textBuilder = StringBuilder()
        // Accumulate streamed tool_call fragments keyed by their array index.
        val toolAccumulators = sortedMapOf<Int, StreamingToolCall>()
        var finishReason: String? = null

        try {
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break

                val chunk = try {
                    json.parseToJsonElement(data).jsonObject
                } catch (_: Exception) {
                    continue
                }
                val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject

                delta?.get("content")?.jsonPrimitive?.contentOrNull?.let { text ->
                    if (text.isNotEmpty()) {
                        textBuilder.append(text)
                        emit(StreamEvent.TextDelta(text))
                    }
                }

                delta?.get("tool_calls")?.jsonArray?.forEach { tcElement ->
                    val tc = tcElement.jsonObject
                    val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val acc = toolAccumulators.getOrPut(index) { StreamingToolCall() }
                    tc["id"]?.jsonPrimitive?.contentOrNull?.let { acc.id = it }
                    val fn = tc["function"]?.jsonObject
                    fn?.get("name")?.jsonPrimitive?.contentOrNull?.let {
                        if (acc.name == null) {
                            acc.name = it
                            emit(StreamEvent.ToolUseStart(acc.id ?: "", it))
                        }
                    }
                    fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let {
                        acc.arguments.append(it)
                        emit(StreamEvent.ToolUseInputDelta(it))
                    }
                }

                choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: "Stream read error"))
            return@flow
        }

        val blocks = mutableListOf<ContentBlock>()
        if (textBuilder.isNotBlank()) blocks.add(ContentBlock.Text(textBuilder.toString()))
        for ((_, acc) in toolAccumulators) {
            val input = try {
                json.parseToJsonElement(acc.arguments.toString().ifBlank { "{}" }).jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
            val rawName = acc.name ?: continue
            blocks.add(ContentBlock.ToolUse(
                id = acc.id ?: "",
                name = toolNameMap[rawName] ?: rawName.replace("_", "."),
                input = input
            ))
        }

        val stopReason = when {
            finishReason == "tool_calls" || toolAccumulators.isNotEmpty() -> StopReason.TOOL_USE
            finishReason == "length" -> StopReason.MAX_TOKENS
            else -> StopReason.END_TURN
        }
        emit(StreamEvent.Complete(CompletionResponse(blocks, stopReason)))
    }.flowOn(Dispatchers.IO)

    private class StreamingToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private fun buildRequestBody(request: CompletionRequest, stream: Boolean = false): String {
        toolNameMap.clear()
        val messages = buildJsonArray {
            // System message
            add(buildJsonObject {
                put("role", "system")
                put("content", request.systemPrompt)
            })
            // Conversation messages
            for (msg in request.messages) {
                val toolResults = msg.content.filterIsInstance<ContentBlock.ToolResult>()
                val toolCalls = msg.content.filterIsInstance<ContentBlock.ToolUse>()

                if (toolResults.isNotEmpty()) {
                    // OpenAI-compatible servers expect each tool result as a
                    // separate message with role: "tool"
                    for (result in toolResults) {
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", result.toolUseId)
                            put("content", result.content)
                        })
                    }
                } else if (toolCalls.isNotEmpty()) {
                    // Assistant message with tool calls
                    add(buildJsonObject {
                        put("role", "assistant")
                        val textContent = msg.content.filterIsInstance<ContentBlock.Text>()
                            .joinToString("") { it.text }
                        if (textContent.isNotBlank()) {
                            put("content", textContent)
                        } else {
                            put("content", JsonNull)
                        }
                        putJsonArray("tool_calls") {
                            for (call in toolCalls) {
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name.replace(".", "_"))
                                        put("arguments", json.encodeToString(JsonObject.serializer(), call.input))
                                    }
                                })
                            }
                        }
                    })
                } else {
                    // Regular user or assistant message. Images are sent as
                    // data URLs; only multimodal GGUF setups (e.g. llama-server
                    // with --mmproj) will understand them.
                    add(buildJsonObject {
                        put("role", msg.role.name.lowercase())
                        val hasImages = msg.content.any { it is ContentBlock.Image }
                        if (hasImages) {
                            putJsonArray("content") {
                                for (block in msg.content) {
                                    when (block) {
                                        is ContentBlock.Text -> add(buildJsonObject {
                                            put("type", "text")
                                            put("text", block.text)
                                        })
                                        is ContentBlock.Image -> add(buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", "data:${block.mediaType};base64,${block.base64Data}")
                                            }
                                        })
                                        else -> {}
                                    }
                                }
                            }
                        } else {
                            val textContent = msg.content.filterIsInstance<ContentBlock.Text>()
                                .joinToString("") { it.text }
                            put("content", textContent)
                        }
                    })
                }
            }
        }

        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("max_tokens", request.maxTokens)
            if (stream) put("stream", true)
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in request.tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                val sanitized = tool.name.replace(".", "_")
                                toolNameMap[sanitized] = tool.name
                                put("name", sanitized)
                                put("description", tool.description)
                                putJsonObject("parameters") {
                                    put("type", tool.inputSchema.type)
                                    putJsonObject("properties") {
                                        for ((key, prop) in tool.inputSchema.properties) {
                                            putJsonObject(key) {
                                                put("type", prop.type)
                                                put("description", prop.description)
                                            }
                                        }
                                    }
                                    if (tool.inputSchema.required.isNotEmpty()) {
                                        putJsonArray("required") {
                                            tool.inputSchema.required.forEach { add(it) }
                                        }
                                    }
                                }
                            }
                        })
                    }
                }
            }
        })
    }

    /**
     * Human-readable error for a failed request. A 404 mentioning the model
     * usually means the configured model name doesn't match anything the
     * server has (common with Ollama, which validates names — unlike
     * llama-server, which ignores them).
     */
    private fun formatServerError(code: Int, body: String): String {
        val looksLikeMissingModel = code == 404 &&
            (body.contains("not found", ignoreCase = true) || body.contains("model", ignoreCase = true))
        return if (looksLikeMissingModel) {
            "Model \"$model\" not found on the server at $endpoint.\n\n" +
                "If you're using Ollama, set the Model in Settings to a tag you've pulled " +
                "(run `ollama list` to see them, e.g. qwen3:4b). llama-server ignores the " +
                "model name, so any value works there.\n\nServer said: $body"
        } else {
            "Local server error $code at $endpoint: $body\nIs the server running?"
        }
    }

    private fun parseResponse(obj: JsonObject): CompletionResponse {
        val choices = obj["choices"]?.jsonArray ?: return CompletionResponse(emptyList(), StopReason.ERROR)
        val choice = choices.firstOrNull()?.jsonObject ?: return CompletionResponse(emptyList(), StopReason.ERROR)
        val message = choice["message"]?.jsonObject ?: return CompletionResponse(emptyList(), StopReason.ERROR)

        val blocks = mutableListOf<ContentBlock>()

        // Text content
        message["content"]?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotBlank()) blocks.add(ContentBlock.Text(it))
        }

        // Tool calls
        message["tool_calls"]?.jsonArray?.forEach { toolCallElement ->
            val toolCall = toolCallElement.jsonObject
            val function = toolCall["function"]?.jsonObject ?: return@forEach
            val input = try {
                json.parseToJsonElement(function["arguments"]?.jsonPrimitive?.content ?: "{}").jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }

            val rawName = function["name"]?.jsonPrimitive?.content ?: ""
            val toolName = toolNameMap[rawName] ?: rawName.replace("_", ".")
            blocks.add(ContentBlock.ToolUse(
                id = toolCall["id"]?.jsonPrimitive?.content ?: "",
                name = toolName,
                input = input
            ))
        }

        val finishReason = choice["finish_reason"]?.jsonPrimitive?.content
        val stopReason = when (finishReason) {
            "tool_calls" -> StopReason.TOOL_USE
            "length" -> StopReason.MAX_TOKENS
            else -> StopReason.END_TURN
        }

        return CompletionResponse(blocks, stopReason)
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:8080"
        const val DEFAULT_MODEL = "local-gguf"

        /**
         * Build the chat completions URL from a user-supplied endpoint,
         * tolerating trailing slashes and an already-present /v1 suffix.
         */
        fun chatCompletionsUrl(endpoint: String): String {
            var base = endpoint.trim().trimEnd('/')
            if (base.endsWith("/v1")) base = base.removeSuffix("/v1")
            return "$base/v1/chat/completions"
        }
    }
}
