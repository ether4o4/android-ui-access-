package com.ether4o4.odcm.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceInputSessionTest {
    @Test fun finalRecognitionDispatchesOnlyOnceEvenIfAndroidRepeatsResults() {
        val session = VoiceInputSession()
        assertEquals("Go home", session.complete(listOf(" Go home ", "go back")))
        assertNull(session.complete(listOf("Go home")))
    }
    @Test fun cancelledRecordingCannotDispatchLateResults() {
        val session = VoiceInputSession()
        session.cancel()
        assertNull(session.complete(listOf("Send the message")))
    }
    @Test fun missingBlankAndOversizedTranscriptsCannotRunActions() {
        for (result in listOf(null, emptyList(), listOf("  "), listOf("a".repeat(4001)))) {
            assertNull(VoiceInputSession().complete(result))
        }
    }
}
