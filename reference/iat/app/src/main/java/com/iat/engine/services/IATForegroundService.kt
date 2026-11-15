package com.iat.engine.services

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.iat.engine.ipc.IPCReceiver
import com.iat.engine.tools.ToolRegistry
import org.json.JSONObject

class IATForegroundService : Service() {
    private lateinit var ipcReceiver: IPCReceiver
    private val toolRegistry = ToolRegistry()
    private val CHANNEL_ID = "IAT_Engine_Channel"
    private var activeProject = "Idle"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupNativeTools()
        
        ipcReceiver = IPCReceiver(port = 8888) { payload ->
            handleContextPayload(payload)
        }
        ipcReceiver.start()

        startForeground(1001, buildNotification("Engine Ready", "Listening for Vault IPC connection..."))
        startMemoryGuardrailLoop()
    }

    private fun setupNativeTools() {
        toolRegistry.registerTool("filesystem_read", """{"type":"object","properties":{"path":{"type":"string"}}}""") { args ->
            val path = args["path"] as? String ?: return@registerTool "Invalid Path"
            val file = java.io.File(path)
            if (file.exists()) file.readText().take(4000) else "File not found."
        }
    }

    private fun handleContextPayload(payload: JSONObject) {
        val session = payload.optJSONObject("active_session")
        activeProject = session?.optString("project_target") ?: "Default"
        
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)
        
        if (usedMem > maxMem * 0.85) {
            System.gc()
            Log.w("IAT-Memory", "RAM pressure > 85%. Triggered Garbage Collection.")
        }

        updateNotification("Active Project: $activeProject", "Context synced. Engine active.")
    }

    private fun startMemoryGuardrailLoop() {
        Thread {
            while (true) {
                Thread.sleep(15000)
                val runtime = Runtime.getRuntime()
                if ((runtime.totalMemory() - runtime.freeMemory()) > (runtime.maxMemory() * 0.90)) {
                    System.gc()
                }
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "IAT Engine Execution Host", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1001, buildNotification(title, content))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ipcReceiver.stop()
        super.onDestroy()
    }
}
