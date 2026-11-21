package com.ether4o4.odcm.voice

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.ether4o4.odcm.agent.AgentService
import com.ether4o4.odcm.agent.Conversation
import org.junit.After
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
import java.time.Duration
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveVoiceServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private fun result(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }
    private fun start() = Robolectric.buildService(LiveVoiceService::class.java).also {
        com.ether4o4.odcm.data.Settings(app).bundledVoice = false
        Conversation.initialize(app)
        Conversation.finish()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        it.create().startCommand(0, 1)
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        tts.voice = Voice("am_michael", Locale.US, 400, 100, false, emptySet())
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        main.idle()
    }
    @After fun cleanup() { LiveVoiceService.stop(); Conversation.finish() }

    @Test fun finalSpeechSendsOnceThenNextTurnWaitsForBothTaskAndPlayback() {
        val controller = start()
        try {
            val service = controller.get()
            val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
            val mic = shadowOf(first)
            mic.triggerOnPartialResults(result("Hello")); main.idle()
            assertNull(shadowOf(service).nextStartedService)
            mic.triggerOnResults(result("Hello Capo")); main.idle()
            val request = shadowOf(service).nextStartedService
            assertEquals("Hello Capo", request.getStringExtra(AgentService.EXTRA_TASK))
            val token = request.getStringExtra(AgentService.EXTRA_VOICE_TURN)!!
            assertTrue(request.getBooleanExtra(AgentService.EXTRA_SPEAK_REPLY, false))
            assertTrue(mic.isDestroyed)
            mic.triggerOnResults(result("Duplicate")); main.idle()
            assertNull(shadowOf(service).nextStartedService)
            LiveVoiceService.reply(token, "Hello back")
            main.idle() // Simulate full playback, but task is still running.
            main.idleFor(Duration.ofSeconds(1))
            assertSame(first, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            LiveVoiceService.taskEnded(token)
            main.idleFor(Duration.ofMillis(500))
            assertNotSame(first, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            assertFalse(shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isDestroyed)
            assertEquals("Hello back", shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
        } finally { controller.destroy() }
    }

    @Test fun taskFinishingBeforeAudioCannotRestartMicUntilPlaybackCompletes() {
        val controller = start()
        try {
            val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
            shadowOf(first).triggerOnResults(result("Hello")); main.idle()
            val token = shadowOf(controller.get()).nextStartedService.getStringExtra(AgentService.EXTRA_VOICE_TURN)!!
            LiveVoiceService.reply(token, "Response")
            LiveVoiceService.taskEnded(token)
            assertSame(first, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            assertTrue(shadowOf(first).isDestroyed)
            main.idleFor(Duration.ofMillis(500))
            assertNotSame(first, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        } finally { controller.destroy() }
    }

    @Test fun stopInvalidatesLateTranscriptsRepliesAndQueuedResume() {
        val controller = start()
        try {
            val mic = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            mic.triggerOnResults(result("First")); main.idle()
            val token = shadowOf(controller.get()).nextStartedService.getStringExtra(AgentService.EXTRA_VOICE_TURN)!!
            LiveVoiceService.stop()
            LiveVoiceService.reply(token, "Must not speak")
            LiveVoiceService.taskEnded(token)
            mic.triggerOnResults(result("Must not send"))
            main.idleFor(Duration.ofSeconds(1))
            assertFalse(LiveVoiceService.isRunning)
            assertFalse(LiveVoiceService.ownsTurn(token))
            assertNull(shadowOf(controller.get()).nextStartedService)
            assertNull(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
            assertTrue(mic.isDestroyed)
        } finally { controller.destroy() }
    }

    @Test fun nextConversationTurnIgnoresRepliesAndCompletionFromThePreviousTurn() {
        val controller = start()
        try {
            val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
            shadowOf(first).triggerOnResults(result("Hello")); main.idle()
            val firstToken = shadowOf(controller.get()).nextStartedService.getStringExtra(AgentService.EXTRA_VOICE_TURN)!!
            LiveVoiceService.reply(firstToken, "Hello back")
            LiveVoiceService.taskEnded(firstToken)
            main.idleFor(Duration.ofMillis(500))
            assertFalse(LiveVoiceService.ownsTurn(firstToken))

            val second = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
            assertNotSame(first, second)
            shadowOf(second).triggerOnResults(result("Tell me more")); main.idle()
            val secondToken = shadowOf(controller.get()).nextStartedService.getStringExtra(AgentService.EXTRA_VOICE_TURN)!!
            assertNotEquals(firstToken, secondToken)
            LiveVoiceService.reply(firstToken, "Late first reply")
            LiveVoiceService.taskEnded(firstToken)
            main.idleFor(Duration.ofSeconds(1))
            assertEquals("Hello back", shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
            assertTrue(LiveVoiceService.ownsTurn(secondToken))
            assertSame(second, ShadowSpeechRecognizer.getLatestSpeechRecognizer())

            LiveVoiceService.reply(secondToken, "Here is more")
            LiveVoiceService.taskEnded(secondToken)
            main.idleFor(Duration.ofMillis(500))
            assertEquals("Here is more", shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
            assertNotSame(second, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        } finally { controller.destroy() }
    }

    @Test fun emptySpeechRetriesAreBoundedAndNeverCallTheModel() {
        val controller = start()
        try {
            repeat(3) {
                shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                main.idleFor(Duration.ofSeconds(1))
            }
            assertFalse(LiveVoiceService.isRunning)
            assertNull(shadowOf(controller.get()).nextStartedService)
        } finally { controller.destroy() }
    }

    @Test fun screenOffEndsTheMicrophoneSession() {
        val controller = start()
        try {
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); main.idle()
            assertFalse(LiveVoiceService.isRunning)
            assertTrue(shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isDestroyed)
        } finally { controller.destroy() }
    }

    @Test fun stoppedSessionCannotExecuteAnAlreadyQueuedAgentIntent() {
        val controller = start()
        try {
            shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(result("Go home")); main.idle()
            val queued = shadowOf(controller.get()).nextStartedService
            LiveVoiceService.stop()
            val before = Conversation.state.value.messages
            val agent = Robolectric.buildService(AgentService::class.java, queued).create().startCommand(0, 1)
            assertEquals(before, Conversation.state.value.messages)
            agent.destroy()
        } finally { controller.destroy() }
    }

    @Test fun revokedPermissionNeverStartsRecognition() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildService(LiveVoiceService::class.java).create().startCommand(0, 1)
        try {
            assertFalse(LiveVoiceService.isRunning)
            assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        } finally { controller.destroy() }
    }
}
