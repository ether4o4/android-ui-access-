package com.ether4o4.odcm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionParseTest {
    @Test fun parsesTap() {
        val a = AgentAction.parse("tap", mapOf("index" to 3)).getOrThrow()
        assertEquals(AgentAction.Tap(3), a)
    }

    @Test fun parsesTapFromStringNumber() {
        assertEquals(AgentAction.Tap(5), AgentAction.parse("tap", mapOf("index" to "5")).getOrThrow())
    }

    @Test fun rejectsUnknownTool() {
        assertTrue(AgentAction.parse("launch_nukes", emptyMap()).isFailure)
    }

    @Test fun rejectsMissingArg() {
        assertTrue(AgentAction.parse("tap", emptyMap()).isFailure)
    }

    @Test fun rejectsFractionalIndex() {
        assertTrue(AgentAction.parse("tap", mapOf("index" to 2.5)).isFailure)
    }

    @Test fun clampsWait() {
        val a = AgentAction.parse("wait", mapOf("ms" to 99999)).getOrThrow() as AgentAction.Wait
        assertEquals(AgentAction.MAX_WAIT_MS, a.millis)
    }

    @Test fun swipeDirectionCaseInsensitive() {
        val a = AgentAction.parse("swipe", mapOf("direction" to "DOWN")).getOrThrow() as AgentAction.Swipe
        assertEquals(AgentAction.Direction.DOWN, a.direction)
    }

    @Test fun rejectsBadDirection() {
        assertTrue(AgentAction.parse("swipe", mapOf("direction" to "sideways")).isFailure)
    }

    @Test fun rejectsOverlongText() {
        val long = "x".repeat(AgentAction.MAX_TEXT + 1)
        assertTrue(AgentAction.parse("type_text", mapOf("index" to 0, "text" to long)).isFailure)
    }
}

class PolicyTest {
    private fun el(i: Int, text: String, password: Boolean = false) = ScreenElement(
        i, "button", text, null, 0, 0, 100, 50, clickable = true, editable = password,
        scrollable = false, checked = null, password = password,
    )

    private fun screen(pkg: String, vararg els: ScreenElement) =
        ScreenSnapshot(pkg, 1080, 2400, els.toList(), false)

    @Test fun fullAllowsOrdinaryTap() {
        val p = Policy(AutonomyLevel.FULL, emptyList())
        assertTrue(p.check(AgentAction.Tap(0), screen("com.app", el(0, "Next"))) is Verdict.Allow)
    }

    @Test fun balancedAsksBeforeSend() {
        val p = Policy(AutonomyLevel.BALANCED, emptyList())
        val v = p.check(AgentAction.Tap(0), screen("com.app", el(0, "Send")))
        assertTrue(v is Verdict.Ask)
    }

    @Test fun balancedAllowsOrdinary() {
        val p = Policy(AutonomyLevel.BALANCED, emptyList())
        assertTrue(p.check(AgentAction.Tap(0), screen("com.app", el(0, "Open"))) is Verdict.Allow)
    }

    @Test fun supervisedAsksBeforeTap() {
        val p = Policy(AutonomyLevel.SUPERVISED, emptyList())
        assertTrue(p.check(AgentAction.Tap(0), screen("com.app", el(0, "Open"))) is Verdict.Ask)
    }

    @Test fun blockedAppIsBlockedEvenOnFull() {
        val p = Policy(AutonomyLevel.FULL, listOf("com.bank.app"))
        val v = p.check(AgentAction.Tap(0), screen("com.bank.app", el(0, "Pay")))
        assertTrue(v is Verdict.Block)
    }

    @Test fun blockedAppOpenIsBlocked() {
        val p = Policy(AutonomyLevel.FULL, listOf("com.bank.app"))
        val v = p.check(AgentAction.OpenApp("Bank"), screen("com.home"), launchTarget = "com.bank.app")
        assertTrue(v is Verdict.Block)
    }

    @Test fun passwordFieldNeverTyped() {
        val p = Policy(AutonomyLevel.FULL, emptyList())
        val v = p.check(AgentAction.TypeText(0, "hunter2"), screen("com.app", el(0, "Password", password = true)))
        assertTrue(v is Verdict.Block)
    }

