package com.ether4o4.odcm.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FrontmatterTest {
    @Test fun roundTrips() {
        val note = GuideNote(
            id = "button.com.gmail.inbox.compose",
            type = NoteType.BUTTON,
            status = NoteStatus.VERIFIED,
            title = "Compose",
            app = "com.google.android.gm",
            screen = "Inbox",
            triggers = listOf("compose", "new email"),
            elementId = "com.google.android.gm:id/compose_button",
            elementText = "Compose",
            lastCoords = 980 to 2150,
            successes = 4,
            failures = 0,
            updated = "2026-11-15",
            body = "Starts a new email.",
        )
        val parsed = Frontmatter.parse(Frontmatter.serialize(note))
        assertEquals(note, parsed)
    }

    @Test fun handlesTextWithCommasAndColons() {
        val note = GuideNote(id = "x.1", type = NoteType.FACT, title = "a: b, c", body = "line one\nline two")
        val parsed = Frontmatter.parse(Frontmatter.serialize(note))!!
        assertEquals("a: b, c", parsed.title)
        assertEquals("line one\nline two", parsed.body)
    }

    @Test fun rejectsNonFrontmatter() {
        assertNull(Frontmatter.parse("just some text"))
        assertNull(Frontmatter.parse("---\nno terminator"))
    }

    @Test fun toleratesCrlf() {
        val text = "---\r\nobject_id: x.1\r\nobject_type: fact\r\n---\r\n\r\nbody\r\n"
        val parsed = Frontmatter.parse(text)!!
        assertEquals("x.1", parsed.id)
        assertEquals("body", parsed.body)
    }
}

class NoteStatusTest {
    @Test fun promotesAfterThreeCleanSuccesses() {
        var n = GuideNote(id = "b.1", type = NoteType.BUTTON, status = NoteStatus.DRAFT)
        n = n.recordSuccess("d"); assertEquals(NoteStatus.DRAFT, n.status)
        n = n.recordSuccess("d"); assertEquals(NoteStatus.DRAFT, n.status)
        n = n.recordSuccess("d"); assertEquals(NoteStatus.TESTED, n.status)
    }

    @Test fun failureDoesNotPromoteAndLowersTrust() {
        var n = GuideNote(id = "b.1", type = NoteType.BUTTON, status = NoteStatus.VERIFIED, successes = 5)
        n = n.recordFailure("d")
        assertEquals(NoteStatus.TESTED, n.status)
        assertEquals(1, n.failures)
    }

    @Test fun statusParsingDefaultsToDraft() {
        assertEquals(NoteStatus.DRAFT, NoteStatus.from("nonsense"))
        assertEquals(NoteStatus.PROVEN, NoteStatus.from("proven"))
    }

    @Test fun buttonIdIsStableAndSafe() {
        val a = GuideNote.buttonId("com.app", "Inbox", "com.app:id/compose_button", "Compose")
        val b = GuideNote.buttonId("com.app", "Inbox", "com.app:id/compose_button", "Compose")
        assertEquals(a, b)
        assertTrue(a.contains("compose-button"))
    }
}

class GuideIndexTest {
    private fun note(id: String, app: String?, triggers: List<String>, status: NoteStatus = NoteStatus.DRAFT, text: String? = null) =
        GuideNote(id = id, type = NoteType.BUTTON, status = status, app = app, triggers = triggers, elementText = text, body = "do $id")

    @Test fun ranksTriggerAndAppMatchesHighest() {
        val notes = listOf(
            note("a", "com.gmail", listOf("compose", "email")),
            note("b", "com.other", listOf("compose")),
            note("c", "com.gmail", listOf("settings")),
        )
        val ranked = GuideIndex.rank(notes, "compose a new email", "com.gmail")
        assertEquals("a", ranked.first().id)
        assertTrue(ranked.none { it.id == "c" })
    }

