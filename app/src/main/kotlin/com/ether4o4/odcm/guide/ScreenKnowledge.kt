package com.ether4o4.odcm.guide

import android.content.Context
import com.ether4o4.odcm.core.ScreenSnapshot
import com.ether4o4.odcm.data.Settings
import com.ether4o4.odcm.vault.VaultRepository
import java.io.File

/** Keeps an internal map even before a vault is selected, then publishes its observed layouts. */
class ScreenKnowledge(private val context: Context) {
    private val atlas = ScreenAtlas(File(context.filesDir, "screen-keys"))
    private val published = HashSet<String>()
    var current: ScreenMap? = null
        private set

    fun observe(screen: ScreenSnapshot): ScreenMap? {
        if (screen.packageName in Settings(context).blockedList()) { current = null; return null }
        current = atlas.observe(screen)
        val uri = Settings(context).vaultUri
        if (uri.isNotBlank()) VaultRepository(context).use { repo ->
            if (published.add("$uri/basics")) {
                try {
                    repo.publishGenerated("ODCM/Android/Foundation.md", ScreenAtlas.BASICS)
                    for (map in atlas.savedMaps()) {
                        repo.publishGenerated(map.path, map.markdown); published.add("$uri/${map.path}")
                    }
                } catch (e: Exception) { published.remove("$uri/basics"); throw e }
            }
            current?.let { map ->
                val key = "$uri/${map.path}"
                if (key !in published) { repo.publishGenerated(map.path, map.markdown); published.add(key) }
            }
        }
        return current
    }
}
