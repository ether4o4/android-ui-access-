package com.ether4o4.odcm.agent

import com.ether4o4.odcm.data.ModelProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MessageStreamTest {
    @Test fun anthropicEmitsTextBeforeCompletionAndPreservesToolJson() {
        val chunks = mutableListOf<String>()
        val stream = MessageStream(true, chunks::add)
        stream.accept("""{"type":"message_start","message":{}}""")
        stream.accept("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        stream.accept("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""")
        assertEquals(listOf("Hello"), chunks)
        assertFalse(stream.complete)
        stream.accept("""{"type":"content_block_stop","index":0}""")
        stream.accept("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"tap","input":{}}}""")
        stream.accept("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"index\":"}}""")
        stream.accept("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"3}"}}""")
        stream.accept("""{"type":"content_block_stop","index":1}""")
        stream.accept("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
        stream.accept("""{"type":"message_stop"}""")
        assertEquals(3, stream.response().getJSONArray("content").getJSONObject(1).getJSONObject("input").getInt("index"))
    }

    @Test fun unfinishedAndTruncatedStreamsNeverProduceAnAction() {
        val s = MessageStream(false) {}
        s.accept("""{"choices":[{"index":0,"delta":{"content":"partial"},"finish_reason":null}]}""")
        assertThrows(ModelException::class.java) { s.response() }
        s.accept("""{"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}""")
        s.accept("[DONE]")
        assertThrows(ModelException::class.java) { s.response() }
    }

    @Test fun compatibleTextToolsAndOpaqueReasoningAreReassembled() {
        val s = MessageStream(false) {}
        s.accept("""{"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"x","type":"function","function":{"name":"tap","arguments":"{\"index\":"}}],"reasoning_details":[{"index":0,"type":"reasoning.encrypted","data":"abc"}]},"finish_reason":null}]}""")
        s.accept("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"2}"}}],"reasoning_details":[{"index":0,"data":"def"}]},"finish_reason":"tool_calls"}]}""")
        s.accept("[DONE]")
        val m = s.response().getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertEquals("{\"index\":2}", m.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals("abcdef", m.getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
    }

    @Test fun multipleActionsAndStreamErrorsAreRejected() {
        val s = MessageStream(false) {}
        assertThrows(ModelException::class.java) {
            s.accept("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"bad"}]},"finish_reason":null}]}""")
        }
        val error = assertThrows(ModelException::class.java) { MessageStream(true) {}.accept("""{"type":"error","error":{"message":"secret-value"}}""") }
        assertFalse(error.message.orEmpty().contains("secret-value"))
    }

    @Test fun networkStreamsReplyAndChatOnlyOmitsAllActionTools() = runBlocking {
        MockWebServer().use { server ->
            val body = """data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hello "},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"content":"Capo"},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

            """.trimIndent().replace("                data:", "data:")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
            val parts = mutableListOf<String>()
            val client = ModelClient("", "test", "low", "Chat", ModelProvider.OLLAMA,
                endpoint = server.url("/v1/chat/completions").toString(), onTextDelta = parts::add, allowTools = false)
            client.addUserText("Hello")
            assertEquals("Hello Capo", client.next().plainText)
            assertEquals(listOf("Hello ", "Capo"), parts)
            val sent = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(sent.getBoolean("stream"))
            assertFalse(sent.has("tools")); assertFalse(sent.has("tool_choice"))
        }
    }

    @Test fun jsonFallbackStillWorksWithStreamingEnabled() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"role":"assistant","content":"Ready"},"finish_reason":"stop"}]}"""))
            val client = ModelClient("", "test", "low", "Chat", ModelProvider.OLLAMA,
                endpoint = server.url("/").toString(), onTextDelta = {})
            assertEquals("Ready", client.next().plainText)
        }
    }

    @Test fun cancelClosesPendingStream() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = ModelClient("", "test", "low", "Chat", ModelProvider.OLLAMA,
                endpoint = server.url("/").toString(), onTextDelta = {})
            val job = launch { client.next() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
}
