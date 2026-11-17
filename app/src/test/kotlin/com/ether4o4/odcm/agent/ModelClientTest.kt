package com.ether4o4.odcm.agent

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelClientTest {
    @Test fun cancellingDecisionCancelsNetworkAndDoesNotRetry() = runBlocking {
        val transport = FakeTransport()
        val client = client(transport)
        val decision = async(start = CoroutineStart.UNDISPATCHED) { client.next() }
        val call = transport.calls.single()

        withTimeout(1000) { decision.cancelAndJoin() }
        assertTrue(call.isCanceled())
        assertTrue(decision.isCancelled)
        // OkHttp may report its cancellation after the coroutine is already gone.
        call.fail(IOException("Canceled"))
        yield()
        assertEquals(1, transport.calls.size)
    }

    @Test fun cancellationInterruptsRetryDelay() = runBlocking {
        val transport = FakeTransport()
        val decision = async(start = CoroutineStart.UNDISPATCHED) { client(transport).next() }
        transport.calls.single().respond(429, "{}")
        yield() // Let the response enter the retry delay.
        withTimeout(1000) { decision.cancelAndJoin() }
        assertEquals(1, transport.calls.size)
    }

    @Test fun singleToolRequestParsesOneDecision() = runBlocking {
        val transport = FakeTransport()
        val client = client(transport)
        client.addUserText("Task: open Gmail")
        val decision = async(start = CoroutineStart.UNDISPATCHED) { client.next() }
        val call = transport.calls.single()
        val request = JSONObject(Buffer().also { call.request().body!!.writeTo(it) }.readUtf8())
        assertTrue(request.getJSONObject("tool_choice").getBoolean("disable_parallel_tool_use"))
        assertEquals("auto", request.getJSONObject("tool_choice").getString("type"))
        call.respond(200, """{"content":[{"type":"tool_use","id":"one","name":"open_app","input":{"name":"Gmail"}}],"stop_reason":"tool_use"}""")

        val result = decision.await()
        assertEquals("open_app", result.toolName)
        assertEquals("Gmail", result.toolArgs["name"])
        assertFalse(call.isCanceled())
    }

    @Test fun multipleActionsAreRejectedBeforeTheyReachRunner() = runBlocking {
        val transport = FakeTransport()
        // Catch inside async so an expected failure does not cancel the test scope.
        val decision = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { client(transport).next() }
        }
        transport.calls.single().respond(200, """{"content":[{"type":"tool_use","id":"one","name":"home","input":{}},{"type":"tool_use","id":"two","name":"back","input":{}}]}""")
        assertTrue(decision.await().exceptionOrNull() is ModelException)
    }

    private fun client(transport: Call.Factory) = ModelClient("test-key-${System.identityHashCode(transport)}", "test-model", "low", "test", callFactory = transport)

    private class FakeTransport : Call.Factory {
        val calls = mutableListOf<FakeCall>()
        override fun newCall(request: Request): Call = FakeCall(request).also { calls += it }
    }

    private class FakeCall(private val request: Request) : Call {
        private lateinit var callback: Callback
        private var cancelled = false
        private var executed = false
        override fun request(): Request = request
        override fun execute(): Response = error("Expected asynchronous transport")
        override fun enqueue(responseCallback: Callback) { executed = true; callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(request)

        fun fail(error: IOException) = callback.onFailure(this, error)

        fun respond(code: Int, payload: String) = callback.onResponse(
            this,
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Test response")
                .body(payload.toResponseBody("application/json".toMediaType())).build(),
        )
    }
}
