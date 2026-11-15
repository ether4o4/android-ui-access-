package com.ether4o4.odcm.core

/** Every action the model may request. Anything else is rejected before it reaches the phone. */
sealed class AgentAction {
    data class Tap(val index: Int) : AgentAction()
    data class TypeText(val index: Int, val text: String) : AgentAction()
    data class Swipe(val direction: Direction) : AgentAction()
    data object Back : AgentAction()
    data object Home : AgentAction()
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
                "open_app" -> OpenApp(textArg(args, "name"))
                "wait" -> Wait(msArg(args, "ms").coerceIn(100L, MAX_WAIT_MS))
                "reply" -> Reply(textArg(args, "text"))
                "done" -> Done(textArg(args, "summary"))
                else -> throw IllegalArgumentException("unknown tool '$name'")
            }
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
