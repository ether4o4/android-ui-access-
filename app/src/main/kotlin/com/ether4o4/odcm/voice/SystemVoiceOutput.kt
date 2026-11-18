package com.ether4o4.odcm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ether4o4.odcm.agent.Conversation
import java.util.Locale

/** Uses the selected Android engine. Text is never sent to a network-only voice. */
internal class SystemVoiceOutput(
    private val context: Context,
    private val onIdle: () -> Unit = {},
    private val onFailure: (String) -> Unit = { Conversation.notice(it) },
) : SpeechOutput {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private val pending = ArrayDeque<String>()
    private val utterances = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var sequence = 0L
    private var onReady: (() -> Unit)? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change < 0 && utterances.isNotEmpty()) fail("Voice interrupted by another audio app. Tap the mic to reconnect.")
        }.build()
    private var hasFocus = false
    private val timeout = Runnable { fail("Android voice did not finish. Test the selected voice in Android text-to-speech settings, then try again.") }
    override val isBusy: Boolean get() = pending.isNotEmpty() || utterances.isNotEmpty()

    override fun prepare(callback: () -> Unit) {
        if (closed) return
        if (ready) callback() else { onReady = callback; initialize() }
    }

    override fun speak(text: String) {
        if (closed || text.isBlank()) return
        pending.addLast(text)
        if (ready) drain() else initialize()
    }

    private fun initialize() {
        if (engine != null) return
        handler.postDelayed(timeout, 15_000)
        engine = TextToSpeech(context.applicationContext) { result -> handler.post {
            if (closed) return@post
            handler.removeCallbacks(timeout)
            val tts = engine
            if (result != TextToSpeech.SUCCESS || tts == null) {
                fail("Android voice could not start. Select an installed voice in text-to-speech settings.")
                return@post
            }
            // Some engines (including legacy Kaldi builds) publish voices only after language loading.
            if (tts.voice == null) tts.setLanguage(Locale.getDefault())
            if (tts.voice?.isNetworkConnectionRequired != false) {
                val local = tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
                val chosen = local.firstOrNull { it.locale.language == Locale.getDefault().language }
                    ?: local.firstOrNull { it.locale.language == Locale.ENGLISH.language }
                if (chosen != null) tts.voice = chosen
            }
            if (tts.voice?.isNetworkConnectionRequired != false) {
                fail("Select a downloaded offline voice in Android text-to-speech settings, then tap the mic again.")
                return@post
            }
            ready = true
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { handler.post { if (id in utterances) armTimeout() } }
                override fun onDone(id: String?) { handler.post {
                    if (!utterances.remove(id)) return@post
                    if (utterances.isEmpty() && pending.isEmpty()) {
                        handler.removeCallbacks(timeout); releaseFocus(); onIdle()
                    } else armTimeout()
                } }
                @Deprecated("Android compatibility callback")
                override fun onError(id: String?) { handler.post {
                    if (id in utterances) fail("Voice playback failed. Check Android text-to-speech settings.")
                } }
            })
            val callback = onReady; onReady = null
            drain()
            if (!closed) callback?.invoke()
        } }
    }

    private fun drain() {
        val tts = engine ?: return
        if (pending.isEmpty()) return
        if (!hasFocus) {
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasFocus) { fail("Another app is using audio. Finish that audio session, then tap the mic."); return }
        }
        while (pending.isNotEmpty() && !closed) {
            // Short chunks bound synthesis latency and give every chunk a playback watchdog.
            for (chunk in pending.removeFirst().chunked(minOf(800, TextToSpeech.getMaxSpeechInputLength()))) {
                val id = "odcm-${++sequence}"
                utterances.add(id)
                armTimeout()
                if (tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
                    fail("Voice playback could not start. Check the selected Android voice."); return
                }
            }
        }
    }
    private fun armTimeout() { handler.removeCallbacks(timeout); handler.postDelayed(timeout, 120_000) }
    private fun releaseFocus() { if (hasFocus) { hasFocus = false; audio.abandonAudioFocusRequest(focus) } }
    private fun fail(message: String) { if (!closed) { close(); onFailure(message) } }
    override fun stop() {
        pending.clear(); utterances.clear(); onReady = null
        handler.removeCallbacks(timeout); engine?.stop(); releaseFocus()
    }
    override fun close() { closed = true; stop(); engine?.shutdown(); engine = null; ready = false; handler.removeCallbacksAndMessages(null) }
}
