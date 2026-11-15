package com.ether4o4.odcm.guide

/** Trust level of a learned note. It rises with successful use and falls on failure. */
enum class NoteStatus {
    DRAFT, TESTED, VERIFIED, PROVEN;

    fun promoted(): NoteStatus = entries.getOrElse(ordinal + 1) { PROVEN }
    fun demoted(): NoteStatus = entries.getOrElse(ordinal - 1) { DRAFT }

    companion object {
        fun from(text: String?): NoteStatus =
            entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: DRAFT
    }
}

enum class NoteType {
    BUTTON, FLOW, WORKFLOW, FACT, OTHER;

    companion object {
        fun from(text: String?): NoteType =
            entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: OTHER
    }
}

/**
 * One note in the ODCM Guide. A button map, a flow, a workflow recipe, or a fact about the user.
 * Stored as a Markdown file with these fields in the frontmatter and prose in [body].
 */
data class GuideNote(
    val id: String,
    val type: NoteType,
    val status: NoteStatus = NoteStatus.DRAFT,
    val title: String = "",
    val app: String? = null,
    val screen: String? = null,
    val triggers: List<String> = emptyList(),
    val elementId: String? = null,
    val elementText: String? = null,
    val lastCoords: Pair<Int, Int>? = null,
    val successes: Int = 0,
    val failures: Int = 0,
    val updated: String = "",
    val body: String = "",
) {
    /** A successful use: count it and, after enough clean uses, raise trust. */
    fun recordSuccess(today: String): GuideNote {
        val next = successes + 1
        val newStatus = if (next >= PROMOTE_AT && failures == 0) status.promoted() else status
        return copy(successes = next, status = newStatus, updated = today)
    }

    /** A failed use: count it and lower trust so a stale note stops being trusted. */
    fun recordFailure(today: String): GuideNote =
        copy(failures = failures + 1, status = status.demoted(), updated = today)

    companion object {
        const val PROMOTE_AT = 3

        /** Deterministic id for a button on one screen, so repeat sightings update one note. */
        fun buttonId(app: String, screen: String, elementId: String?, elementText: String): String {
            val leaf = (elementId?.substringAfterLast('/')?.ifBlank { null } ?: elementText)
                .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "el" }
            val scr = screen.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "screen" }
            return "button.$app.$scr.$leaf"
        }
    }
}
