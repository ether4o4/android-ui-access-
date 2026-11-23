package com.ether4o4.odcm.agent

import com.ether4o4.odcm.core.AppCatalog

/** Conversation is the default. Only clear device requests get phone tools. */
internal object RequestRoute {
    fun text(raw: String) = raw.replaceFirst(Regex("^/(chat|task|deep)\\s+", RegexOption.IGNORE_CASE), "").trim()
    fun deep(raw: String) = raw.startsWith("/deep ", true)
    fun phone(raw: String): Boolean {
        val request = raw.trim()
        if (request.startsWith("/chat ", true)) return false
        if (request.startsWith("/task ", true)) return true
        var t = text(request).lowercase().replace('\u2019', '\'').replace(Regex("\\s+"), " ")
        // Strip request wrappers, not arbitrary words from the middle of a conversation.
        repeat(3) { t = requestPrefix.replaceFirst(t, "") }
        if (conversationalAction.containsMatchIn(t)) return false
        if (t.matches(Regex("(?:home|back|recents)[.!?]*"))) return true
        return deviceAction.containsMatchIn(t) || screenQuestion.containsMatchIn(t)
    }

    private val requestPrefix = Regex("^(?:hey odcm[, ]+|odcm[, ]+|please |(?:can|could|would|will) you (?:please )?|i (?:want|need) you to |i'd like you to |let's )")
    private val conversationalAction = Regex("^(?:open up (?:to|about|a little|a bit)\\b|call me\\b|go back to (?:what (?:you|i)|our conversation|that (?:point|idea))\\b)")
    // Questions about apps, feelings, plans, hypothetical/negated commands and unknown phrases
    // stay in chat. /task is the explicit override when a less common request needs tools.
    private val deviceAction = Regex(
        "^(?:(?:open|launch|tap|click|press|swipe|scroll|install|uninstall)\\s+" +
            "|go (?:back|home|to (?:the )?home screen)\\b|navigate to\\s+" +
            "|(?:show|read|check|describe|look at|summarize) (?:me )?(?:(?:my|the|this|current|latest|last) )?(?:screen|notifications?|clipboard|recent apps|quick settings)\\b" +
            "|(?:read|check) (?:my|the|latest|last) (?:messages?|texts?|emails?)\\b" +
            "|(?:take|capture) (?:a |the )?screenshot\\b" +
            "|(?:turn (?:on|off)|enable|disable) (?:the )?(?:wi-?fi|bluetooth|airplane mode|mobile data|flashlight|torch|do not disturb)\\b" +
            "|turn (?:the )?(?:wi-?fi|bluetooth|airplane mode|mobile data|flashlight|torch|do not disturb) (?:on|off)\\b" +
            "|(?:set|change|adjust) (?:the |my |an? )?(?:volume|brightness|alarm|timer)\\b" +
            "|(?:lock|mute|unmute) (?:the |my )?phone\\b" +
            "|(?:copy|paste|type|enter|select|delete|remove) (?:this|that|the|my|all|selected)\\s+" +
            "|send (?:a |an |the |this |that |my )?(?:message|text|email|photo|file)\\b" +
            "|(?:text|call|dial)\\s+|reply to (?:the |my )?(?:message|text|email)\\b" +
            "|(?:search|find) .+ (?:in|on|using) (?:the )?(?:browser|chrome|firefox|youtube|google|maps|phone)\\b)"
    )
    private val screenQuestion = Regex(
        "^(?:tell me )?(?:(?:what is|what's|what do you see|what can you see) (?:currently )?on (?:my|the|this) screen\\b" +
            "|(?:which|what) app (?:is this|am i (?:in|using))\\b)"
    )
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
