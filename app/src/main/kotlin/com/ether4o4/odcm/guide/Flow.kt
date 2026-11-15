package com.ether4o4.odcm.guide

/**
 * A learned sequence that runs on the device with no model calls: each step waits for an
 * element (by text or view id) and acts on it. A step that cannot be satisfied falls the whole
 * task back to the normal model loop, so a stale flow never strands the agent.
 */
data class FlowStep(
    val act: String,              // tap | type | back | home | swipe_up | swipe_down
    val waitText: String? = null, // element text to wait for
    val waitId: String? = null,   // view id to wait for
    val text: String? = null,     // for type; "$query" is filled with the task's argument
    val optional: Boolean = false,
) {
    fun serialize(): String = buildString {
        append("act=").append(act)
        waitText?.let { append("; text=").append(it) }
        waitId?.let { append("; id=").append(it) }
        text?.let { append("; type=").append(it) }
        if (optional) append("; optional=true")
    }

    companion object {
        fun parse(line: String): FlowStep? {
            val parts = line.removePrefix("-").trim().split(';')
                .mapNotNull { seg ->
                    val eq = seg.indexOf('='); if (eq < 0) null
                    else seg.substring(0, eq).trim() to seg.substring(eq + 1).trim()
                }.toMap()
            val act = parts["act"]?.ifBlank { null } ?: return null
            return FlowStep(
                act = act,
                waitText = parts["text"],
                waitId = parts["id"],
                text = parts["type"],
                optional = parts["optional"] == "true",
            )
        }
    }
}

data class Flow(val note: GuideNote, val steps: List<FlowStep>) {
    val id: String get() = note.id

    companion object {
        /** Flow steps live in the note body, one per line under a `## steps` heading. */
        fun from(note: GuideNote): Flow? {
            if (note.type != NoteType.FLOW) return null
            val stepsSection = note.body.substringAfter("## steps", "").trim()
            if (stepsSection.isEmpty()) return null
            val steps = stepsSection.split('\n').mapNotNull { FlowStep.parse(it) }
            return if (steps.isEmpty()) null else Flow(note, steps)
        }

        fun toBody(title: String, steps: List<FlowStep>): String = buildString {
            if (title.isNotEmpty()) { append(title.trimEnd()); append("\n\n") }
            append("## steps\n")
            for (s in steps) { append("- "); append(s.serialize()); append('\n') }
        }
    }
}

/** Chooses a flow whose triggers the task clearly matches, preferring the most-proven one. */
object FlowMatcher {
    fun match(flows: List<Flow>, task: String, currentApp: String?): Flow? {
        val q = GuideIndex.tokenize(task)
        return flows
            .filter { f -> f.note.app == null || f.note.app == currentApp }
            .filter { f -> f.note.triggers.any { trig ->
                val tt = GuideIndex.tokenize(trig)
                tt.isNotEmpty() && q.containsAll(tt)
            } }
            .maxWithOrNull(compareBy({ it.note.status.ordinal }, { it.note.successes }))
    }
}
