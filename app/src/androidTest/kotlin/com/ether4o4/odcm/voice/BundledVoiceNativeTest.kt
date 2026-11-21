package com.ether4o4.odcm.voice

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ether4o4.odcm.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class BundledVoiceNativeTest {
    @Test fun bundledOutputPreparesThenDrainsSpeechBeforeReportingIdle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(com.ether4o4.odcm.data.Settings(context).bundledVoice)
        // Android 15 grants audio focus to a foreground app/service. Keep the real app visible.
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val prepared = CountDownLatch(1)
        val idle = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        var output: VoiceOutput? = null
        try {
            instrumentation.runOnMainSync {
                // Exercise the default selector, wrapper, native synthesis, focus and AudioTrack.
                output = VoiceOutput(activity, onIdle = { idle.countDown() }, onFailure = {
                    failure.compareAndSet(null, it)
                    prepared.countDown()
                    idle.countDown()
                })
                output!!.prepare { prepared.countDown() }
            }
            assertTrue("Bundled output did not prepare", prepared.await(90, TimeUnit.SECONDS))
            assertNull("Bundled preparation failed: ${failure.get()}", failure.get())
            instrumentation.runOnMainSync {
                assertFalse(output!!.isBusy)
                output!!.speak("I'm here, Capo. We can just talk.")
                assertNull("Bundled playback could not start: ${failure.get()}", failure.get())
                assertTrue("Speech must stay busy until native playback drains", output!!.isBusy)
            }
            assertTrue("Bundled playback did not finish", idle.await(90, TimeUnit.SECONDS))
            assertNull("Bundled playback failed: ${failure.get()}", failure.get())
            instrumentation.runOnMainSync { assertFalse(output!!.isBusy) }
            // This verifies completion through AudioTrack, not human audibility or phone routing.
        } finally {
            instrumentation.runOnMainSync { output?.close(); activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun packagedMichaelSynthesizesOfflineAndReopensFromInstalledAssets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(com.ether4o4.odcm.data.Settings(context).bundledVoice)
        repeat(2) {
            val voice = KokoroVoice(context)
            try {
                voice.prepare()
                val pcm = voice.generate(if (it == 0) "Hello Capo. I am ready." else "Yes.")
                assertEquals(24000, pcm.sampleRate)
                assertTrue(pcm.samples.size > 1000)
                assertTrue(pcm.samples.all { sample -> sample.isFinite() })
                assertTrue(pcm.samples.any { sample -> abs(sample) > 0.01f })
                assertTrue(pcm.samples.count { sample -> abs(sample) >= 0.99f } < pcm.samples.size / 100)
                PcmVoicePlayer().play(pcm) { true }
            } finally { voice.close() }
        }
    }
}
