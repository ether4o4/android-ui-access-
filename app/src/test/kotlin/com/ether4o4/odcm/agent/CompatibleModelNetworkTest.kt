package com.ether4o4.odcm.agent

import com.ether4o4.odcm.data.ModelProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CompatibleModelNetworkTest {
    @Test fun sendsFunctionSchemaAndReplaysToolIdentityAndProviderExtensions() = runBlocking {
        MockWebServer().use { server ->
            val assistant = toolMessage("search_vault", """{"query":"reference"}""")
                .put("reasoning_details", JSONArray().put(JSONObject().put("type", "opaque").put("data", "keep-me")))
            server.enqueue(response(assistant))
            server.enqueue(response(JSONObject().put("role", "assistant").put("content", "Found the reference note."), "stop"))
            val client = client(server, ModelProvider.OPENROUTER)
            client.addUserText("Find the reference note")

            val decision = client.next()
            assertEquals("call-one", decision.toolUseId)
            assertEquals("search_vault", decision.toolName)
            assertEquals("reference", decision.toolArgs["query"])
            val first = server.takeRequest()
            assertEquals("Bearer test-key", first.getHeader("Authorization"))
            assertNull(first.getHeader("x-api-key"))
            assertNull(first.getHeader("anthropic-version"))
            val body = JSONObject(first.body.readUtf8())
            assertEquals("auto", body.getString("tool_choice"))
            assertFalse(body.getBoolean("parallel_tool_calls"))
            assertFalse(body.getBoolean("stream"))
            assertFalse(body.has("output_config"))
            assertFalse(body.has("system"))
            val messages = body.getJSONArray("messages")
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals("system prompt", messages.getJSONObject(0).getString("content"))
            assertEquals("Find the reference note", messages.getJSONObject(1).getString("content"))
            val tools = body.getJSONArray("tools")
            assertEquals(Tools.json.length(), tools.length())
            val function = tools.getJSONObject(0).getJSONObject("function")
            assertEquals("tap", function.getString("name"))
            assertEquals("integer", function.getJSONObject("parameters").getJSONObject("properties").getJSONObject("index").getString("type"))
            assertFalse(function.has("input_schema"))

            client.addToolResult(decision.toolUseId!!, "Reference/ODCM/note.md", false)
            client.addUserText("Current screen data")
            assertEquals("Found the reference note.", client.next().plainText)
            val replay = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            val replayedAssistant = replay.getJSONObject(2)
            assertEquals("call-one", replayedAssistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertEquals("keep-me", replayedAssistant.getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
            assertEquals("tool", replay.getJSONObject(3).getString("role"))
            assertEquals("call-one", replay.getJSONObject(3).getString("tool_call_id"))
            assertEquals("Reference/ODCM/note.md", replay.getJSONObject(3).getString("content"))
            assertEquals("Current screen data", replay.getJSONObject(4).getString("content"))
        }
    }

    @Test fun compatiblePresetsUseBearerAndLocalOllamaNeedsNoKey() = runBlocking {
        for (provider in ModelProvider.entries.filter { it != ModelProvider.ANTHROPIC }) {
            MockWebServer().use { server ->
                server.enqueue(response(JSONObject().put("role", "assistant").put("content", "hello")
                    .put("tool_calls", JSONObject.NULL).put("function_call", JSONObject.NULL), "stop"))
                val key = if (provider.requiresKey) "provider-key" else ""
                assertEquals("hello", client(server, provider, key).next().plainText)
                val request = server.takeRequest()
                assertEquals(if (key.isEmpty()) null else "Bearer provider-key", request.getHeader("Authorization"))
                assertNull(request.getHeader("anthropic-version"))
                assertNull(request.getHeader("x-api-key"))
            }
        }
    }

    @Test fun errorToolResultRemainsAToolMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(toolMessage("tap", """{"index":0}""")))
            server.enqueue(response(JSONObject().put("role", "assistant").put("content", "Unable to tap."), "stop"))
            val client = client(server)
            client.next()
            client.addToolResult("call-one", "Screen changed", true)
            client.next()
            server.takeRequest()
            val result = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(2)
            assertEquals("tool", result.getString("role"))
            assertEquals("call-one", result.getString("tool_call_id"))
            assertEquals("Tool error: Screen changed", result.getString("content"))
        }
    }

    @Test fun rejectsMalformedArgumentsUnknownToolsAndMultipleCallsWithoutPoisoningHistory() = runBlocking {
        val malformedMessages = listOf(
            toolMessage("tap", "not-json-secret"),
            toolMessage("tap", "[]"),
            toolMessage("tap", "{} trailing-secret"),
            toolMessage("tap", "{\"index\":0"),
            toolMessage("unregistered_action", "{}"),
            toolMessage("tap", "{}").apply { getJSONArray("tool_calls").getJSONObject(0).put("id", "") },
            toolMessage("tap", "{}").apply { getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments", JSONObject()) },
            toolMessage("home", "{}").apply { getJSONArray("tool_calls").put(toolMessage("back", "{}").getJSONArray("tool_calls").getJSONObject(0)) },
        )
        for (malformed in malformedMessages) {
            MockWebServer().use { server ->
                server.enqueue(response(malformed))
                server.enqueue(response(JSONObject().put("role", "assistant").put("content", "Safe reply"), "stop"))
                val client = client(server)
                client.addUserText("task")
                val error = runCatching { client.next() }.exceptionOrNull()
                assertTrue(error is ModelException)
                assertFalse(error!!.message.orEmpty().contains("secret"))
                assertEquals("Safe reply", client.next().plainText)
                server.takeRequest()
                val retryHistory = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                assertEquals(2, retryHistory.length()) // system + user only; failed assistant never stored
            }
        }
    }

    @Test fun malformedResponseAndServerErrorsDoNotExposeKeysOrResponseContent() = runBlocking {
        for ((code, body) in listOf(
            200 to "not-json private-vault-text test-key",
            200 to """{"choices":[{"message":{"role":"assistant","tool_calls":"test-key"}}]}""",
            401 to """{"error":{"message":"Bad key test-key; private-vault-text"}}""",
            402 to """{"error":{"message":"test-key"}}""",
        )) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code).setBody(body))
                val error = runCatching { client(server).next() }.exceptionOrNull()
                assertTrue(error is ModelException)
                assertFalse(error!!.message.orEmpty().contains("test-key"))
                assertFalse(error.message.orEmpty().contains("private-vault-text"))
                if (code >= 400) assertTrue(error.message.orEmpty().contains(code.toString()))
            }
        }
    }

    @Test fun compatibleCancellationClosesStalledRequestWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(3, TimeUnit.SECONDS))
            val job = launch { client(server, ModelProvider.OLLAMA, "").next() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectsCannotForwardCredentialsToAnotherEndpoint() = runBlocking {
        MockWebServer().use { destination ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/capture")))
                val error = runCatching { client(server, ModelProvider.ANTHROPIC).next() }.exceptionOrNull()
                assertTrue(error is ModelException)
                assertEquals(0, destination.requestCount)
                assertFalse(error!!.message.orEmpty().contains("test-key"))
            }
        }
    }

    @Test fun endpointAndKeyValidationNeverEchoSecrets() {
        for (endpoint in listOf(
            "http://example.com/chat/completions", "https://user:secret@example.com/chat/completions", "https://@example.com/chat/completions",
            "https://example.com/chat/completions?key=secret", "https://example.com/chat/completions#secret",
            "file:///secret", "not-a-url-secret",
        )) {
            val error = runCatching { ModelClient("key", "model", "low", "system", ModelProvider.CUSTOM, endpoint = endpoint) }.exceptionOrNull()
            assertTrue(error is ModelException)
            assertFalse(error!!.message.orEmpty().contains("secret"))
        }
        for (endpoint in listOf("http://127.0.0.1:11434/v1/chat/completions", "http://localhost:11434/v1/chat/completions", "http://[::1]:11434/v1/chat/completions", "https://example.com/chat/completions")) {
            ModelClient("", "model", "low", "system", ModelProvider.CUSTOM, endpoint = endpoint)
        }
        val error = runCatching { ModelClient("secret\nkey", "model", "low", "system") }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertFalse(error!!.message.orEmpty().contains("secret"))
    }

    @Test fun providerDefaultsRetainAnthropicSecretAndSelectExpectedProtocols() {
        assertEquals(ModelProvider.ANTHROPIC, ModelProvider.fromId("unknown"))
        assertEquals("anthropic_api_key", ModelProvider.ANTHROPIC.keyName)
        assertEquals(ModelProvider.OLLAMA, ModelProvider.fromId("ollama"))
        assertEquals("openrouter/free", ModelProvider.OPENROUTER.defaultModel)
        assertEquals("phi4-mini", ModelProvider.OLLAMA.defaultModel)
        assertFalse(ModelProvider.OLLAMA.requiresKey)
        assertEquals(ModelProvider.entries.size, ModelProvider.entries.map { it.keyName }.toSet().size)
    }

    private fun client(server: MockWebServer, provider: ModelProvider = ModelProvider.GROQ, key: String = "test-key") =
        ModelClient(key, "test-model", "low", "system prompt", provider, endpoint = server.url("/chat/completions").toString())

    private fun toolMessage(name: String, arguments: String): JSONObject = JSONObject()
        .put("role", "assistant").put("content", JSONObject.NULL)
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-one").put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments))))

    private fun response(message: JSONObject, reason: String = "tool_calls") = MockResponse()
        .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", reason))).toString())
}
