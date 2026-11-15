package com.ether4o4.odcm.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import com.ether4o4.odcm.agent.AgentService
import com.ether4o4.odcm.agent.CompanionService
import com.ether4o4.odcm.agent.TaskLog
import com.ether4o4.odcm.control.ControlService
import com.ether4o4.odcm.core.AutonomyLevel
import com.ether4o4.odcm.data.SecretStore
import com.ether4o4.odcm.data.Settings

/** One scrolling screen: keys, settings, and a box to type a task. Plain Views, no UI framework. */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var secrets: SecretStore
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private var keySaved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        secrets = SecretStore(this)
        keySaved = secrets.has(SecretStore.ANTHROPIC_KEY)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = dp(16); setPadding(p, p, p, p) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(header("ODCM"))

        statusView = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, dp(8)) }
        root.addView(statusView)

        root.addView(label("Claude API key"))
        val keyField = edit(if (secrets.has(SecretStore.ANTHROPIC_KEY)) "••••••••" else "", password = true)
        root.addView(keyField)
        root.addView(button("Save key") {
            val v = keyField.text.toString()
            if (v.isNotBlank() && v != "••••••••") {
                secrets.put(SecretStore.ANTHROPIC_KEY, v.trim()); keySaved = true; toast("Key saved"); refreshStatus()
            }
        })

        root.addView(label("Model"))
        val modelField = edit(settings.model)
        root.addView(modelField)

        root.addView(label("Autonomy"))
        val autonomy = TextView(this)
        val levels = AutonomyLevel.entries
        var levelIndex = levels.indexOf(settings.autonomy).coerceAtLeast(0)
        fun showLevel() { autonomy.text = "Level: ${levels[levelIndex].name.lowercase()}" }
        showLevel()
        root.addView(autonomy)
        root.addView(button("Change autonomy") { levelIndex = (levelIndex + 1) % levels.size; showLevel() })

        root.addView(label("Rules (plain English, one per line)"))
        val rulesField = edit(settings.rules, lines = 3)
        root.addView(rulesField)

        root.addView(label("Blocked apps (package names, comma-separated)"))
        val blockedField = edit(settings.blockedPackages, lines = 2)
        root.addView(blockedField)

        root.addView(label("Telegram chat name to listen to (blank = off)"))
        val chatField = edit(settings.inboxChat)
        root.addView(chatField)
        root.addView(label("Passphrase to require (blank = none)"))
        val phraseField = edit(settings.inboxPassphrase)
        root.addView(phraseField)

        root.addView(button("Save settings") {
            settings.model = modelField.text.toString()
            settings.autonomy = levels[levelIndex]
            settings.rules = rulesField.text.toString()
            settings.blockedPackages = blockedField.text.toString()
            settings.inboxChat = chatField.text.toString()
            settings.inboxPassphrase = phraseField.text.toString()
            toast("Saved")
        })

        root.addView(divider())
        root.addView(button("Open Accessibility settings") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Open Notification access settings") {
            startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(hint("Enable ODCM in both, plus allow restricted settings if Android asks."))

        root.addView(divider())
        root.addView(label("Companion overlay"))
        root.addView(button("Show companion") {
            if (!CompanionService.canDrawOverlays(this)) {
                toast("Allow 'display over other apps' for ODCM, then tap again")
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                CompanionService.start(this)
                toast("Companion shown — drag it, tap ⤢/—/✕ to resize or close")
            }
        })
        root.addView(button("Hide companion") { CompanionService.stop(this) })
        root.addView(button("Choose companion image") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            startActivityForResult(intent, REQ_PICK_IMAGE)
        })
        root.addView(hint("Pick any image (PNG/JPG, or an animated GIF/WebP) for the face. Drag to move; ⤢ full, — bubble, ✕ close."))

        root.addView(divider())
        root.addView(label("Try a task"))
        val taskField = edit("", lines = 2)
        root.addView(taskField)
        root.addView(button("Run") {
            val task = taskField.text.toString().trim()
            if (task.isEmpty()) { toast("Type a task first"); return@button }
            if (ControlService.get() == null) { toast("Enable Accessibility for ODCM first"); refreshStatus(); return@button }
            if (!keySaved) { toast("Save your Claude API key first"); return@button }
            settings.model = modelField.text.toString()
            settings.autonomy = levels[levelIndex]
            settings.rules = rulesField.text.toString()
            settings.blockedPackages = blockedField.text.toString()
            AgentService.start(this, task, replyKey = null)
            toast("Running — watch the log below, or the overlay on other screens")
        })

        root.addView(divider())
        root.addView(label("Live log"))
        logView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(logView)
        root.addView(button("Clear log") { TaskLog.clear() })
    }

    private val logListener: (List<String>) -> Unit = { lines ->
        logView.text = lines.takeLast(40).joinToString("\n").ifEmpty { "(no activity yet)" }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        TaskLog.addListener(logListener)
    }

    override fun onPause() {
        super.onPause()
        TaskLog.removeListener(logListener)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            settings.avatarUri = uri.toString()
            CompanionService.refresh(this)
            toast(if (CompanionService.isRunning) "Companion image updated" else "Image saved — shows when you open the companion")
        }
    }

    private fun refreshStatus() {
        val acc = if (ControlService.get() != null) "ON" else "OFF — enable in Accessibility settings"
        val key = if (keySaved) "set" else "missing"
        statusView.text = "Accessibility: $acc\nAPI key: $key"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun header(s: String) = TextView(this).apply { text = s; textSize = 24f; setPadding(0, 0, 0, dp(12)) }
    private fun label(s: String) = TextView(this).apply { text = s; textSize = 13f; setPadding(0, dp(12), 0, dp(4)) }
    private fun hint(s: String) = TextView(this).apply { text = s; textSize = 12f; alpha = 0.7f; setPadding(0, dp(4), 0, 0) }
    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).also { it.topMargin = dp(16); it.bottomMargin = dp(4) }
        setBackgroundColor(0x33888888)
    }

    private fun edit(value: String, password: Boolean = false, lines: Int = 1) = EditText(this).apply {
        setText(value)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            lines > 1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (lines > 1) { minLines = lines; maxLines = lines + 2 }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    @Suppress("unused") private fun unusedCheckbox() = CheckBox(this)

    private companion object {
        const val REQ_PICK_IMAGE = 2001
    }
}
