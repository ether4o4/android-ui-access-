package com.ether4o4.odcm.guide

import com.ether4o4.odcm.core.ScreenIdentity
import com.ether4o4.odcm.core.ScreenSnapshot
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

data class ScreenMap(val id: String, val path: String, val markdown: String, val known: Boolean, val changed: Boolean)

/** First-visit screen keys and layout revisions, separated by app, surface and device configuration. */
class ScreenAtlas(private val root: File) {
    @Synchronized fun observe(screen: ScreenSnapshot): ScreenMap? {
        if (screen.elements.isEmpty() || screen.packageName == "unknown") return null
        root.mkdirs()
        val config = "${screen.packageName}|${screen.surface}|${screen.width}x${screen.height}|${screen.rotation}|${screen.density}|${screen.fontScale}|${screen.appVersion}|${screen.device}"
        val structure = screen.elements.joinToString("|") { ScreenIdentity.key(it) }
        val id = ScreenIdentity.hash("$config|$structure").take(24)
        val layout = ScreenIdentity.hash(screen.elements.joinToString("|") {
            "${ScreenIdentity.key(it)}:${it.left},${it.top},${it.right},${it.bottom}:${it.clickable},${it.editable},${it.scrollable}"
        }).take(16)
        val file = File(root, "$id.json")
        val previous = runCatching { JSONObject(file.readText()) }.getOrNull()
        val known = previous != null
        val changed = previous?.optString("layout") != layout
        val visits = (previous?.optInt("observations", 0) ?: 0) + 1
        val first = previous?.optString("firstSeen") ?: Instant.now().toString()
        val path = "ODCM/Apps/${screen.packageName}/$id-$layout.md"
        fun clean(s: String) = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').take(180)
        val markdown = if (!changed && !previous?.optString("markdown").isNullOrBlank()) previous!!.getString("markdown") else buildString {
            append("---\ngenerated_by: ODCM\nobject_type: SCREEN_MAP\nobject_status: OBSERVED\n")
            append("map_id: $id\nlayout_id: $layout\n---\n\n# Screen key — ${screen.packageName}\n\n")
            append("Surface: ${clean(screen.surface)}\n\n")
            append("Device: ${clean(screen.device)} · App version: ${clean(screen.appVersion)}\n\n")
            append("Display: ${screen.width} × ${screen.height} px · Rotation: ${screen.rotation} · Density: ${screen.density} · Font scale: ${screen.fontScale}\n\n")
            append("First observed: $first\n\n")
            append("Coverage: ${if (screen.truncated) "PARTIAL — element limit reached" else "visible accessibility elements only"}. Hidden/offscreen controls are not mapped until visited. Canvas content may require manual teaching.\n\n")
            append("Coordinates originate at the physical screen's top left. Bounds are pixels; normalized centers are fractions of screen width/height. A key must resolve uniquely in a fresh screen before acting. A recorded location is not proof that a tap achieved its goal.\n\n")
            append("| Key | Control | Role | Window/app | Bounds (left, top, right, bottom) | Center (x, y) | Normalized | Actions |\n")
            append("|---|---|---|---|---|---|---|---|\n")
            for (e in screen.elements) {
                val actions = buildList { if (e.clickable) add("tap"); if (e.editable) add("type"); if (e.scrollable) add("scroll"); if (e.password) add("manual only") }.joinToString(", ")
                val normX = String.format(java.util.Locale.ROOT, "%.4f", e.centerX.toDouble() / screen.width.coerceAtLeast(1))
                val normY = String.format(java.util.Locale.ROOT, "%.4f", e.centerY.toDouble() / screen.height.coerceAtLeast(1))
                append("| ${ScreenIdentity.key(e)} | ${clean(ScreenIdentity.label(e))} | ${clean(e.role)} | ${clean(e.packageName)} | ${e.left}, ${e.top}, ${e.right}, ${e.bottom} | ${e.centerX}, ${e.centerY} | $normX, $normY | $actions |\n")
            }
            append("\n## Navigation directive\nLoad this key on return; verify the live control ID/label and current display configuration. Learn every newly observed app, launcher, site and scrolled page. Save a new revision when the layout changes. Never invent coordinates for unseen controls or reuse an index from an earlier screen.\n")
        }
        val data = JSONObject().put("layout", layout).put("firstSeen", first).put("lastSeen", Instant.now().toString())
            .put("observations", visits).put("path", path).put("markdown", markdown)
        val tmp = File(root, "$id.tmp").apply { writeText(data.toString()) }
        // File.renameTo cannot replace an existing target on Windows. Keep the old key intact
        // until the complete new observation is ready, then replace it in a single move.
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return ScreenMap(id, path, markdown, known, changed)
    }

    fun savedMaps(): List<ScreenMap> = root.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
        runCatching { val obj = JSONObject(file.readText()); ScreenMap(file.nameWithoutExtension, obj.getString("path"), obj.getString("markdown"), true, false) }.getOrNull()
    }.orEmpty()

    companion object {
        val BASICS = """
            # Android foundation key

            ODCM records actual device coordinates on first observation. No universal Samsung, Android,
            launcher or website coordinate sheet is assumed. Display size, rotation, density, font scale,
            app version and surface identity are attached to each observed screen key.

            | Basic control | Reliable starting method | Coordinate status |
            |---|---|---|
            | Home | Android global Home action | No coordinates required |
            | Back | Android global Back action | No coordinates required |
            | Recent apps | Android global Recents action | No coordinates required |
            | Notifications | Android global Notifications action | Read the opened panel |
            | Quick settings | Android global Quick settings action | Read the opened panel |
            | Clock / time | Find System UI clock ID or accessibility label | Learn observed bounds |
            | Status and navigation bars | Read exposed System UI windows | Learn observed bounds |
            | App drawer | Launcher-specific icon or configured gesture | Unknown until observed; some launchers have no drawer button |
            | Home icons, folders, widgets | Current launcher accessibility tree | Learn each visible page |
            | App/site controls | Current visible accessibility tree | Learn first visit, then verify on return |

            ## Always build on the foundation
            During every agent task, map a new screen before the next action. On a return visit, load its
            saved key and validate it against the live tree. Add scrolled pages and new apps, sites and
            launchers as they are visited. Store changed layouts as revisions. Coordinates are a hint;
            matching the current control identity is mandatory. A successful dispatch is not a verified
            outcome. Inspect the next screen. Keep unknown or inaccessible controls marked unknown.

            Use “Learn while I browse” to build the same guide during manual use. It is an explicit,
            time-limited session with Stop/Pause; no model calls, microphone, or idle screen recording.
            Password fields and blocked apps are excluded. Editable field values are never stored.
        """.trimIndent()
    }
}
