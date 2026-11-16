package com.ether4o4.odcm.agent

/** User steering is consumed only at decision boundaries; it never mutates model history mid-call. */
class TaskMessages {
    private val pending = ArrayDeque<String>()
    @Volatile var revision: Long = 0
        private set
    @Synchronized fun offer(text: String): Boolean {
        if (text.isBlank() || text.length > 4000 || pending.size >= 8) return false
        pending.addLast(text.trim())
        revision++
        return true
    }
    @Synchronized fun drain(): List<String> = pending.toList().also { pending.clear() }
}
