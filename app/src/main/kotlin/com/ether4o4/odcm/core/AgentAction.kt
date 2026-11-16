package com.ether4o4.odcm.core

/** Every action the model may request. Anything else is rejected before it reaches the phone. */
sealed class AgentAction {
    data class Tap(val index: Int) : AgentAction()
    data class TypeText(val index: Int, val text: String) : AgentAction()
    data class Swipe(val direction: Direction) : AgentAction()
    data object Back : AgentAction()
    data object Home : AgentAction()
    data object Recents : AgentAction()
    data object Notifications : AgentAction()
    data object QuickSettings : AgentAction()
    data class TapKnown(val key: String) : AgentAction()
    data class SearchVault(val query: String) : AgentAction()
    data class ReadVault(val path: String, val offset: Int) : AgentAction()
    data class ReadScreenMap(val offset: Int) : AgentAction()
    data class ReadScreen(val offset: Int) : AgentAction()
    data class OpenApp(val name: String) : AgentAction()
    data class Wait(val millis: Long) : AgentAction()
    data class Reply(val text: String) : AgentAction()
    data class Done(val summary: String) : AgentAction()

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    companion object {
        const val MAX_TEXT = 2000
        const val MAX_WAIT_MS = 5000L

        /**
         * Parses a tool call into an action. Returns a failure with a message the model can read
         * when the name or arguments are wrong, so nothing malformed ever executes.
         */
        fun parse(name: String, args: Map<String, Any?>): Result<AgentAction> = runCatching {
            when (name) {
                "tap" -> Tap(intArg(args, "index"))
                "type_text" -> TypeText(intArg(args, "index"), textArg(args, "text"))
                "swipe" -> Swipe(
                    Direction.entries.firstOrNull { it.name.equals(textArg(args, "direction"), true) }
                        ?: throw IllegalArgumentException("direction must be up, down, left or right"),
                )
                "back" -> Back
                "home" -> Home
                "recents" -> Recents
                "notifications" -> Notifications
                "quick_settings" -> QuickSettings
                "tap_known" -> TapKnown(textArg(args, "key").also { require(it.matches(Regex("[0-9a-f]{16}"))) { "Use a key from the current screen map" } })
                "search_vault" -> SearchVault(textArg(args, "query"))
                "read_vault" -> ReadVault(textArg(args, "path"), offsetArg(args))
                "read_screen_map" -> ReadScreenMap(offsetArg(args))
                "read_screen" -> ReadScreen(offsetArg(args))
                "open_app" -> OpenApp(textArg(args, "name"))
                "wait" -> Wait(msArg(args, "ms").coerceIn(100L, MAX_WAIT_MS))
                "reply" -> Reply(textArg(args, "text"))
                "done" -> Done(textArg(args, "summary"))
                else -> throw IllegalArgumentException("unknown tool '$name'")
            }
        }

        private fun offsetArg(args: Map<String, Any?>): Int {
            val value = args["offset"] ?: return 0
            val n = (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull()
            require(n != null && n >= 0 && n <= 8_000_000 && n == kotlin.math.floor(n)) { "offset must be a non-negative character offset" }
            return n.toInt()
        }

        private fun intArg(args: Map<String, Any?>, key: String): Int {
            val v = args[key] ?: throw IllegalArgumentException("missing '$key'")
            val n = when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: throw IllegalArgumentException("'$key' must be a number")
                else -> throw IllegalArgumentException("'$key' must be a number")
            }
            require(n >= 0 && n <= 10_000 && n == Math.floor(n)) { "'$key' must be a whole number" }
            return n.toInt()
        }

        /** A duration in ms: any non-negative number; the caller clamps it to the safe range. */
        private fun msArg(args: Map<String, Any?>, key: String): Long {
            val v = args[key] ?: throw IllegalArgumentException("missing '$key'")
            val n = when (v) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull() ?: throw IllegalArgumentException("'$key' must be a number")
                else -> throw IllegalArgumentException("'$key' must be a number")
            }
            require(n >= 0) { "'$key' must be positive" }
            return n
        }

        private fun textArg(args: Map<String, Any?>, key: String): String {
            val v = args[key] as? String ?: throw IllegalArgumentException("missing text '$key'")
            require(v.length <= MAX_TEXT) { "'$key' is too long" }
            return v
        }
    }
}
