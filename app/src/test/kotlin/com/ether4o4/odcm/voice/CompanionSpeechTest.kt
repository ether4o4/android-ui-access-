package com.ether4o4.odcm.voice

import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.ether4o4.odcm.agent.CompanionService
import com.ether4o4.odcm.agent.Conversation
import com.ether4o4.odcm.data.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionSpeechTest {
    @Test fun failedOneOffVoiceReinitializesOnlyWhenAnotherReplyArrives() {
        val app = RuntimeEnvironment.getApplication()
        Settings(app).bundledVoice = false
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(CompanionService::class.java).create().startCommand(0, 1)
        val main = shadowOf(Looper.getMainLooper())
        try {
            assertTrue(CompanionService.isRunning)
            CompanionService.speakReply("First reply")
            val failed = ShadowTextToSpeech.getLastTextToSpeechInstance()
            shadowOf(failed).onInitListener.onInit(TextToSpeech.ERROR)
            main.idle()
            assertTrue(shadowOf(failed).isShutdown)
            assertNull(shadowOf(failed).lastSpokenText)
            assertTrue(Conversation.state.value.messages.any { it.role == "notice" && "Android voice could not start" in it.text })
            // Failure reports once and does not automatically replay or switch engines.
            assertSame(failed, ShadowTextToSpeech.getLastTextToSpeechInstance())
            assertTrue(CompanionService.isRunning)

            CompanionService.speakReply("Second reply")
            val recovered = ShadowTextToSpeech.getLastTextToSpeechInstance()
            assertNotSame(failed, recovered)
            recovered.voice = Voice("test-offline", Locale.US, 400, 100, false, emptySet())
            shadowOf(recovered).onInitListener.onInit(TextToSpeech.SUCCESS)
            main.idle()
            assertEquals("Second reply", shadowOf(recovered).lastSpokenText)

            CompanionService.speakReply("Third reply")
            main.idle()
            assertSame(recovered, ShadowTextToSpeech.getLastTextToSpeechInstance())
            assertEquals("Third reply", shadowOf(recovered).lastSpokenText)
        } finally {
            controller.destroy()
            ShadowSettings.setCanDrawOverlays(false)
            Conversation.finish()
        }
    }
}
