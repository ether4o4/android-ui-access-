package com.ether4o4.odcm.agent

import com.ether4o4.odcm.core.AppCatalog

/** Conservative local shortcuts; arbitrary instructions always remain model tasks. */
internal object RequestRoute {
    fun text(raw: String) = raw.replaceFirst(Regex("^/(chat|task|deep)\\s+", RegexOption.IGNORE_CASE), "").trim()
    fun deep(raw: String) = raw.startsWith("/deep ", true)
    fun phone(raw: String): Boolean {
        if (raw.startsWith("/chat ", true)) return false
        if (raw.startsWith("/task ", true)) return true
        val t = text(raw).lowercase()
        // Clear conversational questions skip the control prompt. Ambiguous follow-ups retain tools.
        if (Regex("\\b(screen|phone|app|notification|clipboard|tap|swipe|scroll|click|open|launch|send|delete|install|turn on|turn off|go back|go home)\\b").containsMatchIn(t)) return true
        return !Regex("^((?:hi|hey|hello|thanks|thank you|good morning|good night|how are you|how's it going)\\b|what is |what's |who is |who was |why |how (do|does|can|would|should) |explain |tell me |write |draft |summarize |translate |calculate |help me (understand|write)|can you (explain|write|tell|summarize))", RegexOption.IGNORE_CASE).containsMatchIn(t)
    }
    fun local(raw: String, catalog: AppCatalog): Pair<String, Map<String, Any?>>? {
        if (raw.startsWith("/chat ", true)) return null
        val t = text(raw).lowercase().trimEnd('.', '!').trim()
        val name = when (t) {
            "go home", "home", "go to home screen" -> "home"
            "go back", "back" -> "back"
            "open recent apps", "show recent apps", "recents" -> "recents"
            "open notifications", "show notifications" -> "notifications"
            "open quick settings", "show quick settings" -> "quick_settings"
            else -> null
        }
        if (name != null) return name to emptyMap()
        val requested = Regex("^(?:open|launch) ([^\\n]+)$").matchEntire(t)?.groupValues?.get(1) ?: return null
        val app = catalog.apps.singleOrNull { it.packageName.equals(requested, true) || it.label.equals(requested, true) } ?: return null
        return "open_app" to mapOf("name" to app.packageName)
    }
}

/** Still goes through TaskRunner's policy, fresh-screen checks, pause and approval handling. */
internal class LocalTaskModel(private val action: Pair<String, Map<String, Any?>>) : TaskModel {
    private var used = false
    private var result = "No action was confirmed."
    private var changed = false
    override fun addUserText(text: String) { if (text.startsWith("User follow-up:")) changed = true }
    override fun addToolResult(toolUseId: String, result: String, isError: Boolean) { this.result = result }
    override suspend fun next(): ModelDecision {
        if (changed) return ModelDecision(null, null, emptyMap(), "The local action was interrupted. Please send your updated task again.", "stop")
        if (used) return ModelDecision(null, null, emptyMap(), result, "stop")
        used = true
        return ModelDecision("local-action", action.first, action.second, "", "tool_calls")
    }
}
