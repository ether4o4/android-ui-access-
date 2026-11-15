package com.ether4o4.odcm.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.ether4o4.odcm.control.CompanionOverlay

/**
 * Keeps the companion window on screen over other apps until the user closes it.
 * User-initiated and user-dismissable; it is the one persistent piece, by request.
 */
class CompanionService : Service() {

    private var overlay: CompanionOverlay? = null
    private val logListener: (List<String>) -> Unit = { lines ->
        overlay?.setText(lines.takeLast(30).joinToString("\n").ifEmpty { "Idle. Give me a task." })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(); return START_NOT_STICKY }
        if (!canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (overlay != null) return START_STICKY

        startForeground(NOTIF_ID, notification())
        val o = CompanionOverlay(this)
        o.onClose = { stop() }
        o.show(CompanionOverlay.Mode.BOX)
        overlay = o
        TaskLog.addListener(logListener)
        TaskLog.add("Companion ready.")
        return START_STICKY
    }

    private fun stop() {
        TaskLog.removeListener(logListener)
        overlay?.hide()
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        TaskLog.removeListener(logListener)
        overlay?.hide()
        overlay = null
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
        private const val CHANNEL = "odcm_companion"
        private const val NOTIF_ID = 43

        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CompanionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CompanionService::class.java).setAction(ACTION_STOP))
        }
    }
}