    @Test fun trustBreaksTies() {
        val notes = listOf(
            note("draft", "com.gmail", listOf("compose")),
            note("proven", "com.gmail", listOf("compose"), status = NoteStatus.PROVEN),
        )
        val ranked = GuideIndex.rank(notes, "compose", "com.gmail")
        assertEquals("proven", ranked.first().id)
    }

    @Test fun dropsZeroScoreNotes() {
        val notes = listOf(note("x", "com.other", listOf("unrelated")))
        assertTrue(GuideIndex.rank(notes, "compose email", "com.gmail").isEmpty())
    }

    @Test fun tokenizeDropsStopwords() {
        assertEquals(setOf("compose", "email"), GuideIndex.tokenize("compose the email to me"))
    }

    @Test fun renderIsEmptyForNoNotes() {
        assertEquals("", GuideIndex.render(emptyList()))
    }
}

class FlowTest {
    @Test fun stepRoundTrips() {
        val step = FlowStep(act = "type", waitId = "com.app:id/search", text = "\$query", optional = true)
        assertEquals(step, FlowStep.parse(step.serialize()))
    }

    @Test fun buildsAndParsesFlowFromNote() {
        val steps = listOf(
            FlowStep(act = "tap", waitText = "Compose"),
            FlowStep(act = "type", waitId = "com.gmail:id/to", text = "\$query"),
        )
        val note = GuideNote(
            id = "flow.compose", type = NoteType.FLOW, app = "com.gmail",
            triggers = listOf("compose email"), body = Flow.toBody("Compose and address", steps),
        )
        val flow = Flow.from(note)!!
        assertEquals(2, flow.steps.size)
        assertEquals("Compose", flow.steps[0].waitText)
    }

    @Test fun nonFlowNoteYieldsNull() {
        assertNull(Flow.from(GuideNote(id = "b", type = NoteType.BUTTON)))
    }

    @Test fun matcherPicksMostProvenApplicableFlow() {
        fun flow(id: String, status: NoteStatus) = Flow.from(
            GuideNote(id = id, type = NoteType.FLOW, app = "com.gmail", status = status,
                triggers = listOf("check email"), body = Flow.toBody("", listOf(FlowStep("tap", waitText = "x")))),
        )!!
        val flows = listOf(flow("draft", NoteStatus.DRAFT), flow("proven", NoteStatus.PROVEN))
        assertEquals("proven", FlowMatcher.match(flows, "check my email now", "com.gmail")?.id)
        assertNull(FlowMatcher.match(flows, "play music", "com.gmail"))
    }
}

class GuideStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun writesAndReadsNotes() {
        val store = GuideStore(temp.root)
        val note = GuideNote(id = "b.1", type = NoteType.BUTTON, app = "com.app", elementText = "Go", body = "tap go")
        store.put(note)
        assertEquals(note.copy(body = "tap go"), store.get("b.1"))
        assertEquals(1, store.allNotes().size)
    }

    @Test fun recordButtonCreatesThenStrengthens() {
        val store = GuideStore(temp.root)
        store.recordButton("com.app", "Home", "com.app:id/go", "Go", 100, 200, "2026-11-15")
        val id = GuideNote.buttonId("com.app", "Home", "com.app:id/go", "Go")
        val first = store.get(id)!!
        assertEquals(1, first.successes)
        assertEquals(NoteStatus.DRAFT, first.status)

        repeat(2) { store.recordButton("com.app", "Home", "com.app:id/go", "Go", 100, 200, "2026-11-15") }
        val later = store.get(id)!!
        assertEquals(3, later.successes)
        assertEquals(NoteStatus.TESTED, later.status)
    }

    @Test fun emptyRootYieldsNoNotes() {
        assertTrue(GuideStore(temp.newFolder("empty")).allNotes().isEmpty())
    }

    @Test fun ignoresBlankButtons() {
        val store = GuideStore(temp.root)
        store.recordButton("com.app", "Home", null, "", 0, 0, "d")
        assertTrue(store.allNotes().isEmpty())
    }
}
