package com.ether4o4.odcm.vault

import android.net.Uri
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultIndexTest {
    private lateinit var db: VaultIndex
    @Before fun setup() { val c = RuntimeEnvironment.getApplication(); c.deleteDatabase("vault-reference.db"); db = VaultIndex(c) }
    @After fun close() = db.close()
    private fun file(path: String, modified: Long = 1) = VaultFile(path, Uri.parse("content://vault/$path"), "text/plain", modified, 100)

    @Test fun searchesBodiesWithoutMetadataThenUpdatesAndRemovesDeletedFiles() {
        val files = listOf(file("Notes/Plan.md"), file("Old.md"))
        db.refresh("tree", files) { ExtractedText(if (it.path == "Old.md") "retired project" else "The turbine uses a brass coupling.", "Text indexed") }
        val hit = db.search("brass coupling").single()
        assertEquals("Notes/Plan.md", hit.path)
        assertTrue(db.read(hit.path).contains("turbine"))
        db.refresh("tree", listOf(file("Notes/Plan.md", 2))) { ExtractedText("A different copper part.", "Text indexed") }
        assertTrue(db.search("brass").isEmpty()); assertTrue(db.search("retired").isEmpty())
        assertEquals(1, db.search("copper").size)
    }
    @Test fun switchedVaultCannotExposePreviousVaultCache() {
        db.refresh("A", listOf(file("One.md"))) { ExtractedText("private apple", "Text indexed") }
        assertTrue(runCatching { db.requireTree("B") }.isFailure)
        db.refresh("B", listOf(file("Two.md"))) { ExtractedText("orange", "Text indexed") }
        assertTrue(db.search("apple").isEmpty()); assertEquals("Two.md", db.search("orange").first().path)
    }
    @Test fun unchangedMetadataReusesIndexButForceRebuildReadsAgain() {
        var reads = 0
        repeat(2) { db.refresh("tree", listOf(file("Plan.md"))) { reads++; ExtractedText("contents", "Text indexed") } }
        assertEquals(1, reads)
        db.refresh("tree", listOf(file("Plan.md")), force = true) { reads++; ExtractedText("new contents", "Text indexed") }
        assertEquals(2, reads)
    }
    @Test fun unsupportedFilesRemainFindableAndReadErrorsStayVisible() {
        db.refresh("tree", listOf(file("Concert.mp3"), file("Bad.md"))) {
            if (it.path == "Bad.md") error("permission denied") else ExtractedText("", "Filename only: audio")
        }
        assertTrue(db.read("Concert.mp3").startsWith("Filename only"))
        assertEquals(1, db.search("Concert").size); assertEquals(1, db.stats().errors)
    }
    @Test fun unicodeAndLongFilePagingFindsTheEnd() {
        val body = "🌳".repeat(2000) + "\nFrançais coupling final detail"
        db.refresh("tree", listOf(file("Notes.md"))) { ExtractedText(body, "Text indexed") }
        assertEquals(1, db.search("Français").size)
        assertTrue(db.read("Notes.md", 3900).contains("final detail"))
        assertTrue(db.search("\" OR ***").isEmpty())
    }
}
