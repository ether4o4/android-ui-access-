package com.ether4o4.odcm.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.ether4o4.odcm.control.CompanionOverlay
import com.ether4o4.odcm.ui.VoiceActivity
import com.ether4o4.odcm.voice.VoiceOutput
import com.ether4o4.odcm.voice.LiveVoiceService

/**
 * Keeps the companion window on screen over other apps until the user closes it.
 * User-initiated and user-dismissable; it is the one persistent piece, by request.
 */
class CompanionService : Service() {

    private var overlay: CompanionOverlay? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var chatJob: Job? = null
    private var receiverRegistered = false
    private var voiceCapture = false
    private var speech: VoiceOutput? = null
    private val logListener: (List<String>) -> Unit = { lines -> overlay?.setLogs(lines) }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            overlay?.setScreenOn(getSystemService(PowerManager::class.java).isInteractive)
            if (intent?.action == Intent.ACTION_SCREEN_OFF) { LiveVoiceService.stop(); speech?.stop() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!voiceCapture) overlay?.refreshLayout()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(); return START_NOT_STICKY }
        if (intent?.action == ACTION_REFRESH) { overlay?.reloadAvatar(); return START_STICKY }
        if (!canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (overlay != null) return START_STICKY

        startForeground(NOTIF_ID, notification())
        val o = CompanionOverlay(this)
        Conversation.initialize(this)
        o.onClose = { stop() }
        o.onSend = { text ->
            LiveVoiceService.stop()
            runCatching { AgentService.start(this, text); true }.getOrElse {
                Conversation.notice("Could not start the request. Open ODCM and try again."); false
            }
        }
        o.onStop = { LiveVoiceService.stop(); speech?.stop(); AgentService.stopCurrent() }
        o.onPause = { AgentService.togglePause() }
        o.onMic = {
            if (LiveVoiceService.isRunning) LiveVoiceService.stop()
            else {
                speech?.close(); speech = null
                runCatching { startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Conversation.notice("Could not open the microphone. Open ODCM and try again.") }
            }
        }
        o.show(CompanionOverlay.Mode.BOX)
        overlay = o
        instance = this
        o.setScreenOn(getSystemService(PowerManager::class.java).isInteractive)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        chatJob = scope.launch { Conversation.state.collect { o.setConversation(it) } }
        TaskLog.addListener(logListener)
        TaskLog.add("Companion ready.")
        return START_STICKY
    }

    private fun stop() {
        LiveVoiceService.stop()
        speech?.close(); speech = null
        chatJob?.cancel(); chatJob = null
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false }
        TaskLog.removeListener(logListener)
        overlay?.close()
        overlay = null
        if (instance === this) instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        LiveVoiceService.stop()
        speech?.close(); speech = null
        chatJob?.cancel(); chatJob = null
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false }
        TaskLog.removeListener(logListener)
        overlay?.close()
        overlay = null
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "ODCM companion", NotificationManager.IMPORTANCE_MIN),
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("ODCM companion")
            .setContentText("Tap the ✕ on the companion to close it.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ether4o4.odcm.COMPANION_STOP"
        const val ACTION_REFRESH = "com.ether4o4.odcm.COMPANION_REFRESH"
        private const val CHANNEL = "odcm_companion"
        private const val NOTIF_ID = 43
        @Volatile private var instance: CompanionService? = null

        val isRunning: Boolean get() = instance != null

        fun hideForVoice() { instance?.let { it.voiceCapture = true; it.overlay?.hide() } }
        fun restoreAfterVoice() { instance?.takeIf { it.voiceCapture }?.let { it.voiceCapture = false; it.overlay?.show() } }
        fun speakReply(text: String) { instance?.takeIf { !it.voiceCapture && it.getSystemService(PowerManager::class.java).isInteractive }?.let {
            if (it.speech == null) it.speech = VoiceOutput(it)
            it.speech?.speak(text)
        } }
        fun voiceState(state: String?) { instance?.overlay?.setVoiceState(state) }
        fun stopSpeech() { instance?.speech?.stop() }

        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CompanionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CompanionService::class.java).setAction(ACTION_STOP))
        }

        /** Tell a running companion to reload the user's chosen image. No-op if not running. */
        fun refresh(context: Context) {
            if (instance != null) {
                context.startService(Intent(context, CompanionService::class.java).setAction(ACTION_REFRESH))
            }
        }
    }
}
