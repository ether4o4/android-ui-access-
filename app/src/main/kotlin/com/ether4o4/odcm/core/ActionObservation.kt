package com.ether4o4.odcm.core

/** A decision (and any approval) is valid only for the screen that produced it. */
object ActionGuard {
    fun staleReason(action: AgentAction, before: ScreenSnapshot, current: ScreenSnapshot): String? {
        // Opening a named package does not use a screen index; Policy checks the current app again.
        if (action is AgentAction.OpenApp) return null
        if (before != current) return "The screen changed while deciding or waiting. Read it again before acting."
        val index = when (action) {
            is AgentAction.Tap -> action.index
            is AgentAction.TypeText -> action.index
            else -> return null
        }
        val target = current.elements.getOrNull(index)
            ?: return "The requested element is no longer on the screen."
        if (action is AgentAction.TypeText && !target.editable) return "The requested element is not an editable field."
        return null
    }
}

/** Android accepting an action is distinct from an observed result or completion of the task. */
data class ActionObservation(val confirmed: Boolean, val message: String) {
    companion object {
        fun assess(
            action: AgentAction,
            before: ScreenSnapshot,
            after: ScreenSnapshot,
            dispatched: Boolean,
            launchPackage: String? = null,
        ): ActionObservation {
            if (!dispatched) return ActionObservation(false, "Android rejected the action. Read the screen and choose another approach.")
            if (action is AgentAction.OpenApp && launchPackage != null && after.packageName == launchPackage) {
                return ActionObservation(true, "Observed the requested app in the foreground: $launchPackage.")
            }
            if (action is AgentAction.Tap && before.packageName == after.packageName) {
                val target = before.elements.getOrNull(action.index)
                // Only a state change of the uniquely identified control is evidence for button learning.
                // Navigation, timers, banners and other content changes are not proof the tap succeeded.
                if (target?.checked != null) {
                    val matches = after.elements.filter {
                        if (!target.viewId.isNullOrBlank()) it.viewId == target.viewId && it.text == target.text
                        else target.text.isNotBlank() && it.text == target.text && it.role == target.role
                    }
                    val observed = matches.singleOrNull()
                    if (observed?.checked != null && observed.checked != target.checked) {
                        return ActionObservation(true, "Observed the selected control change from ${target.checked} to ${observed.checked}.")
                    }
                }
            }
            return ActionObservation(false, if (before == after) {
                "Android accepted the action, but the readable screen is unchanged. The outcome is unverified; do not claim success."
            } else {
                "Android accepted the action and the screen changed. Verify the intended outcome from the new screen before claiming success."
            })
        }
    }
}
