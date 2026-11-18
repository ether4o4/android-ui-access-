package com.ether4o4.odcm.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class BundledVoiceNativeTest {
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
