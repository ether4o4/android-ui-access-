package com.ether4o4.odcm.guide

/**
 * Picks the few notes worth showing the model for the current task and screen.
 * Exact keyword, trigger and app matching; no embeddings, nothing to load, fast on-device.
 */
object GuideIndex {

    private val STOP = setOf(
        "the", "a", "an", "to", "of", "in", "on", "and", "or", "for", "my", "me", "i", "it",
        "is", "at", "with", "from", "this", "that", "your", "you", "please", "can", "could",
    )

    fun tokenize(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in STOP }
            .toSet()

    /**
     * Rank notes for a task on a given app/screen. Higher is more relevant; notes scoring
     * zero are dropped. Trusted notes outrank drafts on a tie.
     */
    fun rank(
        notes: List<GuideNote>,
        task: String,
        currentApp: String?,
        currentScreen: String? = null,
        limit: Int = 5,
    ): List<GuideNote> {
        val queryTokens = tokenize(task)
        return notes.mapNotNull { note ->
            // Relevance must come from the task itself; app/screen only boost an already-relevant note,
            // so we never surface every button in the current app.
            var relevance = 0
            for (trigger in note.triggers) {
                val tt = tokenize(trigger)
                if (tt.isNotEmpty() && queryTokens.containsAll(tt)) relevance += 3
                else if (tt.any { it in queryTokens }) relevance += 1
            }
            for (t in tokenize(note.title)) if (t in queryTokens) relevance += 1
            note.elementText?.let { for (t in tokenize(it)) if (t in queryTokens) relevance += 1 }
            if (relevance <= 0) return@mapNotNull null

            var score = relevance + trust(note.status)
            if (currentApp != null && note.app == currentApp) score += 3
            if (currentScreen != null && note.screen != null &&
                note.screen.equals(currentScreen, ignoreCase = true)
            ) score += 2
            ScoredNote(note, score)
        }
            .sortedWith(compareByDescending<ScoredNote> { it.score }.thenByDescending { it.note.successes })
            .take(limit)
            .map { it.note }
    }

    private fun trust(status: NoteStatus) = when (status) {
        NoteStatus.PROVEN -> 3
        NoteStatus.VERIFIED -> 2
        NoteStatus.TESTED -> 1
        NoteStatus.DRAFT -> 0
    }

    /** Renders the chosen notes as the compact "what you've learned here" context block. */
    fun render(notes: List<GuideNote>): String {
        if (notes.isEmpty()) return ""
        return buildString {
            append("What you've learned about this before:\n")
            for (n in notes) {
                append("- ")
                if (n.elementText != null) append("\"").append(n.elementText).append("\": ")
                append(n.body.ifBlank { n.title }.replace('\n', ' ').take(120))
                append(" [").append(n.status.name.lowercase()).append("]\n")
            }
        }
    }

    private class ScoredNote(val note: GuideNote, val score: Int)
}
