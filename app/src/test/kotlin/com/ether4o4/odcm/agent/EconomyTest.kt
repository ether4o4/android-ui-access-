package com.ether4o4.odcm.agent

import com.ether4o4.odcm.core.AppCatalog
import com.ether4o4.odcm.data.ModelProvider
import kotlinx.coroutines.*
import okhttp3.Headers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class EconomyTest {
    @Test fun routineChatAndExactCommandsDoNotRequireAClassifierCall() {
        val apps = AppCatalog(listOf(AppCatalog.App("com.test.notes", "Notes")))
        assertFalse(RequestRoute.phone("How's it going?"))
        assertFalse(RequestRoute.phone("Explain why the sky is blue"))
        assertTrue(RequestRoute.phone("What is on my screen?"))
        assertTrue(RequestRoute.phone("/task tell me what you see"))
        assertFalse(RequestRoute.phone("/chat explain phone settings"))
        assertEquals("home", RequestRoute.local("Go home!", apps)?.first)
        assertEquals("com.test.notes", RequestRoute.local("Open Notes", apps)?.second?.get("name"))
        assertNull(RequestRoute.local("Open Notes and delete everything", apps))
        assertNull(RequestRoute.local("Don't go home", apps))
        assertNull(RequestRoute.local("Can you go back?", apps))
        assertNull(RequestRoute.local("/chat go home", apps))
        assertNull(RequestRoute.local("Open Notes", AppCatalog(apps.apps + AppCatalog.App("com.other", "Notes"))))
    }

    @Test fun compactionPreservesGoalCorrectionsPairingAndRecoverableResults() {
        for (anthropic in listOf(false, true)) {
            val h = RequestHistory(anthropic)
            h.messages.put(h.user("Task: read five notes and compare them"))
            for (i in 0..19) {
                h.setUpExchange(i, anthropic)
                if (i == 4) h.messages.put(h.user("User follow-up: do not send anything"))
                h.compact()
            }
            val encoded = h.messages.toString()
            assertTrue(encoded.contains("read five notes and compare them"))
            assertTrue(encoded.contains("do not send anything"))
            assertTrue(encoded.contains("screen-19"))
            assertFalse(encoded.contains("screen-0"))
            assertTrue(h.read(0).contains("full-note-0"))
            assertTrue(encoded.length < 10_000)
            val assistants = (0 until h.messages.length()).count { h.messages.getJSONObject(it).optString("role") == "assistant" }
            assertEquals(3, assistants)
            for (n in 0 until h.messages.length()) {
                val m = h.messages.getJSONObject(n)
                if (m.optString("role") == "tool") assertEquals("assistant", h.messages.getJSONObject(n - 1).getString("role"))
            }
        }
    }
    private fun RequestHistory.setUpExchange(i: Int, anthropic: Boolean) {
        addScreen("screen-$i")
        val a = if (anthropic) JSONObject().put("role", "assistant").put("content", JSONArray().put(
            JSONObject().put("type", "tool_use").put("name", "read_vault").put("id", "t$i").put("input", JSONObject().put("path", "note-$i"))))
        else JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "t$i").put("function", JSONObject().put("name", "read_vault").put("arguments", "{\"path\":\"note-$i\"}"))))
        messages.put(a)
        messages.put(if (anthropic) JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "tool_result").put("tool_use_id", "t$i").put("content", "full-note-$i")))
            else JSONObject().put("role", "tool").put("tool_call_id", "t$i").put("content", "full-note-$i"))
    }

    @Test fun retryAfterSecondsDateAndStructuredDelayAreRespected() {
        assertEquals(90_000L, ProviderCooldown.parse(429, Headers.headersOf("Retry-After", "90"), "{}").waitMs)
        val now = 1_700_000_000_000L
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 90_000).atZone(ZoneOffset.UTC))
        assertEquals(90_000L, ProviderCooldown.parse(429, Headers.headersOf("Retry-After", date), "{}", now).waitMs)
        assertEquals(12_000L, ProviderCooldown.parse(429, Headers.headersOf(), """{"error":{"details":[{"retryDelay":"12s"}]}}""").waitMs)
        assertTrue(ProviderCooldown.parse(429, Headers.headersOf(), """{"error":{"code":"insufficient_quota"}}""").quota)
    }

    @Test fun streamed429RetriesBeforeTextAndCapturesUsage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1").setBody("{}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":20,\"completion_tokens_details\":{\"reasoning_tokens\":5}}}\n\n" + "data: [DONE]\n\n"))
            val parts = mutableListOf<String>()
            val statuses = mutableListOf<String>()
            val c = ModelClient("key", "glm-4.7-flash", "low", "chat", ModelProvider.ZAI,
                endpoint = server.url("/chat").toString(), onTextDelta = { parts += it }, allowTools = false, onStatus = { statuses += it })
            assertEquals("hello", c.next().plainText)
            assertEquals(listOf("hello"), parts)
            assertEquals(2, server.requestCount)
            assertEquals(1, c.usage.retries)
            assertEquals(120L, c.usage.input)
            assertEquals(20L, c.usage.output)
            assertEquals(5L, c.usage.reasoning)
            assertTrue(statuses.any { "waiting" in it })
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(body.has("tools"))
            assertFalse(body.has("stream_options"))
            assertFalse(body.has("parallel_tool_calls"))
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
            assertTrue(body.getInt("max_tokens") < 4096)
        }
    }

    @Test fun glmAcceptsStructuredToolArgumentsAndRequestsToolStreaming() = runBlocking {
        for (streaming in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", if (streaming) "text/event-stream" else "application/json").setBody(if (streaming)
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"home\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
            else """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"t1","type":"function","function":{"name":"home","arguments":{}}}]},"finish_reason":"tool_calls"}]}"""))
            val c = ModelClient("key", "glm-4.7-flash", "low", "control", ModelProvider.ZAI,
                endpoint = server.url("/chat").toString(), onTextDelta = if (streaming) ({ _ -> }) else null)
            assertEquals("home", c.next().toolName)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(body.has("stream_options"))
            assertFalse(body.has("parallel_tool_calls"))
            assertEquals(streaming, body.optBoolean("tool_stream"))
        }
    }

    @Test fun longCooldownAndQuotaDoNotSendImmediateRetriesOrLeakErrorText() = runBlocking {
        for (quota in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120")
                .setBody(if (quota) """{"error":{"code":"insufficient_quota","message":"private-key"}}""" else "private-key"))
            val c = ModelClient("key", "model", "low", "chat", ModelProvider.GROQ, endpoint = server.url("/chat").toString(), onTextDelta = {})
            val error = runCatching { c.next() }.exceptionOrNull()
            assertTrue(error is ModelException)
            assertFalse(error!!.message!!.contains("private-key"))
            assertEquals(1, server.requestCount)
            assertTrue(runCatching { c.next() }.exceptionOrNull() is ModelException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cooldownCanBeCancelledBeforeAnotherRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "40"))
            val waiting = CompletableDeferred<Unit>()
            val c = ModelClient("key", "model", "low", "chat", ModelProvider.GROQ, endpoint = server.url("/chat").toString(), onTextDelta = {}, onStatus = { if ("waiting" in it) waiting.complete(Unit) })
            val job = launch { c.next() }
            withTimeout(2000) { waiting.await() }
            withTimeout(1000) { job.cancelAndJoin() }
            assertEquals(1, server.requestCount)
        }
    }
}
