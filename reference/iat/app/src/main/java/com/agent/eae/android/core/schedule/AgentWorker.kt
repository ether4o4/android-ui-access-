package com.agent.eae.android.core.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agent.eae.android.R
import com.agent.eae.android.core.orchestrator.AgentEngine
import com.agent.eae.android.data.EaeDatabase
import com.agent.eae.android.data.ScheduleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs a full agent loop headless at the scheduled time; results arrive as notifications. */
class AgentWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prompt: String = inputData.getString(Scheduler.KEY_PROMPT) ?: return Result.failure()
        val scheduleId: String = inputData.getString(Scheduler.KEY_ID) ?: id.toString()
        val name: String = inputData.getString(Scheduler.KEY_NAME) ?: DEFAULT_NAME

        val answer: String = runCatching { AgentEngine.runHeadless(applicationContext, prompt) }
            .getOrElse { "Scheduled task failed: ${it.message}" }

        notifyResult(scheduleId, name, answer)
        updateBookkeeping(scheduleId)
        return Result.success()
    }

    private fun notifyResult(scheduleId: String, name: String, answer: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
        )
        val body: String = answer.take(BODY_MAX_CHARS)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(name)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIF_ID_BASE + scheduleId.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    private suspend fun updateBookkeeping(scheduleId: String) {
        withContext(Dispatchers.IO) {
            val dao = EaeDatabase.get(applicationContext).scheduleDao()
            val entity: ScheduleEntity = dao.byId(scheduleId) ?: return@withContext
            if (entity.intervalMinutes == 0L) {
                dao.delete(scheduleId)
            } else {
                dao.upsert(entity.copy(nextRunAt = System.currentTimeMillis() + entity.intervalMinutes * 60_000L))
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "eae_scheduled_results"
        const val CHANNEL_NAME = "Scheduled task results"
        const val DEFAULT_NAME = "Scheduled task"
        const val BODY_MAX_CHARS = 300
        const val NOTIF_ID_BASE = 4200
    }
}
