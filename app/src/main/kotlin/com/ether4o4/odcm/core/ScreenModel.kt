package com.ether4o4.odcm.core

/** Plain snapshot of one on-screen element. No Android types, so it is unit-testable. */
data class ScreenElement(
    val index: Int,
    val role: String,
    val text: String,
    val viewId: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val password: Boolean,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class ScreenSnapshot(
    val packageName: String,
    val width: Int,
    val height: Int,
    val elements: List<ScreenElement>,
    val truncated: Boolean,
)

/** Turns a snapshot into the compact text the model reads. Kept stable so prompts cache well. */
object ScreenFormatter {
    const val MAX_TEXT = 80

    fun format(snapshot: ScreenSnapshot): String = buildString {
        append("app: ").append(snapshot.packageName)
        append(" | screen ").append(snapshot.width).append('x').append(snapshot.height).append('\n')
        if (snapshot.elements.isEmpty()) {
            append("(no readable elements: the screen may be a game, video, or protected content)\n")
        }
        for (e in snapshot.elements) {
            append('[').append(e.index).append("] ").append(e.role)
            if (e.text.isNotEmpty()) append(" \"").append(clip(e.text)).append('"')
            val flags = buildList {
                if (e.clickable) add("tap")
                if (e.editable) add("edit")
                if (e.scrollable) add("scroll")
                if (e.checked == true) add("on")
                if (e.checked == false) add("off")
                if (e.password) add("password")
            }
            if (flags.isNotEmpty()) append(" (").append(flags.joinToString(",")).append(')')
            append(" @").append(e.centerX).append(',').append(e.centerY)
            append('\n')
        }
        if (snapshot.truncated) append("(list truncated; scroll to see more)\n")
    }

    fun clip(text: String): String {
        val single = text.replace('\n', ' ').replace('"', '\'').trim()
        return if (single.length <= MAX_TEXT) single else single.take(MAX_TEXT - 1) + "…"
    }

    /** Short role names from Android class names, e.g. android.widget.Button -> button. */
    fun roleOf(className: CharSequence?): String {
        val simple = className?.toString()?.substringAfterLast('.')?.lowercase() ?: return "view"
        return when {
            simple.contains("button") -> "button"
            simple.contains("edittext") || simple.contains("textfield") -> "field"
            simple.contains("checkbox") -> "checkbox"
            simple.contains("switch") || simple.contains("toggle") -> "switch"
            simple.contains("image") -> "image"
            simple.contains("textview") || simple == "text" -> "text"
            simple.contains("recyclerview") || simple.contains("listview") || simple.contains("scrollview") -> "list"
            simple.contains("tab") -> "tab"
            simple.isEmpty() -> "view"
            else -> simple
        }
    }
}
