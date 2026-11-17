package com.ether4o4.odcm.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ether4o4.odcm.agent.AgentService
import com.ether4o4.odcm.agent.CompanionService
import com.ether4o4.odcm.agent.Conversation
import com.ether4o4.odcm.voice.VoiceInputSession

/** Visible, user-started mic capture: Android owns permission and the microphone indicator. */
class VoiceActivity : Activity(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var session = VoiceInputSession()
    private var listening = false
    private var requestingPermission = false
    private var submitted = false
    private lateinit var status: TextView
    private lateinit var retry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CompanionService.hideForVoice()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        status = TextView(this).apply { textSize = 18f; text = "Starting microphone…"; gravity = Gravity.CENTER; setPadding(0, 12, 0, 24) }
        body.addView(status)
        retry = Button(this).apply { text = "Try again"; visibility = android.view.View.GONE; setOnClickListener { begin() } }
        body.addView(retry)
        body.addView(Button(this).apply { text = "Cancel"; setOnClickListener { session.cancel(); finish() } })
        setContentView(body)
        // Never resume or dispatch an old transcript after process/activity recreation.
        if (savedInstanceState == null) begin() else showError("Tap Try again to start a new recording.")
    }

    private fun begin() {
        if (listening || isFinishing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestingPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
            return
        }
        val local = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (!local && !SpeechRecognizer.isRecognitionAvailable(this)) {
            showError("No Android speech recognizer is installed. You can still type in the chat dropdown.")
            return
        }
        recognizer?.destroy()
        session = VoiceInputSession()
        submitted = false
        retry.visibility = android.view.View.GONE
        status.text = if (local) "Listening on device…" else "Listening with Android…\nYour recognizer may use the network."
        try {
            recognizer = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
            val attempt = session
            recognizer!!.setRecognitionListener(object : RecognitionListener by this@VoiceActivity {
                override fun onResults(results: Bundle?) { if (session === attempt) this@VoiceActivity.onResults(results) }
                override fun onError(error: Int) { if (session === attempt) this@VoiceActivity.onError(error) }
                override fun onEndOfSpeech() { if (session === attempt) this@VoiceActivity.onEndOfSpeech() }
            })
            listening = true
            recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
        } catch (_: Exception) { showError("Microphone could not start. Check microphone permission and Android speech settings.") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 41) return
        requestingPermission = false
        if (!isFinishing && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin()
        else showError("Microphone permission is required for voice. Typing remains available.")
    }

    override fun onResults(results: Bundle?) {
        if (submitted || isFinishing) return
        listening = false
        val text = session.complete(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
        if (text == null) { showError("No usable speech captured. Try again."); return }
        submitted = true
        recognizer?.destroy(); recognizer = null
        CompanionService.restoreAfterVoice()
        // Launch only after final recognition; existing TaskRunner policies still govern actions.
        runCatching { AgentService.start(this, text, speakReply = true) }.onFailure {
            Conversation.notice("Could not send the voice request. Try again from the companion.")
        }
        finish()
    }
    private fun showError(message: String) {
        listening = false
        session.cancel()
        recognizer?.cancel()
        status.text = message
        retry.visibility = android.view.View.VISIBLE
    }
    override fun onError(error: Int) {
        if (submitted || isFinishing || !listening) return
        showError(when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap Try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission was denied. Enable it in ODCM app permissions."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech language unavailable. Install its offline speech pack in Android settings."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Android recognition couldn't connect. Install an offline speech pack or try again."
            else -> "Speech recognition stopped ($error). Tap Try again."
        })
    }
    override fun onStop() {
        super.onStop()
        if (!requestingPermission) {
            session.cancel(); listening = false; recognizer?.cancel()
            if (!isFinishing) finish()
        }
    }
    override fun onDestroy() {
        session.cancel(); recognizer?.destroy(); recognizer = null
        CompanionService.restoreAfterVoice()
        super.onDestroy()
    }
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { status.text = "Processing speech…" }
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
