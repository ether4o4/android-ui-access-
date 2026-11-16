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
import com.ether4o4.odcm.guide.ScreenKnowledge
import com.ether4o4.odcm.vault.VaultRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Short-lived foreground service that owns one task. It starts when a task arrives and stops itself
 * when the task ends, so nothing of ODCM's runs while idle.
 */
class AgentService : Service(), Overlay.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var runner: TaskRunner? = null
    private var pendingApproval: CompletableDeferred<Boolean>? = null
    private var replySink: ((String) -> Unit)? = null
    private var generation = 0
    private var learningEvents: Channel<Unit>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val task = intent?.getStringExtra(EXTRA_TASK)?.trim()
        if (intent?.action == ACTION_STOP) { finish(); return START_NOT_STICKY }
        if (task.isNullOrEmpty()) { finish(); return START_NOT_STICKY }

        startForeground(NOTIF_ID, notification("ODCM is working"))
        if (intent.getBooleanExtra(EXTRA_LEARN, false)) { startLearning(); return START_NOT_STICKY }
        startTask(task, replyTarget = intent.getStringExtra(EXTRA_REPLY_KEY))
        return START_NOT_STICKY
    }

    private fun startTask(task: String, replyTarget: String?) {
        if (job?.isActive == true) { TaskLog.add("Busy with another task; ignored: $task"); return }
        TaskLog.add("Task: $task")
        val control = ControlService.get()
        if (control == null) {
            TaskLog.add("Can't start: accessibility service is OFF. Enable ODCM in Accessibility settings.")
            notify("Enable ODCM's accessibility service first.")
            finish()
            return
        }
        val settings = Settings(this)
        val apiKey = SecretStore(this).get(SecretStore.ANTHROPIC_KEY)
        if (apiKey.isEmpty()) {
            TaskLog.add("Can't start: no Claude API key saved. Add it and tap Save key.")
            notify("Add your Claude API key in ODCM first.")
            finish()
            return
        }
        TaskLog.add("Model ${settings.model}, autonomy ${settings.autonomy.name.lowercase()}. Working…")

        val overlay = control.overlay
        overlay.listener = this
        overlay.show()
        pausedFlag = false

        replySink = { text -> Replies.send(this, replyTarget, text) }

        val model = ModelClient(
            apiKey = apiKey,
            model = settings.model,
            effort = settings.effort,
            systemPrompt = Prompt.system(settings.autonomy, settings.rules),
        )
        val policy = TaskRunner.policyFor(settings.autonomy, settings.blockedList())
        // Legacy learned notes remain available; new references use the user-selected SAF tree.
        val guideRoot = File(getExternalFilesDir(null) ?: filesDir, "ODCM Guide")
        val vault = if (settings.vaultUri.isNotBlank()) VaultRepository(this) else null
        val r = TaskRunner(
            control = control,
            launcher = AppLauncher(this),
            model = model,
            policy = policy,
            guide = GuideStore(guideRoot),
            vault = vault,
            knowledge = ScreenKnowledge(this),
            callbacks = object : TaskRunner.Callbacks {
                override fun status(text: String) { overlay.status(text); notify(text); TaskLog.add(text) }
                override fun reply(text: String) { TaskLog.add("Reply: $text"); replySink?.invoke(text) }
                override suspend fun requestApproval(reason: String): Boolean {
                    val d = CompletableDeferred<Boolean>()
                    pendingApproval = d
                    overlay.askApproval(reason)
                    val answer = withTimeoutOrNull(90_000) { d.await() } ?: false
                    if (pendingApproval === d) pendingApproval = null
                    overlay.clearApproval()
                    return answer
                }
            },
        )
        runner = r
        val taskGeneration = ++generation
        job = scope.launch {
            try { r.run(task) }
            catch (e: CancellationException) { TaskLog.add("Task stopped or timed out."); throw e }
            catch (e: Exception) { TaskLog.add("Error: ${e.message ?: e.javaClass.simpleName}") }
            finally {
                vault?.close()
                TaskLog.add("Task ended.")
                if (generation == taskGeneration) finish()
            }
        }
    }

    /** User-started observation session. Never opens apps, taps controls or calls a model. */
    private fun startLearning() {
        if (job?.isActive == true) { TaskLog.add("Finish the current task before starting a learning session."); return }
        val control = ControlService.get()
        if (control == null) { TaskLog.add("Enable Accessibility before learning screens."); finish(); return }
        val knowledge = ScreenKnowledge(this)
        val events = Channel<Unit>(Channel.CONFLATED)
        learningEvents = events
        control.onScreenChanged = { events.trySend(Unit) }
        control.overlay.listener = this
        control.overlay.show()
        control.overlay.status("Learning screens · browse normally · stops in 15 minutes")
        pausedFlag = false
        val taskGeneration = ++generation
        events.trySend(Unit)
        job = scope.launch {
            var last = ""
            try {
                withTimeoutOrNull(15 * 60_000L) {
                    for (event in events) {
                        delay(350)
                        events.tryReceive()
                        if (pausedFlag) continue
                        val screen = control.readScreen()
                        val fingerprint = screen.copy(elements = screen.elements.map { it.copy(text = com.ether4o4.odcm.core.ScreenIdentity.label(it)) }).toString()
                        if (fingerprint == last) continue
                        last = fingerprint
                        val map = runInterruptible(Dispatchers.IO) { knowledge.observe(screen) }
                        if (map != null) TaskLog.add("${if (map.known) "Refreshed" else "Learned"} screen key: ${map.path}")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { TaskLog.add("Learning stopped: ${e.message}") }
            finally { if (generation == taskGeneration) finish() }
        }
    }

    override fun onStop() { runner?.stop(); finish() }

    override fun onPauseToggle() {
        val r = runner
        if (pausedFlag) { r?.resume(); pausedFlag = false; learningEvents?.trySend(Unit) }
        else { r?.pause(); pausedFlag = true }
        ControlService.get()?.overlay?.paused(pausedFlag)
    }

    override fun onApproval(approved: Boolean) { pendingApproval?.complete(approved); pendingApproval = null }

    private var pausedFlag = false

    private fun finish() {
        generation++
        runner?.stop()
        pendingApproval?.complete(false)
        pendingApproval = null
        ControlService.get()?.onScreenChanged = null
        learningEvents?.close(); learningEvents = null
        job?.cancel()
        ControlService.get()?.overlay?.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runner?.stop()
        ControlService.get()?.onScreenChanged = null
        learningEvents?.close()
        ControlService.get()?.overlay?.hide()
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
        private const val EXTRA_LEARN = "learn_screens"
        const val ACTION_STOP = "com.ether4o4.odcm.STOP"
        private const val CHANNEL = "odcm_task"
        private const val NOTIF_ID = 42

        fun start(context: Context, task: String, replyKey: String? = null) {
            val intent = Intent(context, AgentService::class.java)
                .putExtra(EXTRA_TASK, task)
                .putExtra(EXTRA_REPLY_KEY, replyKey)
            context.startForegroundService(intent)
        }
        fun learn(context: Context) {
            context.startForegroundService(Intent(context, AgentService::class.java)
                .putExtra(EXTRA_TASK, "Learn visited screens").putExtra(EXTRA_LEARN, true))
        }
    }
}
