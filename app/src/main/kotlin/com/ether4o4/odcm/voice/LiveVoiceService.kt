package com.ether4o4.odcm.voice

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.ether4o4.odcm.agent.AgentService
import com.ether4o4.odcm.agent.CompanionService
import com.ether4o4.odcm.agent.Conversation
import java.util.UUID

/** A user-started conversation, with a microphone FGS and no recognition activity covering chat. */
class LiveVoiceService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var capture: VoiceInputSession? = null
    private var output: VoiceOutput? = null
    private var active = false
    private var awaitingTask = false
    private var turn: String? = null
    private var emptyAttempts = 0
    private var registered = false
    private var state = "Starting voice"
    private val resume = Runnable { listen() }
    private val captureTimeout = Runnable { end("Microphone timed out. Tap the mic to try again.") }
    private val taskTimeout = Runnable { end("Voice session timed out waiting for a reply. Check the chat dropdown.") }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { end() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ack = intent?.getParcelableExtra(EXTRA_READY) as? ResultReceiver
        if (intent?.action == ACTION_STOP || intent == null) { end(); return START_NOT_STICKY }
        if (active) { ack?.send(Activity.RESULT_OK, null); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ack?.send(Activity.RESULT_CANCELED, null); end("Enable ODCM microphone permission to use voice."); return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            ack?.send(Activity.RESULT_CANCELED, null); end("Open ODCM and tap the mic to start voice."); return START_NOT_STICKY
        }
        // A visible VoiceActivity waits for this acknowledgement before finishing (Android 14+).
        active = true; instance = this
        ack?.send(Activity.RESULT_OK, null)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        if (!getSystemService(PowerManager::class.java).isInteractive) { end(); return START_NOT_STICKY }
        update("Starting voice")
        output = VoiceOutput(this, onIdle = { maybeListen() }, onFailure = { end(it) })
        output?.prepare { if (active) listen() }
        return START_NOT_STICKY
    }

    private fun listen() {
        if (!active || awaitingTask || output?.isBusy == true || capture != null) return
        if (Conversation.state.value.busy) {
            end("Finish or stop the current task, then tap the mic for a conversation."); return
        }
        val local = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (!local && !SpeechRecognizer.isRecognitionAvailable(this)) {
            end("No Android speech recognizer is installed. You can still type in the chat dropdown."); return
        }
        val attempt = VoiceInputSession()
        capture = attempt
        try {
            recognizer = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    if (!active || capture !== attempt) return
                    val text = attempt.complete(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                    releaseCapture()
                    if (text == null) { retrySilence(); return }
                    emptyAttempts = 0
                    awaitingTask = true
                    val token = UUID.randomUUID().toString(); turn = token
                    update("Waiting for reply")
                    handler.postDelayed(taskTimeout, TASK_WATCHDOG_MS)
                    runCatching { AgentService.start(this@LiveVoiceService, text, speakReply = true, voiceTurn = token) }
                        .onFailure { end("Could not send your message. Tap the mic to try again.") }
                }
                override fun onError(error: Int) {
                    if (!active || capture !== attempt) return
                    releaseCapture()
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) retrySilence()
                    else end(when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission was denied. Enable it in ODCM app permissions."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Install your speech language in Android offline recognition settings."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition couldn't connect. Download an offline speech pack or try again."
                        else -> "Speech recognition stopped ($error). Tap the mic to reconnect."
                    })
                }
                override fun onReadyForSpeech(params: Bundle?) { if (capture === attempt) update(if (local) "Listening on device" else "Listening with Android") }
                override fun onEndOfSpeech() { if (capture === attempt) update("Processing speech") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            update(if (local) "Listening on device" else "Listening with Android")
            if (!local && emptyAttempts == 0) Conversation.notice("Voice uses Android's speech recognizer; it may use the network. Replies use the offline voice selected in ODCM.")
            recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
            handler.postDelayed(captureTimeout, 45_000)
        } catch (_: Exception) { end("Microphone could not start. Check Android microphone permission and speech settings.") }
    }

    private fun retrySilence() {
        if (++emptyAttempts >= 3) { end("Voice paused after no speech. Tap the mic when you're ready."); return }
        update("Waiting for speech")
        handler.postDelayed(resume, 700)
    }
    private fun releaseCapture() {
        capture?.cancel(); capture = null
        handler.removeCallbacks(captureTimeout)
        val old = recognizer; recognizer = null
        runCatching { old?.cancel(); old?.destroy() }
    }
    private fun reply(token: String, text: String) {
        if (!active || turn != token) return
        update("Speaking")
        output?.speak(text)
    }
    private fun taskEnded(token: String) {
        if (!active || turn != token) return
        awaitingTask = false
        handler.removeCallbacks(taskTimeout)
        maybeListen()
    }
    private fun maybeListen() {
        if (active && !awaitingTask && output?.isBusy == false) {
            turn = null
            update("Waiting for speech")
            handler.removeCallbacks(resume)
            handler.postDelayed(resume, 450)
        }
    }
    private fun update(value: String) {
        state = value
        CompanionService.voiceState(value)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    private fun end(message: String? = null) {
        active = false; awaitingTask = false; turn = null
        handler.removeCallbacksAndMessages(null)
        releaseCapture(); output?.close(); output = null
        if (registered) { unregisterReceiver(screenReceiver); registered = false }
        if (instance === this) instance = null
        CompanionService.voiceState(null)
        if (message != null) {
            Conversation.notice(message)
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { end(); super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) { end(); super.onTaskRemoved(rootIntent) }
    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "ODCM voice conversation", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 44, Intent(this, LiveVoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setContentTitle("ODCM voice conversation")
            .setContentText("$state · tap Stop to end conversation").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null, "Stop voice", stop).build()).build()
    }
    companion object {
        const val EXTRA_READY = "voice_ready"
        const val ACTION_STOP = "com.ether4o4.odcm.STOP_VOICE"
        private const val CHANNEL = "odcm_live_voice"
        private const val NOTIFICATION = 44
        // AgentService allows ten minutes. Its timeout explanation must reach the still-owned
        // voice turn before this independent watchdog ends an unresponsive service handoff.
        private const val TASK_WATCHDOG_MS = 11 * 60_000L
        private var instance: LiveVoiceService? = null
        val isRunning: Boolean get() = instance?.active == true
        fun ownsTurn(token: String): Boolean = instance?.let { it.active && it.turn == token } == true
        fun stop() { instance?.end() }
        fun reply(token: String, text: String) { instance?.reply(token, text) }
        fun taskEnded(token: String) { instance?.taskEnded(token) }
    }
}
