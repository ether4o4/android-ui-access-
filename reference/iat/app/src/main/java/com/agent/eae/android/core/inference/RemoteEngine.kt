package com.agent.eae.android.core.inference

import com.agent.eae.android.core.AgentException
import com.agent.eae.android.core.EngineConfig
import com.agent.eae.android.core.Role
import com.agent.eae.android.core.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * Any OpenAI-compatible `/chat/completions` server: Ollama, llama.cpp server, LM Studio, vLLM.
 */
class RemoteEngine(private val cfg: EngineConfig) : InferenceEngine {
    override val label: String = "remote " + cfg.modelName

    override suspend fun generate(systemPrompt: String, transcript: List<Turn>): String =
        withContext(Dispatchers.IO) {
            contentOf(post(requestBody(systemPrompt, transcript)))
        }

    override fun close() = Unit

    private fun endpoint(): String = cfg.baseUrl.trim().trimEnd('/') + "/chat/completions"

    private fun requestBody(systemPrompt: String, transcript: List<Turn>): JSONObject {
        val messages = JSONArray()
        messages.put(message("system", systemPrompt))
        for (turn in transcript) {
            if (turn.content.isBlank()) continue
            when (turn.role) {
                Role.SYSTEM -> messages.put(message("system", turn.content))
                Role.USER -> messages.put(message("user", turn.content))
                Role.ASSISTANT -> messages.put(message("assistant", turn.content))
                // The OpenAI "tool" role requires a matching tool_call_id, which this agent loop
                // never issues, so observations come back as a labelled user turn instead.
                Role.TOOL -> messages.put(message("user", TOOL_PREFIX + turn.content))
            }
        }
        return JSONObject()
            .put("model", cfg.modelName)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", TEMPERATURE)
            // Layer 1 grammar enforcement: servers that honour this cannot emit non-JSON at all.
            .put("response_format", JSONObject().put("type", "json_object"))
    }

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun post(body: JSONObject): String {
        val url = try {
            URL(endpoint())
        } catch (e: MalformedURLException) {
            throw AgentException("Server address is not a valid URL: ${cfg.baseUrl}")
        }
        val conn = try {
            url.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw AgentException("Could not open a connection to ${url.host}: ${e.message}")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            if (cfg.apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey.trim()}")
            }
            conn.outputStream.use { out -> out.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status !in 200..299) {
                val detail = conn.errorStream?.readTextOrEmpty().orEmpty().truncate()
                throw AgentException(
                    "Model server returned HTTP $status" +
                        if (detail.isEmpty()) "." else ": $detail",
                )
            }
            return conn.inputStream.readTextOrEmpty()
        } catch (e: AgentException) {
            throw e
        } catch (e: IOException) {
            throw AgentException(
                "Could not reach the model server at ${cfg.baseUrl} (${e.javaClass.simpleName}: " +
                    "${e.message}). Check that it is running and reachable from this device.",
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun contentOf(rawBody: String): String {
        val root = try {
            JSONObject(rawBody)
        } catch (e: JSONException) {
            throw AgentException("Model server sent a non-JSON response: ${rawBody.truncate()}")
        }
        root.optJSONObject("error")?.let { err ->
            throw AgentException("Model server error: " + err.optString("message", err.toString()).truncate())
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AgentException("Model server response had no choices: ${rawBody.truncate()}")
        val content = choice.optJSONObject("message")?.optString("content").orEmpty()
        if (content.isBlank()) {
            throw AgentException("Model returned an empty message. Try a smaller prompt or another model.")
        }
        return content
    }

    private fun InputStream.readTextOrEmpty(): String =
        use { stream -> stream.bufferedReader(Charsets.UTF_8).readText() }

    private fun String.truncate(): String =
        if (length > MAX_ERROR_BODY_CHARS) take(MAX_ERROR_BODY_CHARS) + "..." else this

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
        const val MAX_ERROR_BODY_CHARS = 500
        const val TEMPERATURE = 0.2
        const val TOOL_PREFIX = "TOOL RESULT: "
    }
}
