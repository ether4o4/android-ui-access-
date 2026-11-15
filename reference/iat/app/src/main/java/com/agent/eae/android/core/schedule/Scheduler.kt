package com.agent.eae.android.core.schedule

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.agent.eae.android.data.EaeDatabase
import com.agent.eae.android.data.ScheduleEntity
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persists agent schedules in Room and mirrors them into WorkManager. */
object Scheduler {
    const val KEY_ID: String = "schedule_id"
    const val KEY_NAME: String = "schedule_name"
    const val KEY_PROMPT: String = "schedule_prompt"

    private const val MIN_PERIOD_MINUTES: Long = 15L
    private const val MAX_PROMPT_CHARS: Int = 8000

    suspend fun schedule(
        context: Context,
        name: String,
        prompt: String,
        delayMinutes: Long,
        repeatMinutes: Long,
    ): String {
        val id: String = UUID.randomUUID().toString()
        val delay: Long = delayMinutes.coerceAtLeast(0L)
        val interval: Long = if (repeatMinutes > 0L) repeatMinutes.coerceAtLeast(MIN_PERIOD_MINUTES) else 0L
        val entity = ScheduleEntity(
            id = id,
            name = name,
            prompt = prompt,
            intervalMinutes = interval,
            nextRunAt = System.currentTimeMillis() + delay * 60_000L,
            enabled = true,
        )
        withContext(Dispatchers.IO) {
            EaeDatabase.get(context).scheduleDao().upsert(entity)
        }
        val input = workDataOf(
            KEY_ID to id,
            KEY_NAME to name,
            KEY_PROMPT to prompt.take(MAX_PROMPT_CHARS),
        )
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (interval == 0L) {
            val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<AgentWorker>()
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .setInputData(input)
                .addTag(id)
                .build()
            workManager.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
        } else {
            val request: PeriodicWorkRequest = PeriodicWorkRequestBuilder<AgentWorker>(interval, TimeUnit.MINUTES)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .setInputData(input)
                .addTag(id)
                .build()
            workManager.enqueueUniquePeriodicWork(id, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        return id
    }

    suspend fun cancel(context: Context, id: String): Boolean {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(id)
        return withContext(Dispatchers.IO) {
            EaeDatabase.get(context).scheduleDao().delete(id) > 0
        }
    }

    suspend fun list(context: Context): List<ScheduleEntity> =
        withContext(Dispatchers.IO) {
            EaeDatabase.get(context).scheduleDao().all()
        }
}
