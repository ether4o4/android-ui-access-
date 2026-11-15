package com.ether4o4.odcm.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.ether4o4.odcm.control.ControlService
import com.ether4o4.odcm.control.Overlay
import com.ether4o4.odcm.data.SecretStore
import com.ether4o4.odcm.data.Settings
import com.ether4o4.odcm.guide.GuideStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Short-lived foreground service that owns one task. It starts when a task arrives and stops itself
 * when the task ends, so nothing of ODCM's runs while idle.
 */
class AgentService : Service(), Overlay.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var runner: TaskRunner? = null
    private var pendingApproval: CompletableDeferred<Boolean>? = null
    private var replySink: ((String) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val task = intent?.getStringExtra(EXTRA_TASK)?.trim()
        if (intent?.action == ACTION_STOP) { finish(); return START_NOT_STICKY }
        if (task.isNullOrEmpty()) { finish(); return START_NOT_STICKY }

        startForeground(NOTIF_ID, notification("ODCM is working"))
        startTask(task, replyTarget = intent.getStringExtra(EXTRA_REPLY_KEY))
        return START_NOT_STICKY
    }

    private fun startTask(task: String, replyTarget: String?) {
        if (job?.isActive == true) return
        val control = ControlService.get()
        if (control == null) {
            notify("Enable ODCM's accessibility service first.")
            finish()
            return
        }
        val settings = Settings(this)
        val apiKey = SecretStore(this).get(SecretStore.ANTHROPIC_KEY)
        if (apiKey.isEmpty()) {
            notify("Add your Claude API key in ODCM first.")
            finish()
            return
        }

        val overlay = control.overlay
        overlay.listener = this
        overlay.show()

        replySink = { text -> Replies.send(this, replyTarget, text) }

        val model = ModelClient(
            apiKey = apiKey,
            model = settings.model,
            effort = settings.effort,
            systemPrompt = Prompt.system(settings.autonomy, settings.rules),
        )
        val policy = TaskRunner.policyFor(settings.autonomy, settings.blockedList())
        // The vault lives in app external files so a file manager (and later Obsidian) can reach it.
        val guideRoot = File(getExternalFilesDir(null) ?: filesDir, "ODCM Guide")
        val r = TaskRunner(
            control = control,
            launcher = AppLauncher(this),
            model = model,
            policy = policy,
            guide = GuideStore(guideRoot),
            callbacks = object : TaskRunner.Callbacks {
                override fun status(text: String) { overlay.status(text); notify(text) }
                override fun reply(text: String) { replySink?.invoke(text) }
                override suspend fun requestApproval(reason: String): Boolean {
                    val d = CompletableDeferred<Boolean>()
                    pendingApproval = d
                    overlay.askApproval(reason)
                    val answer = d.await()
                    overlay.clearApproval()
                    return answer
                }
            },
        )
        runner = r
        job = scope.launch {
            runCatching { r.run(task) }
            finish()
        }
    }

    override fun onStop() { runner?.stop(); finish() }

    override fun onPauseToggle() {
        val r = runner ?: return
        if (pausedFlag) { r.resume(); pausedFlag = false } else { r.pause(); pausedFlag = true }
        ControlService.get()?.overlay?.paused(pausedFlag)
    }

    override fun onApproval(approved: Boolean) { pendingApproval?.complete(approved); pendingApproval = null }

    private var pausedFlag = false

    private fun finish() {
        pendingApproval?.complete(false)
        job?.cancel()
        ControlService.get()?.overlay?.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "ODCM task", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("ODCM")
            .setContentText(text.take(120))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val EXTRA_TASK = "task"
        const val EXTRA_REPLY_KEY = "reply_key"
        const val ACTION_STOP = "com.ether4o4.odcm.STOP"
        private const val CHANNEL = "odcm_task"
        private const val NOTIF_ID = 42

        fun start(context: Context, task: String, replyKey: String? = null) {
            val intent = Intent(context, AgentService::class.java)
                .putExtra(EXTRA_TASK, task)
                .putExtra(EXTRA_REPLY_KEY, replyKey)
            context.startForegroundService(intent)
        }
    }
}
