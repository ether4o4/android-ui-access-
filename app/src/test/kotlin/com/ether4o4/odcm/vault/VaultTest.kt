package com.ether4o4.odcm.vault

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VaultTextTest {
    @Test fun preservesEveryCharacterAcrossChunkBoundaries() {
        val text = ("A note with Français, 日本語, 🌳 and lines\n".repeat(500))
        assertEquals(text, VaultText.chunks(text).joinToString(""))
        assertTrue(VaultText.chunks(text).all { it.length <= VaultText.CHUNK_SIZE && !it.last().isHighSurrogate() })
    }
    @Test fun queriesCannotInjectFtsOperators() {
        assertEquals("\"cat\" OR \"or\" OR \"secret\"", VaultText.matchQuery("cat\" OR (secret*)"))
        assertEquals("", VaultText.matchQuery("\"' ();"))
    }
    @Test fun refusesTraversalAndKeepsUnicodeFileNames() {
        assertFalse(VaultText.validPath("../keys.md")); assertFalse(VaultText.validPath("/keys.md"))
        assertTrue(VaultText.validPath("Projects/日本語 notes.md"))
        assertFalse(VaultText.validPath("Notes/../../keys"))
        assertEquals("a_b_c", VaultText.safeSegment("a/b\\c"))
    }
    @Test fun plainObsidianNotesDoNotNeedAFrontmatterSchema() {
        assertTrue(VaultText.isText("My notes.md")); assertTrue(VaultText.isText("board.canvas"))
        assertTrue(VaultText.isText("data", "text/plain")); assertFalse(VaultText.isText("song.mp3"))
    }
}

class ConversationImportTest {
    @Test fun gptKeepsBothBranchesRolesTimestampsAndDeduplicates() {
        val json = """{"id":"conv","title":"A / Project","create_time":1,"mapping":{
          "a":{"parent":null,"message":{"author":{"role":"user"},"create_time":1,"content":{"parts":["Keep my notes"]}}},
          "b":{"parent":"a","message":{"author":{"role":"assistant"},"create_time":2,"content":{"parts":["First branch"]}}},
          "c":{"parent":"a","message":{"author":{"role":"assistant"},"create_time":3,"content":{"parts":["Alternate branch"]}}}}}"""
        val a = ConversationImport.convert(JSONObject(json))!!
        assertEquals(a, ConversationImport.convert(JSONObject(json)))
        assertTrue(a.path.startsWith("Imports/ChatGPT/A _ Project-"))
        for (text in listOf("First branch", "Alternate branch", "Parent: a", "Time: 1", "## user")) assertTrue(a.markdown.contains(text))
    }
    @Test fun claudeHandlesTextBlocksAndAttachmentMarkers() {
        val obj = JSONObject("""{"uuid":"c1","name":"Notes","created_at":"2026-01-01","chat_messages":[
            {"uuid":"m1","sender":"human","content":[{"type":"text","text":"My project details"},{"type":"image"}],"attachments":[{}]},
            {"uuid":"m2","sender":"assistant","text":"Legacy text"}]}""")
        val note = ConversationImport.convert(obj)!!
        assertTrue(note.markdown.contains("My project details")); assertTrue(note.markdown.contains("Legacy text"))
        assertTrue(note.markdown.contains("## human")); assertTrue(note.markdown.contains("see original export"))
    }
    @Test fun streamsRecordsWithEscapedQuotesAndNestedArrays() {
        val values = mutableListOf<JSONObject>()
        JsonRecords.read("""[{"name":"a } \\\" [","nested":[{"x":1}]},{"name":"second"}]""".reader(), values::add)
        assertEquals(2, values.size); assertEquals("second", values[1].getString("name"))
    }
    @Test fun rejectsTruncatedExports() {
        assertTrue(runCatching { JsonRecords.read("[{\"x\":1}".reader()) {} }.isFailure)
        assertNull(ConversationImport.convert(JSONObject("""{"unrelated":"file"}""")))
    }
}
