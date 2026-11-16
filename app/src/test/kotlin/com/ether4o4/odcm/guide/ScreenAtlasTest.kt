package com.ether4o4.odcm.guide

import com.ether4o4.odcm.core.*
import com.ether4o4.odcm.agent.TaskRunner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScreenAtlasTest {
    @get:Rule val temp = TemporaryFolder()
    private fun el(index: Int = 0, x: Int = 100, label: String = "Compose") = ScreenElement(index, "button", label, "app:id/compose", x, 100, x + 80, 160, true, false, false, null, false, "app")
    private fun screen(vararg els: ScreenElement) = ScreenSnapshot("app", 1440, 3120, els.toList(), false, surface = "Inbox", density = 3.5f)

    @Test fun firstVisitRecordsFullBoundsAndReturnLoadsTheSameKey() {
        val atlas = ScreenAtlas(temp.root)
        val first = atlas.observe(screen(el()))!!
        val second = ScreenAtlas(temp.root).observe(screen(el()))!!
        assertFalse(first.known); assertTrue(second.known); assertFalse(second.changed)
        assertEquals(first.path, second.path); assertTrue(first.markdown.contains("100, 100, 180, 160"))
        assertTrue(first.markdown.contains("140, 130"))
    }
    @Test fun movedControlsProduceANewRevisionAndRotationIsIsolated() {
        val atlas = ScreenAtlas(temp.root)
        val first = atlas.observe(screen(el()))!!
        val moved = atlas.observe(screen(el(x = 400)))!!
        assertEquals(first.id, moved.id); assertTrue(moved.changed); assertNotEquals(first.path, moved.path)
        val rotated = atlas.observe(screen(el()).copy(width = 3120, height = 1440, rotation = 1))!!
        assertNotEquals(first.id, rotated.id); assertFalse(rotated.known)
    }
    @Test fun repeatedObservationsPersistCountAndLatestRevisionAcrossInstances() {
        val first = ScreenAtlas(temp.root).observe(screen(el()))!!
        ScreenAtlas(temp.root).observe(screen(el()))
        val moved = ScreenAtlas(temp.root).observe(screen(el(x = 400)))!!
        val saved = ScreenAtlas(temp.root).savedMaps().single()
        val record = JSONObject(temp.root.resolve("${first.id}.json").readText())
        assertEquals(3, record.getInt("observations"))
        assertEquals(moved.path, saved.path)
        assertEquals(moved.markdown, saved.markdown)
        assertTrue(saved.markdown.contains("400, 100, 480, 160"))
        assertFalse(temp.root.resolve("${first.id}.tmp").exists())
    }
    @Test fun editableValuesAndPasswordsAreNotWrittenIntoMaps() {
        val editable = el(label = "private value").copy(editable = true)
        val password = el(1, label = "secret123").copy(password = true)
        val map = ScreenAtlas(temp.root).observe(screen(editable, password))!!
        assertFalse(map.markdown.contains("private value")); assertFalse(map.markdown.contains("secret123"))
    }
    @Test fun staleIndexResolvesByIdentityAndAmbiguityRefusesToAct() {
        val before = screen(el(), el(index = 1, label = "Delete").copy(viewId = "app:id/delete"))
        val after = screen(el(label = "Delete").copy(index = 0, viewId = "app:id/delete"), el(index = 1, x = 400))
        assertEquals(AgentAction.Tap(1), TaskRunner.remapAction(AgentAction.Tap(0), before, after))
        assertNull(TaskRunner.remapAction(AgentAction.Tap(0), before, screen(el(), el(1))))
        assertNull(TaskRunner.remapAction(AgentAction.TapKnown(ScreenIdentity.key(el())), before, after.copy(packageName = "other")))
    }
    @Test fun changedToggleOrDialogTextInvalidatesASavedAction() {
        val toggle = el().copy(checked = false)
        assertNull(TaskRunner.remapAction(AgentAction.TapKnown(ScreenIdentity.key(toggle)), screen(toggle), screen(toggle.copy(checked = true))))
        val text = el(index = 1, label = "Delete document A?").copy(clickable = false, role = "text", viewId = "app:id/dialog")
        assertNull(TaskRunner.remapAction(AgentAction.Tap(0), screen(el(), text), screen(el(), text.copy(text = "Delete document B?"))))
    }
    @Test fun missingAndUnseenScreensDoNotGetInventedCoordinates() {
        assertNull(ScreenAtlas(temp.root).observe(screen()))
        assertNull(ScreenIdentity.resolve("ffffffffffffffff", screen(el())))
    }
}
