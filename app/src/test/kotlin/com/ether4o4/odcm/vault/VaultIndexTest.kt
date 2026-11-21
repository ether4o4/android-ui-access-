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

    @Test fun curatedKnowledgeIsNotCrowdedOutByFourHundredRawArchiveMatches() {
        val raw = (1..410).map { file("ODCM/90-Sources/$it.md") }
        val curated = file("ODCM/40-Workflows/Termux.md")
        db.refresh("tree", raw + curated) {
            val header = if (it.path == curated.path) "---\nodcm_schema: 1\nkind: workflow\nstatus: confirmed\nsource: conversation-1#message-2\n---\n" else ""
            ExtractedText(header + "Termux project execution", "Text indexed")
        }
        assertEquals(curated.path, db.search("Termux project").first().path)
        assertTrue(db.search("Termux", 30).any { it.path.startsWith("ODCM/90-Sources/") })
    }

    @Test fun laterChunksAndReadsRetainEvidenceStatusAndCoreIndexSupportsEitherVaultRoot() {
        val core = file("00-Core/INDEX.md")
        val workflow = file("40-Workflows/Build.md")
        db.refresh("tree", listOf(core, workflow)) {
            val text = if (it.path == core.path) "Core routing" else
                "---\nodcm_schema: 1\nkind: workflow\nstatus: failed\nsource: thread-7#message-4\nupdated: 2026-11-20\n---\n" + "Earlier details. ".repeat(300) + " terminal_quartz_failure"
            ExtractedText(text, "Text indexed")
        }
        val hit = db.search("terminal_quartz_failure").single()
        assertTrue(hit.offset > 0)
        assertEquals("failed", hit.knowledge.status)
        assertEquals("thread-7#message-4", hit.knowledge.source)
        assertTrue(db.read(workflow.path, hit.offset).contains("\"status\":\"failed\""))
        assertEquals(core.path, db.coreIndex()?.path)
    }

    @Test fun upgradingOldCachePreservesItsTreeAndBackfillsMetadataOnRefresh() {
        val context = RuntimeEnvironment.getApplication()
        db.close()
        context.deleteDatabase("vault-reference.db")
        context.openOrCreateDatabase("vault-reference.db", 0, null).use { old ->
            old.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            old.execSQL("CREATE TABLE files (path TEXT PRIMARY KEY, uri TEXT, modified INTEGER, size INTEGER, status TEXT, generation INTEGER)")
            old.execSQL("CREATE VIRTUAL TABLE chunks USING fts4(path, body, start, tokenize=unicode61)")
            old.execSQL("INSERT INTO meta VALUES('tree','selected-vault')")
            old.execSQL("INSERT INTO files VALUES('Workflow.md','content://vault/Workflow.md',7,100,'Text indexed',1)")
            old.execSQL("INSERT INTO chunks VALUES('Workflow.md','old indexed content','0')")
            old.version = 1
        }
        db = VaultIndex(context)
        db.requireTree("selected-vault")
        assertTrue(db.read("Workflow.md").contains("old indexed content"))
        var reads = 0
        db.refresh("selected-vault", listOf(file("Workflow.md", 7))) {
            reads++
            ExtractedText("---\nodcm_schema: 1\nkind: workflow\nstatus: confirmed\n---\nCurrent workflow", "Text indexed")
        }
        assertEquals(1, reads)
        assertEquals("confirmed", db.search("Current workflow").single().knowledge.status)
    }
}
