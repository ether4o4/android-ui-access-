package com.ether4o4.odcm.agent

import android.content.Context
import android.content.Intent
import com.ether4o4.odcm.core.AppAccess
import com.ether4o4.odcm.core.AppCatalog

/** Resolves an app name to a launchable package, matching against installed apps. */
class AppLauncher(private val context: Context) : AppAccess {

    override fun catalog(): AppCatalog {
        val pm = context.packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
        return AppCatalog(launchables.mapNotNull { info ->
            runCatching {
                val app = info.activityInfo.applicationInfo
                AppCatalog.App(info.activityInfo.packageName, app.loadLabel(pm).toString())
            }.getOrNull()
        })
    }

    fun find(name: String): AppCatalog.App? =
        (catalog().resolve(name) as? AppCatalog.Resolution.Found)?.app

    override fun launch(packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