    @Test fun sensitiveWordMatchesWholeWordOnly() {
        assertNull(Policy.sensitiveReason("Resend code includes sender"))
        assertNotNull(Policy.sensitiveReason("Send"))
    }

    private fun assertNotNull(v: Any?) = assertTrue(v != null)
}

class StepLimitsTest {
    @Test fun stopsAtStepCap() {
        val l = StepLimits(maxSteps = 2, clock = { 0 })
        l.onStep(); assertNull(l.exceeded())
        l.onStep(); assertEquals("reached the 2-step limit", l.exceeded())
    }

    @Test fun stopsAfterConsecutiveFailures() {
        val l = StepLimits(maxConsecutiveFailures = 2, clock = { 0 })
        l.onResult(false); assertNull(l.exceeded())
        l.onResult(false); assertTrue(l.exceeded()!!.contains("failed"))
    }

    @Test fun successResetsFailureStreak() {
        val l = StepLimits(maxConsecutiveFailures = 2, clock = { 0 })
        l.onResult(false); l.onResult(true); l.onResult(false)
        assertNull(l.exceeded())
    }

    @Test fun stopsAtTimeLimit() {
        var now = 0L
        val l = StepLimits(maxMillis = 1000, clock = { now })
        assertNull(l.exceeded())
        now = 1000
        assertTrue(l.exceeded()!!.contains("minute"))
    }
}

class InboxMatcherTest {
    private val pkgs = InboxMatcher.TELEGRAM_PACKAGES

    @Test fun matchesWhitelistedChatWithPassphrase() {
        val m = InboxMatcher(pkgs, "Phone B", "jarvis")
        assertEquals("open gmail", m.match("org.telegram.messenger", "Phone B", "jarvis: open gmail"))
    }

    @Test fun rejectsWrongChat() {
        val m = InboxMatcher(pkgs, "Phone B", "jarvis")
        assertNull(m.match("org.telegram.messenger", "Spam Group", "jarvis open gmail"))
    }

    @Test fun rejectsMissingPassphrase() {
        val m = InboxMatcher(pkgs, "Phone B", "jarvis")
        assertNull(m.match("org.telegram.messenger", "Phone B", "open gmail"))
    }

    @Test fun rejectsNonTelegramPackage() {
        val m = InboxMatcher(pkgs, "Phone B", "")
        assertNull(m.match("com.whatsapp", "Phone B", "open gmail"))
    }

    @Test fun worksWithoutPassphrase() {
        val m = InboxMatcher(pkgs, "Phone B", "")
        assertEquals("open gmail", m.match("org.telegram.messenger", "Phone B", "open gmail"))
    }

    @Test fun disabledWhenNoChatSet() {
        val m = InboxMatcher(pkgs, "", "")
        assertFalse(m.enabled)
        assertNull(m.match("org.telegram.messenger", "Phone B", "open gmail"))
    }
}

class DeduperTest {
    @Test fun suppressesRepeat() {
        val d = Deduper()
        assertTrue(d.firstTime("a"))
        assertFalse(d.firstTime("a"))
        assertTrue(d.firstTime("b"))
    }
}

class ScreenFormatterTest {
    @Test fun clipsLongText() {
        val out = ScreenFormatter.clip("x".repeat(200))
        assertTrue(out.length <= ScreenFormatter.MAX_TEXT)
    }

    @Test fun formatsElements() {
        val s = ScreenSnapshot(
            "com.app", 1080, 2400,
            listOf(ScreenElement(0, "button", "Compose", null, 900, 2000, 1060, 2100, true, false, false, null, false)),
            false,
        )
        val text = ScreenFormatter.format(s)
        assertTrue(text.contains("[0] button \"Compose\""))
        assertTrue(text.contains("(tap)"))
    }

    @Test fun notesEmptyScreen() {
        val s = ScreenSnapshot("com.app", 1080, 2400, emptyList(), false)
        assertTrue(ScreenFormatter.format(s).contains("no readable elements"))
    }

    @Test fun roleMapping() {
        assertEquals("button", ScreenFormatter.roleOf("android.widget.Button"))
        assertEquals("field", ScreenFormatter.roleOf("android.widget.EditText"))
        assertEquals("view", ScreenFormatter.roleOf(null))
    }
}
