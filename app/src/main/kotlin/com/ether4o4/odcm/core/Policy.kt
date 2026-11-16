package com.ether4o4.odcm.core

enum class AutonomyLevel { SUPERVISED, BALANCED, FULL }

sealed class Verdict {
    data object Allow : Verdict()
    data class Ask(val reason: String) : Verdict()
    data class Block(val reason: String) : Verdict()
}

/**
 * Hard lines and approval rules, enforced in code. The model's text can never change these.
 */
class Policy(
    private val level: AutonomyLevel,
    blockedPackages: Collection<String>,
) {
    private val blocked = blockedPackages.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun isBlocked(packageName: String): Boolean = packageName.lowercase() in blocked

    fun check(action: AgentAction, screen: ScreenSnapshot?, launchTarget: String? = null): Verdict {
        // Hard line: never operate inside a blocked app, whatever the level.
        val currentPkg = screen?.packageName?.lowercase()
        if (currentPkg != null && currentPkg in blocked && action !is AgentAction.Back &&
            action !is AgentAction.Home && action !is AgentAction.Done && action !is AgentAction.Reply
        ) {
            return Verdict.Block("$currentPkg is on your blocked list")
        }
        if (action is AgentAction.OpenApp && launchTarget != null && launchTarget.lowercase() in blocked) {
            return Verdict.Block("$launchTarget is on your blocked list")
        }

        val target = when (action) {
            is AgentAction.Tap -> screen?.elements?.getOrNull(action.index)
            is AgentAction.TypeText -> screen?.elements?.getOrNull(action.index)
            else -> null
        }
        // Hard line: never type into password fields. Those are handed to the user.
        if (action is AgentAction.TypeText && target?.password == true) {
            return Verdict.Block("password fields are yours to type")
        }

        val sensitive = when (action) {
            is AgentAction.Tap -> target?.let { sensitiveReason(it.text) }
            is AgentAction.Reply -> null
            else -> null
        }

        return when (level) {
            AutonomyLevel.FULL -> Verdict.Allow
            AutonomyLevel.BALANCED -> if (sensitive != null) Verdict.Ask(sensitive) else Verdict.Allow
            AutonomyLevel.SUPERVISED -> when (action) {
                is AgentAction.Done, is AgentAction.Wait, is AgentAction.Reply -> Verdict.Allow
                else -> Verdict.Ask(sensitive ?: describe(action, target))
            }
        }
    }

    companion object {
        private val SENSITIVE = listOf(
            "send" to "sends a message",
            "post" to "posts publicly",
            "delete" to "deletes something",
            "remove" to "removes something",
            "pay" to "makes a payment",
            "buy" to "makes a purchase",
            "purchase" to "makes a purchase",
            "order" to "places an order",
            "checkout" to "checks out",
            "confirm" to "confirms an action",
            "transfer" to "transfers something",
            "uninstall" to "uninstalls an app",
            "reset" to "resets something",
            "sign out" to "signs out",
            "log out" to "signs out",
            "subscribe" to "starts a subscription",
            "call" to "places a call",
        )

        fun sensitiveReason(label: String): String? {
            val l = label.lowercase()
            return SENSITIVE.firstOrNull { (word, _) -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(l) }
                ?.let { (_, why) -> "\"${ScreenFormatter.clip(label)}\" $why" }
        }

        fun describe(action: AgentAction, target: ScreenElement?): String = when (action) {
            is AgentAction.Tap -> "tap \"${target?.text?.let(ScreenFormatter::clip) ?: "#${action.index}"}\""
            is AgentAction.TypeText -> "type \"${ScreenFormatter.clip(action.text)}\""
            is AgentAction.Swipe -> "swipe ${action.direction.name.lowercase()}"
            AgentAction.Back -> "go back"
            AgentAction.Home -> "go home"
            is AgentAction.OpenApp -> "open ${action.name}"
            is AgentAction.Wait -> "wait"
            is AgentAction.Reply -> "reply"
            is AgentAction.Done -> "finish"
        }
    }
}

/** Run limits, enforced in code. */
class StepLimits(
    private val maxSteps: Int = 40,
    private val maxConsecutiveFailures: Int = 3,
    private val maxMillis: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val start = clock()
    private var steps = 0
    private var failures = 0

    fun onStep() { steps++ }
    fun onResult(ok: Boolean) { failures = if (ok) 0 else failures + 1 }

    fun remainingMillis(): Long = (maxMillis - (clock() - start)).coerceAtLeast(0)

    /** Null while within limits, otherwise why the task must stop. */
    fun exceeded(): String? = when {
        steps >= maxSteps -> "reached the $maxSteps-step limit"
        failures >= maxConsecutiveFailures -> "$maxConsecutiveFailures actions in a row failed"
        clock() - start >= maxMillis -> "reached the ${maxMillis / 60_000}-minute limit"
        else -> null
    }
}
