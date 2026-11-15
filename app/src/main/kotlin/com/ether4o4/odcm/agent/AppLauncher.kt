package com.ether4o4.odcm.agent

import android.content.Context
import android.content.Intent
import java.util.Locale

/** Resolves an app name to a launchable package, matching against installed apps. */
class AppLauncher(private val context: Context) {

    data class Match(val packageName: String, val label: String)

    fun find(name: String): Match? {
        val query = name.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return null
        val pm = context.packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
        var best: Match? = null
        var bestScore = Int.MAX_VALUE
        for (info in launchables) {
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName
            val l = label.lowercase(Locale.ROOT)
            val score = when {
                l == query -> 0
                l.startsWith(query) -> 1
                l.contains(query) -> 2
                pkg.lowercase(Locale.ROOT).contains(query) -> 3
                else -> continue
            }
            if (score < bestScore) { bestScore = score; best = Match(pkg, label) }
        }
        return best
    }

    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
