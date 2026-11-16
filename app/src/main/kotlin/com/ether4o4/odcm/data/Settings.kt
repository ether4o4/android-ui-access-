package com.ether4o4.odcm.data

import android.content.Context
import com.ether4o4.odcm.core.AutonomyLevel

/** Plain, non-secret settings. Read fresh at the start of every task, so edits apply right away. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("odcm_settings", Context.MODE_PRIVATE)

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString(KEY_MODEL, v.trim()).apply()

    var effort: String
        get() = prefs.getString(KEY_EFFORT, "low") ?: "low"
        set(v) = prefs.edit().putString(KEY_EFFORT, v).apply()

    var autonomy: AutonomyLevel
        get() = runCatching { AutonomyLevel.valueOf(prefs.getString(KEY_AUTONOMY, null) ?: "") }
            .getOrDefault(AutonomyLevel.BALANCED)
        set(v) = prefs.edit().putString(KEY_AUTONOMY, v.name).apply()

    var rules: String
        get() = prefs.getString(KEY_RULES, "") ?: ""
        set(v) = prefs.edit().putString(KEY_RULES, v).apply()

    var blockedPackages: String
        get() = prefs.getString(KEY_BLOCKED, DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED
        set(v) = prefs.edit().putString(KEY_BLOCKED, v).apply()

    var inboxChat: String
        get() = prefs.getString(KEY_CHAT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CHAT, v.trim()).apply()

    var inboxPassphrase: String
        get() = prefs.getString(KEY_PHRASE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PHRASE, v.trim()).apply()

    var avatarUri: String
        get() = prefs.getString(KEY_AVATAR, "") ?: ""
        set(v) = prefs.edit().putString(KEY_AVATAR, v).apply()

    var vaultUri: String
        get() = prefs.getString("vault_uri", "") ?: ""
        set(v) = prefs.edit().putString("vault_uri", v).apply()

    fun blockedList(): List<String> =
        blockedPackages.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val EFFORTS = listOf("low", "medium", "high")
        private const val DEFAULT_BLOCKED =
            "com.google.android.apps.authenticator2, com.azure.authenticator, com.x8bit.bitwarden, " +
                "com.agilebits.onepassword, com.lastpass.lpandroid, com.samsung.android.spay"
        private const val KEY_MODEL = "model"
        private const val KEY_EFFORT = "effort"
        private const val KEY_AUTONOMY = "autonomy"
        private const val KEY_RULES = "rules"
        private const val KEY_BLOCKED = "blocked"
        private const val KEY_CHAT = "inbox_chat"
        private const val KEY_PHRASE = "inbox_phrase"
        private const val KEY_AVATAR = "avatar_uri"
    }
}
