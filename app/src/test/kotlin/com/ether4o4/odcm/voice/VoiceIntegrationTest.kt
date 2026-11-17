package com.ether4o4.odcm.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.ether4o4.odcm.agent.AgentService
import com.ether4o4.odcm.ui.VoiceActivity
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceIntegrationTest {
    private fun result(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }

    @Test fun micRequestsPermissionAndDenialNeverStartsAService() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        Robolectric.buildActivity(VoiceActivity::class.java).setup().use { c ->
            val activity = c.get()
            assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            activity.onRequestPermissionsResult(41, arrayOf(Manifest.permission.RECORD_AUDIO), intArrayOf(PackageManager.PERMISSION_DENIED))
            assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedService)
        }
    }

    @Test fun bootstrapWaitsForMicrophoneForegroundAcknowledgement() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        Robolectric.buildActivity(VoiceActivity::class.java).setup().use { c ->
            val activity = c.get()
            val request = shadowOf(activity).nextStartedService
            assertEquals(LiveVoiceService::class.java.name, request.component?.className)
            assertFalse(activity.isFinishing)
            assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            @Suppress("DEPRECATION")
            val ack = request.getParcelableExtra(LiveVoiceService.EXTRA_READY) as android.os.ResultReceiver
            ack.send(android.app.Activity.RESULT_OK, null)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
        }
    }

    @Test fun missingVoiceMetadataLoadsAnInstalledLanguageBeforeRejectingPlayback() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.getDefault())
        val output = VoiceOutput(RuntimeEnvironment.getApplication())
        output.speak("Local voice")
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNull(tts.voice)
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Local voice", shadowOf(tts).lastSpokenText)
        output.close()
    }

    @Test fun initializationTimeoutReportsFailureAndNeverStartsListening() {
        var ready = false
        var error = ""
        val output = VoiceOutput(RuntimeEnvironment.getApplication(), onFailure = { error = it })
        output.prepare { ready = true }
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(16))
        assertFalse(ready)
        assertTrue(error.isNotBlank())
        assertTrue(shadowOf(tts).isShutdown)
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ready)
    }

    @Test fun offlineTtsQueuesBeforeInitializationAndStopsOnClose() {
        val output = VoiceOutput(RuntimeEnvironment.getApplication())
        output.speak("Hello Capo")
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        val shadow = shadowOf(tts)
        tts.voice = Voice("af_heart", Locale.US, 400, 100, false, emptySet())
        shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Hello Capo", shadow.lastSpokenText)
        output.close()
        assertTrue(shadow.isStopped)
        assertTrue(shadow.isShutdown)
        output.speak("Must not play")
        assertEquals("Hello Capo", shadow.lastSpokenText)
    }

    @Test fun networkOnlyTtsIsNotSilentlyUsedAndStopClearsPendingSpeech() {
        val output = VoiceOutput(RuntimeEnvironment.getApplication())
        output.speak("Discard on stop")
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        val shadow = shadowOf(tts)
        tts.voice = Voice("remote", Locale.US, 400, 100, true, emptySet())
        output.stop()
        shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        output.speak("Do not send to network")
        assertNull(shadow.lastSpokenText)
        output.close()
    }
}
