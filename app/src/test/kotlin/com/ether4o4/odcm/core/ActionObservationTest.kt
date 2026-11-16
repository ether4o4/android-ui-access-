package com.ether4o4.odcm.core

import org.junit.Assert.*
import org.junit.Test

class ActionObservationTest {
    private val target = ScreenElement(0, "switch", "Wi-Fi", "app:id/wifi", 0, 0, 100, 50,
        true, false, false, false, false)
    private val before = ScreenSnapshot("com.settings", 100, 200, listOf(target), false)

    @Test fun changedRecipientInvalidatesEvenAnIdenticalSendButton() {
        val old = before.copy(elements = listOf(target, target.copy(index = 1, text = "Alice")))
        val fresh = before.copy(elements = listOf(target, target.copy(index = 1, text = "Bob")))
        assertNotNull(ActionGuard.staleReason(AgentAction.Tap(0), old, fresh))
    }

    @Test fun failedDispatchNeverConfirmsEvenIfScreenChanges() {
        val after = before.copy(elements = listOf(target.copy(checked = true)))
        assertFalse(ActionObservation.assess(AgentAction.Tap(0), before, after, false).confirmed)
    }

    @Test fun unrelatedChangesAndDuplicateControlsDoNotConfirmLearning() {
        val after = before.copy(elements = listOf(target, target.copy(index = 1, text = "12:01")))
        assertFalse(ActionObservation.assess(AgentAction.Tap(0), before, after, true).confirmed)
        val duplicate = before.copy(elements = listOf(target.copy(checked = true), target.copy(index = 1, checked = true)))
        assertFalse(ActionObservation.assess(AgentAction.Tap(0), before, duplicate, true).confirmed)
    }

    @Test fun appLaunchRequiresObservedRequestedPackage() {
        assertFalse(ActionObservation.assess(AgentAction.OpenApp("Mail"), before, before, true, "com.mail").confirmed)
        assertTrue(ActionObservation.assess(AgentAction.OpenApp("Mail"), before,
            before.copy(packageName = "com.mail"), true, "com.mail").confirmed)
    }
}
