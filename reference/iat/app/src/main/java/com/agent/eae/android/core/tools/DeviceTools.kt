package com.agent.eae.android.core.tools

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import com.agent.eae.android.R
import com.agent.eae.android.core.ToolResult
import org.json.JSONObject

/**
 * Layer 3's Android bridge. Intents are deliberately not generic: the model may only open http(s)
 * URLs, share text, or launch a package's own launcher activity. Letting a model name an arbitrary
 * action or component would hand it the whole exported-surface of every installed app.
 */
class DeviceTools(context: Context) {
    private val appContext = context.applicationContext

    fun deviceState(): ToolResult {
        val json = JSONObject()
        runCatching {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            json.put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            json.put("charging", bm.isCharging)
        }
        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            json.put("power_save_mode", pm.isPowerSaveMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                json.put("thermal_status", thermalName(pm.currentThermalStatus))
            }
        }
        runCatching { json.put("network", networkType()) }
        runCatching {
            val stat = StatFs(appContext.filesDir.absolutePath)
            json.put("free_storage_mb", stat.availableBytes / (1024 * 1024))
        }
        runCatching {
            json.put("android_sdk", Build.VERSION.SDK_INT)
            json.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
        val text = json.toString()
        return ToolResult(summary = text, full = json.toString(2), ok = true)
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    @SuppressLint("MissingPermission")
    private fun networkType(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    fun postNotification(title: String, body: String): ToolResult {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent messages", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title.take(120))
            .setContentText(body.take(400))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1000)))
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(NOTIF_ID_BASE + (System.currentTimeMillis() % 1000).toInt(), notification)
            ToolResult("Notification posted.", "$title\n$body", true)
        } catch (e: SecurityException) {
            ToolResult("Notification blocked: the user has not granted notification permission.", e.stackTraceToString(), false)
        }
    }

    fun openUrl(url: String): ToolResult {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "http" && scheme != "https")) {
            return ToolResult("Refused: only http and https URLs may be opened. Got: $url", "", false)
        }
        return launch(Intent(Intent.ACTION_VIEW, uri), "Opened $url")
    }

    fun shareText(text: String): ToolResult {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return launch(Intent.createChooser(send, null), "Share sheet opened.")
    }

    fun launchApp(packageName: String): ToolResult {
        val intent = appContext.packageManager.getLaunchIntentForPackageCompat(packageName)
            ?: return ToolResult("No launchable app found for package $packageName.", "", false)
        return launch(intent, "Launched $packageName")
    }

    private fun launch(intent: Intent, okMessage: String): ToolResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            ToolResult(okMessage, okMessage, true)
        } catch (e: Exception) {
            ToolResult("Could not start activity: ${e.message ?: e.javaClass.simpleName}", e.stackTraceToString(), false)
        }
    }

    private fun android.content.pm.PackageManager.getLaunchIntentForPackageCompat(pkg: String): Intent? =
        runCatching { getLaunchIntentForPackage(pkg) }.getOrNull()

    private companion object {
        const val CHANNEL_ID = "eae_agent_messages"
        const val NOTIF_ID_BASE = 4200
    }
}
