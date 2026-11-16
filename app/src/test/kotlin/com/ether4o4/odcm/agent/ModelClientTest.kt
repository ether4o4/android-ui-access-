package com.ether4o4.odcm.agent

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelClientTest {
    @Test fun requestsSingleActionAndHandlesPlainTextReplies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Here is the answer"}],"stop_reason":"end_turn"}"""))
            val client = ModelClient("test-key", "test-model", "low", "test", server.url("/messages").toString())
            client.addUserText("Read my notes")
            val decision = client.next()
            assertNull(decision.toolName); assertEquals("Here is the answer", decision.plainText)
            val request = server.takeRequest()
            assertEquals("2026-10-21", request.getHeader("anthropic-version"))
            val choice = JSONObject(request.body.readUtf8()).getJSONObject("tool_choice")
            assertEquals("auto", choice.getString("type")); assertTrue(choice.getBoolean("disable_parallel_tool_use"))
        }
    }
    @Test fun cancelDoesNotWaitForAStalledApiResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(3, TimeUnit.SECONDS))
            val client = ModelClient("test", "test", "low", "test", server.url("/messages").toString())
            client.addUserText("task")
            val request = launch { client.next() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(1000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        }
    }
    @Test fun rejectsUnexpectedMultipleActionsBeforeAnyCanRun() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"tool_use","id":"a","name":"tap","input":{"index":0}},{"type":"tool_use","id":"b","name":"tap","input":{"index":1}}]}"""))
            val client = ModelClient("test", "test", "low", "test", server.url("/messages").toString())
            client.addUserText("task")
            assertTrue(runCatching { client.next() }.isFailure)
        }
    }
}
