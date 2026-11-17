package com.ether4o4.odcm.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ether4o4.odcm.agent.Conversation

/** Uses the system-selected TTS engine/voice; no HTTP speech endpoint or voice API key. */
internal class VoiceOutput(private val context: Context) {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private val pending = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var sequence = 0L

    fun speak(text: String) {
        if (closed || text.isBlank()) return
        if (!ready) {
            pending.addLast(text)
            if (engine == null) engine = TextToSpeech(context.applicationContext) { result ->
                handler.post {
                    if (!closed) {
                        ready = result == TextToSpeech.SUCCESS
                        if (ready) {
                            engine?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(id: String?) = Unit
                                override fun onDone(id: String?) = Unit
                                @Deprecated("Android compatibility callback")
                                override fun onError(id: String?) { handler.post { if (!closed) Conversation.notice("Voice playback failed. Check Android text-to-speech settings.") } }
                            })
                            val queued = pending.toList(); pending.clear()
                            queued.forEach(::speak)
                        } else {
                            pending.clear()
                            Conversation.notice("Android voice could not start. Select an installed voice in text-to-speech settings.")
                            engine?.shutdown(); engine = null
                        }
                    }
                }
            }
            return
        }
        val tts = engine ?: return
        val voice = tts.voice
        if (voice == null || voice.isNetworkConnectionRequired) {
            Conversation.notice("Select an installed offline voice in Android text-to-speech settings, then try the mic again.")
            return
        }
        val chunks = text.chunked(TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1))
        chunks.forEach { chunk ->
            if (tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, "odcm-${++sequence}") == TextToSpeech.ERROR)
                Conversation.notice("Voice playback could not start. Check the selected Android voice.")
        }
    }
    fun stop() { pending.clear(); engine?.stop() }
    fun close() { closed = true; stop(); engine?.shutdown(); engine = null; ready = false; handler.removeCallbacksAndMessages(null) }
}
