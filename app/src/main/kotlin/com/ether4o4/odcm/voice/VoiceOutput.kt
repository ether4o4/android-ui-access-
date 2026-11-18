package com.ether4o4.odcm.voice

import android.content.Context
import com.ether4o4.odcm.agent.Conversation
import com.ether4o4.odcm.data.Settings

internal interface SpeechOutput {
    val isBusy: Boolean
    fun prepare(callback: () -> Unit)
    fun speak(text: String)
    fun stop()
    fun close()
}

/** Both conversation and one-off replies use the same selected offline output. */
internal class VoiceOutput(
    context: Context,
    onIdle: () -> Unit = {},
    onFailure: (String) -> Unit = { Conversation.notice(it) },
) : SpeechOutput by if (Settings(context).bundledVoice) {
    BundledVoiceOutput(context.applicationContext, onIdle, onFailure)
} else {
    SystemVoiceOutput(context.applicationContext, onIdle, onFailure)
}
