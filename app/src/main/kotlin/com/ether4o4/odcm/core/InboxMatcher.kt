package com.ether4o4.odcm.core

/**
 * Decides whether a messaging notification is a command for ODCM.
 * Only one whitelisted chat counts, and an optional passphrase must start the message.
 */
class InboxMatcher(
    private val allowedPackages: Set<String>,
    chatName: String,
    passphrase: String,
) {
    private val chat = chatName.trim()
    private val phrase = passphrase.trim()

    val enabled: Boolean get() = chat.isNotEmpty()

    /** Returns the task text, or null when the notification is not a command. */
    fun match(packageName: String, title: String?, text: String?): String? {
        if (!enabled || packageName !in allowedPackages) return null
        if (title?.trim()?.equals(chat, ignoreCase = true) != true) return null
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return null
        val task = if (phrase.isEmpty()) {
            body
        } else {
            if (!body.startsWith(phrase, ignoreCase = false)) return null
            body.removePrefix(phrase).trim().trimStart(':', ',', '-').trim()
        }
        return task.takeIf { it.isNotEmpty() && it.length <= AgentAction.MAX_TEXT }
    }

    companion object {
        val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
        )
    }
}

/** Drops repeat notifications (Android re-posts updates of the same message). */
class Deduper(private val capacity: Int = 64) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun firstTime(key: String): Boolean {
        if (!seen.add(key)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}
