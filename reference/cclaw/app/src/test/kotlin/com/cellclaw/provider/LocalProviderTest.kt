package com.cellclaw.provider

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalProviderTest {

    @Test
    fun `endpoint url appends v1 chat completions`() {
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            LocalProvider.chatCompletionsUrl("http://127.0.0.1:8080")
        )
    }

    @Test
    fun `endpoint url tolerates trailing slash`() {
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            LocalProvider.chatCompletionsUrl("http://127.0.0.1:8080/")
        )
    }

    @Test
    fun `endpoint url tolerates existing v1 suffix`() {
        assertEquals(
            "http://192.168.1.5:11434/v1/chat/completions",
            LocalProvider.chatCompletionsUrl("http://192.168.1.5:11434/v1")
        )
        assertEquals(
            "http://192.168.1.5:11434/v1/chat/completions",
            LocalProvider.chatCompletionsUrl("http://192.168.1.5:11434/v1/")
        )
    }

    @Test
    fun `endpoint url trims whitespace`() {
        assertEquals(
            "http://localhost:8080/v1/chat/completions",
            LocalProvider.chatCompletionsUrl(" http://localhost:8080 ")
        )
    }

    @Test
    fun `configure falls back to defaults on blank values`() {
        val provider = LocalProvider()
        provider.configure("", "")
        // No exception, provider still usable with defaults
        assertEquals("local", provider.name)
    }

    @Test
    fun `stream surfaces error when server unreachable`() = runBlocking {
        val provider = LocalProvider()
        // Reserved-for-documentation address that never accepts connections
        provider.configure("http://192.0.2.0:9", "test-model")
        val events = provider.stream(
            CompletionRequest(systemPrompt = "sys", messages = listOf(Message.user("hi")))
        ).toList()
        assertTrue("expected an Error or Complete terminal event", events.isNotEmpty())
        val terminal = events.last()
        assertTrue(
            "unreachable server should not yield a successful completion",
            terminal is StreamEvent.Error ||
                (terminal is StreamEvent.Complete && terminal.response.stopReason == StopReason.ERROR)
        )
    }
}
