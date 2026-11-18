package com.ether4o4.odcm.voice

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock

internal class PcmVoicePlayer : VoicePlayer {
    private val lock = Any()
    private var active: AudioTrack? = null

    override fun play(pcm: VoicePcm, current: () -> Boolean) {
        if (!current()) return
        val minimum = AudioTrack.getMinBufferSize(pcm.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0) { "Unsupported audio format" }
        val track = AudioTrack.Builder().setAudioAttributes(voiceAttributes())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(pcm.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, 16384)).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            synchronized(lock) {
                if (!current()) return
                active = track
                check(track.state == AudioTrack.STATE_INITIALIZED)
                track.play()
            }
            var offset = 0
            val deadline = SystemClock.elapsedRealtime() + 15_000 + pcm.samples.size * 1000L / pcm.sampleRate
            while (current() && offset < pcm.samples.size) {
                val written = track.write(pcm.samples, offset, minOf(4096, pcm.samples.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                check(written >= 0) { "Audio write failed: $written" }
                offset += written
                check(SystemClock.elapsedRealtime() < deadline) { "Audio playback timed out" }
                if (written == 0) Thread.sleep(10)
            }
            // A completed write only means queued audio, not audible completion.
            while (current() && track.playbackHeadPosition.toLong() < offset) {
                check(SystemClock.elapsedRealtime() < deadline) { "Audio drain timed out" }
                Thread.sleep(10)
            }
        } finally {
            synchronized(lock) {
                if (active === track) active = null
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    override fun stop() {
        synchronized(lock) { active?.let { runCatching { it.pause(); it.flush() } } }
    }
}
