package com.ether4o4.odcm.voice

import android.Manifest
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.ether4o4.odcm.agent.AgentService
import com.ether4o4.odcm.agent.Conversation
import com.ether4o4.odcm.control.ControlService
import com.ether4o4.odcm.data.ModelProvider
import com.ether4o4.odcm.data.Settings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Real service/model wiring; recognition and Android TTS are shadows, not hardware audio tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())

    @Test fun ordinaryVoiceConversationRepliesAndRemembersThePreviousTurnWithAccessibilityConnected() {
        MockWebServer().use { server ->
            Settings(app).apply {
                provider = ModelProvider.CUSTOM
                model = "conversation-test"
                endpoint = server.url("/v1/chat/completions").toString()
                bundledVoice = false
                vaultUri = ""
            }
            Conversation.initialize(app)
            Conversation.finish()
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
            val control = Robolectric.buildService(ControlService::class.java).create()
            // Emulate Android's accessibility connection callback, without altering private state.
            ReflectionHelpers.callInstanceMethod<Void>(control.get(), "onServiceConnected")
            assertSame(control.get(), ControlService.get())
            val voice = Robolectric.buildService(LiveVoiceService::class.java).create().startCommand(0, 1)
            try {
                val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
                tts.voice = Voice("test-offline", Locale.US, 400, 100, false, emptySet())
                shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
                main.idle()

                val first = exchange(server, voice.get(), "I had a rough day", "I'm here. What made it rough?")
                assertConversationRequest(first, "I had a rough day")

                val followUp = "It's mostly the stress from my app project."
                val second = exchange(server, voice.get(), followUp, "That sounds draining. Want to talk about it?")
                assertConversationRequest(second, followUp)
                val context = second.getJSONArray("messages").toString()
                assertTrue("The follow-up must include the user's previous turn", context.contains("I had a rough day"))
                assertTrue("The follow-up must include ODCM's previous reply", context.contains("I'm here. What made it rough?"))
                assertEquals("One provider call per conversational turn", 2, server.requestCount)
                assertTrue(LiveVoiceService.isRunning)
            } finally {
                LiveVoiceService.stop()
                voice.destroy()
                control.destroy()
                Conversation.finish()
                main.idle()
            }
        }
    }

    private fun exchange(server: MockWebServer, voice: LiveVoiceService, utterance: String, answer: String): JSONObject {
        val chunk = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("index", 0)
            .put("delta", JSONObject().put("role", "assistant").put("content", answer))
            .put("finish_reason", "stop")))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $chunk\n\ndata: [DONE]\n\n"))
        val previousMic = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        shadowOf(previousMic).triggerOnResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(utterance))
        })
        main.idle()
        val intent = shadowOf(voice).nextStartedService
        assertNotNull("Final speech must start an agent turn", intent)
        assertEquals(utterance, intent.getStringExtra(AgentService.EXTRA_TASK))
        assertTrue(intent.getBooleanExtra(AgentService.EXTRA_SPEAK_REPLY, false))
        assertTrue(shadowOf(previousMic).isDestroyed)

        // Do not call LiveVoiceService.reply/taskEnded directly: AgentService must deliver both.
        val agent = Robolectric.buildService(AgentService::class.java, intent).create().startCommand(0, 1)
        try {
            await("AgentService did not deliver and finish its spoken reply") {
                shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText == answer &&
                    !Conversation.state.value.busy
            }
            assertEquals(answer, Conversation.state.value.messages.last { it.role == "assistant" }.text)
            assertSame("The next mic must wait for the playback cooldown", previousMic, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            main.idleFor(Duration.ofMillis(500))
            val nextMic = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
            assertNotSame("Voice must listen again after the reply", previousMic, nextMic)
            assertFalse(shadowOf(nextMic).isDestroyed)
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull("The real ModelClient must send the conversation", request)
            return JSONObject(request!!.body.readUtf8())
        } finally {
            agent.destroy()
        }
    }

    private fun assertConversationRequest(request: JSONObject, utterance: String) {
        assertFalse("Conversation must not expose device tools", request.has("tools"))
        assertFalse(request.has("tool_choice"))
        assertFalse(request.has("parallel_tool_calls"))
        assertTrue(request.getBoolean("stream"))
        val messages = request.getJSONArray("messages")
        assertEquals(utterance, messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    private fun await(message: String, condition: () -> Boolean) {
        // OkHttp and the usage writer are real background workers; pump only current main work.
        // Advancing virtual time here would accidentally trigger microphone/voice watchdogs.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            main.idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("$message. Conversation status: ${Conversation.state.value}")
    }
}
