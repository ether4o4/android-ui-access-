package com.ether4o4.odcm.voice

/** One microphone gesture may dispatch at most one final transcript, never partial results. */
internal class VoiceInputSession {
    private var ended = false
    fun cancel() { ended = true }
    fun complete(candidates: List<String>?): String? {
        if (ended) return null
        ended = true
        return candidates?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4000 }
    }
}
