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
    val packageName: String = "",
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
    val surface: String = "",
    val rotation: Int = 0,
    val density: Float = 1f,
    val fontScale: Float = 1f,
    val appVersion: String = "",
    val device: String = "",
)

/** Turns a snapshot into the compact text the model reads. Kept stable so prompts cache well. */
object ScreenFormatter {
    const val MAX_TEXT = 80

    fun format(snapshot: ScreenSnapshot, compact: Boolean = false): String = buildString {
        append("app: ").append(snapshot.packageName)
        append(" | screen ").append(snapshot.width).append('x').append(snapshot.height).append('\n')
        if (snapshot.elements.isEmpty()) {
            append("(no readable elements: the screen may be a game, video, or protected content)\n")
        }
        val shown = if (compact) snapshot.elements.filter { it.clickable || it.editable || it.scrollable || it.checked != null || it.index < 5 }
            else snapshot.elements
        for (e in shown) {
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
            if (e.clickable && !e.password) append(" key=").append(ScreenIdentity.key(e))
            append(" @").append(e.centerX).append(',').append(e.centerY)
            append('\n')
        }
        if (snapshot.truncated) append("(list truncated; scroll to see more)\n")
        if (shown.size < snapshot.elements.size) append("(${snapshot.elements.size - shown.size} non-control elements omitted on this known screen; use read_screen for all visible text)\n")
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

/** Stable controls are matched against a fresh tree, never blindly tapped at saved coordinates. */
object ScreenIdentity {
    fun label(e: ScreenElement): String = when {
        e.password -> "[password field]"
        e.editable -> "[editable field]"
        e.viewId?.contains("clock", true) == true -> "Clock / time"
        !e.clickable && e.checked == null && e.viewId != null -> e.viewId.substringAfterLast('/')
        else -> e.text.take(160)
    }
    fun key(e: ScreenElement): String = hash("${e.packageName}|${e.viewId.orEmpty()}|${e.role}|${label(e)}").take(16)
    fun hash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun resolve(key: String, screen: ScreenSnapshot): ScreenElement? =
        screen.elements.filter { !it.password && key(it) == key }.singleOrNull()
    fun remap(old: ScreenElement, fresh: ScreenSnapshot): ScreenElement? = fresh.elements.filter {
        it.packageName == old.packageName && it.viewId == old.viewId && it.role == old.role &&
            it.editable == old.editable && it.text == old.text && it.checked == old.checked && !it.password
    }.singleOrNull()
    fun sameContent(before: ScreenSnapshot, after: ScreenSnapshot): Boolean {
        fun content(s: ScreenSnapshot) = s.elements.filter { it.packageName.isBlank() || it.packageName == s.packageName }
            .map { "${key(it)}|${if (it.viewId?.contains("clock", true) == true) "clock" else it.text}|${it.checked}|${it.password}" }.sorted()
        return before.packageName == after.packageName && before.surface == after.surface && content(before) == content(after)
    }
}
