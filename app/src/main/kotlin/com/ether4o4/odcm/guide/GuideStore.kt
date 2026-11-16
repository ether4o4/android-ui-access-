package com.ether4o4.odcm.guide

import java.io.File

/**
 * The vault on disk: one Markdown file per note, under a folder named for its type.
 * Plain files, so Obsidian (or a sync tool) can open the same folder. Uses only java.io.File,
 * so it runs identically under unit tests and on the device.
 */
class GuideStore(private val root: File) {

    fun allNotes(): List<GuideNote> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .mapNotNull { runCatching { Frontmatter.parse(it.readText()) }.getOrNull() }
            .toList()
    }

    fun flows(): List<Flow> = allNotes().mapNotNull { Flow.from(it) }

    fun get(id: String): GuideNote? = allNotes().firstOrNull { it.id == id }

    fun put(note: GuideNote) {
        val dir = File(root, folderFor(note.type)).apply { mkdirs() }
        val file = File(dir, safeName(note.id) + ".md")
        val tmp = File(dir, safeName(note.id) + ".tmp")
        tmp.writeText(Frontmatter.serialize(note))
        if (!tmp.renameTo(file)) { file.writeText(Frontmatter.serialize(note)); tmp.delete() }
    }

    /**
     * Records a button that was tapped successfully. Creates a DRAFT note the first time,
     * or raises the confidence of the existing one.
     */
    fun recordButton(app: String, screen: String, elementId: String?, elementText: String, x: Int, y: Int, today: String) {
        if (elementText.isBlank() && elementId.isNullOrBlank()) return
        val id = GuideNote.buttonId(app, screen, elementId, elementText)
        val existing = get(id)
        val note = if (existing != null) {
            existing.copy(lastCoords = x to y, elementId = elementId ?: existing.elementId).recordSuccess(today)
        } else {
            GuideNote(
                id = id,
                type = NoteType.BUTTON,
                status = NoteStatus.DRAFT,
                title = elementText.ifBlank { elementId.orEmpty() },
                app = app,
                screen = screen,
                triggers = GuideIndex.tokenize(elementText).toList(),
                elementId = elementId,
                elementText = elementText.ifBlank { null },
                lastCoords = x to y,
                successes = 1,
                updated = today,
                body = if (elementText.isNotBlank()) "Tapping \"$elementText\" here." else "A control on this screen.",
            )
        }
        put(note)
    }

    private fun folderFor(type: NoteType) = when (type) {
        NoteType.BUTTON -> "Apps"
        NoteType.FLOW -> "Flows"
        NoteType.WORKFLOW -> "Workflows"
        NoteType.FACT -> "Me"
        NoteType.OTHER -> "Notes"
    }

    private fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
}
