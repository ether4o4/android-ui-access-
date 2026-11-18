package com.ether4o4.odcm.voice

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledVoiceOutputTest {
    private class QueueWorker : AbstractExecutorService() {
        val queue = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { queue.add(command) }
        fun runNext() { queue.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown(); return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
    private class Synth : VoiceSynthesizer {
        var calls = 0
        var released = false
        var fail = false
        override fun prepare() { if (fail) error("bad model") }
        override fun generate(text: String): VoicePcm { calls++; return VoicePcm(floatArrayOf(.2f), 24000) }
        override fun close() { released = true }
    }
    private class Player : VoicePlayer {
        var plays = 0
        var duringPlay: (() -> Unit)? = null
        override fun play(pcm: VoicePcm, current: () -> Boolean) { if (current()) { plays++; duringPlay?.invoke() } }
        override fun stop() = Unit
    }
    @Test fun busyPersistsUntilPlaybackReturnsAndAllQueuedRepliesFinish() {
        val worker = QueueWorker(); val synth = Synth(); val player = Player(); var idle = 0
        val output = BundledVoiceOutput(RuntimeEnvironment.getApplication(), { idle++ }, { fail(it) }, synth, player, worker)
        try {
            player.duringPlay = { assertTrue(output.isBusy); assertEquals(0, idle) }
            output.speak("One."); output.speak("Two.")
            assertEquals(0, synth.calls)
            worker.runNext(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(output.isBusy); assertEquals(0, idle)
            worker.runNext(); shadowOf(Looper.getMainLooper()).idle()
            assertFalse(output.isBusy); assertEquals(1, idle); assertEquals(2, player.plays)
        } finally { output.close(); worker.runNext() }
        assertTrue(synth.released)
    }
    @Test fun stopInvalidatesQueuedPreparationAndSpeechAndLateCompletion() {
        val worker = QueueWorker(); val synth = Synth(); val player = Player(); var callbacks = 0
        val output = BundledVoiceOutput(RuntimeEnvironment.getApplication(), { callbacks++ }, { fail(it) }, synth, player, worker)
        output.prepare { callbacks++ }; output.speak("Old turn")
        output.stop()
        worker.runNext(); worker.runNext(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, callbacks); assertEquals(0, synth.calls); assertFalse(output.isBusy)
        player.duringPlay = { output.stop() }
        output.speak("New turn"); worker.runNext(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, callbacks)
        output.close(); worker.runNext()
        assertTrue(synth.released)
    }
    @Test fun initializationFailureStopsAndReportsOnce() {
        val worker = QueueWorker(); val synth = Synth().apply { fail = true }; val errors = mutableListOf<String>()
        val output = BundledVoiceOutput(RuntimeEnvironment.getApplication(), { fail("Must not resume") }, errors::add, synth, Player(), worker)
        output.speak("Hello"); worker.runNext(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, errors.size); assertFalse(output.isBusy)
        worker.runNext(); assertTrue(synth.released)
        output.speak("Closed"); assertTrue(worker.queue.isEmpty())
    }
    @Test fun splittingKeepsWordsAndBoundsNativeWork() {
        val text = (1..100).joinToString(" ") { "word$it" }
        val chunks = BundledVoiceOutput.chunks(text)
        assertTrue(chunks.all { it.length <= 240 })
        assertEquals(text, chunks.joinToString(" "))
        assertEquals(16, KokoroVoice.MICHAEL)
    }
}
