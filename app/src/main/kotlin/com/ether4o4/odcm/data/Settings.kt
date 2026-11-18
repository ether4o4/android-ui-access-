package com.ether4o4.odcm.data

import android.content.Context
import com.ether4o4.odcm.core.AutonomyLevel

/** Plain, non-secret settings. Read fresh at the start of every task, so edits apply right away. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("odcm_settings", Context.MODE_PRIVATE)

    var provider: ModelProvider
        get() = ModelProvider.entries.firstOrNull { it.id == prefs.getString("provider", "anthropic") }
            ?: ModelProvider.ANTHROPIC
        set(v) = prefs.edit().putString("provider", v.id).apply()

    var model: String
        get() {
            val fallback = if (provider == ModelProvider.ANTHROPIC)
                prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL else provider.defaultModel
            return prefs.getString("model_${provider.id}", fallback)?.ifBlank { fallback } ?: fallback
        }
        set(v) = prefs.edit().putString("model_${provider.id}", v.trim()).apply()

    var endpoint: String
        get() = prefs.getString("endpoint_${provider.id}", provider.defaultEndpoint)
            ?.ifBlank { provider.defaultEndpoint } ?: provider.defaultEndpoint
        set(v) = prefs.edit().putString("endpoint_${provider.id}", v.trim()).apply()

    var economy: Boolean
        get() = prefs.getBoolean("economy", true)
        set(v) = prefs.edit().putBoolean("economy", v).apply()

    var bundledVoice: Boolean
        get() = prefs.getBoolean("bundled_voice", true)
        set(v) = prefs.edit().putBoolean("bundled_voice", v).apply()

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
