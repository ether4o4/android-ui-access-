package com.ether4o4.odcm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal data class VoicePcm(val samples: FloatArray, val sampleRate: Int)
internal interface VoiceSynthesizer {
    fun prepare()
    fun generate(text: String): VoicePcm
    fun close()
}
internal interface VoicePlayer {
    /** Returns only after the last sample is played, or the turn is cancelled. */
    fun play(pcm: VoicePcm, current: () -> Boolean)
    fun stop()
}

/** Main-thread controller; extraction, inference and blocking playback stay on one worker. */
internal class BundledVoiceOutput(
    context: Context,
    private val onIdle: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val synth: VoiceSynthesizer = KokoroVoice(context),
    private val player: VoicePlayer = PcmVoicePlayer(),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
) : SpeechOutput {
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private var closed = false
    private var pending = 0
    private var prepared = false // worker only
    private val audio = context.getSystemService(AudioManager::class.java)
    private var hasFocus = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(voiceAttributes()).setOnAudioFocusChangeListener { change ->
            if (change < 0 && isBusy) fail("Voice interrupted by another audio app. Tap the mic to reconnect.")
        }.build()
    private val timeout = Runnable { fail("Offline voice took too long. Tap the mic to retry, or select Android voice in ODCM settings.") }
    override val isBusy get() = pending > 0

    override fun prepare(callback: () -> Unit) {
        if (closed) return
        val token = generation.get()
        armTimeout()
        worker.execute {
            if (generation.get() != token) return@execute
            try {
                ensurePrepared()
                handler.post {
                    if (!closed && generation.get() == token) {
                        if (!isBusy) handler.removeCallbacks(timeout)
                        callback()
                    }
                }
            } catch (e: Throwable) { reportFailure(token, e) }
        }
    }

    override fun speak(text: String) {
        if (closed || text.isBlank()) return
        if (!hasFocus) {
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasFocus) { fail("Another app is using audio. Finish that audio session, then tap the mic."); return }
        }
        pending++
        val token = generation.get()
        armTimeout()
        worker.execute {
            val current = { generation.get() == token }
            if (!current()) return@execute
            try {
                ensurePrepared()
                for (chunk in chunks(text)) {
                    if (!current()) break
                    handler.post { if (!closed && current()) armTimeout() }
                    val pcm = synth.generate(chunk)
                    if (current()) player.play(pcm, current)
                }
                handler.post {
                    if (!closed && current()) {
                        pending--
                        if (!isBusy) { handler.removeCallbacks(timeout); releaseFocus(); onIdle() }
                    }
                }
            } catch (e: Throwable) { reportFailure(token, e) }
        }
    }

    private fun ensurePrepared() { if (!prepared) { synth.prepare(); prepared = true } }
    private fun reportFailure(token: Long, error: Throwable) {
        handler.post {
            if (!closed && generation.get() == token)
                fail("Bundled Michael voice failed (${error.javaClass.simpleName}). Try Android voice in ODCM settings.")
        }
    }
    private fun armTimeout() { handler.removeCallbacks(timeout); handler.postDelayed(timeout, 120_000) }
    private fun releaseFocus() { if (hasFocus) { hasFocus = false; audio.abandonAudioFocusRequest(focus) } }
    private fun fail(message: String) { if (!closed) { close(); onFailure(message) } }
    override fun stop() {
        generation.incrementAndGet()
        pending = 0
        handler.removeCallbacks(timeout)
        player.stop()
        releaseFocus()
    }
    override fun close() {
        if (closed) return
        closed = true
        stop()
        // Never free JNI state while synthesis is still using it.
        worker.execute { synth.close() }
        worker.shutdown()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        internal fun chunks(text: String): List<String> {
            val result = mutableListOf<String>()
            var remaining = text.trim()
            while (remaining.isNotEmpty()) {
                val limit = minOf(240, remaining.length)
                val end = if (limit == remaining.length) limit else {
                    val sentence = remaining.take(limit).lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                    if (sentence >= 40) sentence + 1
                    else remaining.lastIndexOf(' ', limit - 1).takeIf { it > 0 } ?: limit
                }
                result.add(remaining.take(end))
                remaining = remaining.drop(end).trimStart()
            }
            return result
        }
    }
}

internal fun voiceAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
