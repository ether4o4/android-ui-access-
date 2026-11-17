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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.net.Uri
import com.ether4o4.odcm.agent.AgentService
import com.ether4o4.odcm.agent.CompanionService
import com.ether4o4.odcm.agent.TaskLog
import com.ether4o4.odcm.control.ControlService
import com.ether4o4.odcm.core.AutonomyLevel
import com.ether4o4.odcm.data.SecretStore
import com.ether4o4.odcm.data.Settings
import com.ether4o4.odcm.data.ModelProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One scrolling screen: keys, settings, and a box to type a task. Plain Views, no UI framework. */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var secrets: SecretStore
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        secrets = SecretStore(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = dp(16); setPadding(p, p, p, p) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        scroll.applyScreenInsets()

        root.addView(header("ODCM"))
        root.addView(button("Obsidian vault · search and import") {
            startActivity(Intent(this, VaultActivity::class.java))
        })
        root.addView(button("Learn while I browse · screen key") {
            if (ControlService.get() == null) { toast("Enable Accessibility first"); return@button }
            AgentService.learn(this)
            toast("Browse your home screen, drawer, apps and sites. Stop or pause from the control bar.")
        })
        root.addView(hint("Every agent task also learns new screens automatically. Screen keys include actual coordinates and stay separated by app, layout and display settings."))

        statusView = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, dp(8)) }
        root.addView(statusView)

        root.addView(label("AI provider"))
        val providers = ModelProvider.entries
        val providerPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, providers.map { it.label })
            setSelection(providers.indexOf(settings.provider))
        }
        root.addView(providerPicker)
        val providerHelp = hint("")
        root.addView(providerHelp)
        root.addView(button("Open provider key page") {
            val url = when (settings.provider) {
                ModelProvider.ZAI -> "https://z.ai/manage-apikey/apikey-list"
                ModelProvider.GROQ -> "https://console.groq.com/keys"
                ModelProvider.OPENROUTER -> "https://openrouter.ai/settings/keys"
                ModelProvider.GEMINI -> "https://aistudio.google.com/api-keys"
                ModelProvider.HUGGING_FACE -> "https://huggingface.co/settings/tokens"
                ModelProvider.ANTHROPIC -> "https://platform.claude.com/settings/keys"
                else -> null
            }
            if (url == null) toast("Local/custom endpoints do not have a built-in key page")
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        })
        root.addView(label("Provider API key (stored separately on this phone)"))
        val keyField = edit("", password = true)
        root.addView(keyField)
        root.addView(label("Model"))
        val modelField = edit(settings.model)
        root.addView(modelField)
        root.addView(label("API endpoint"))
        val endpointField = edit(settings.endpoint)
        root.addView(endpointField)
        fun showProvider() {
            val provider = settings.provider
            keyField.setText(if (secrets.has(provider.keyName)) "••••••••" else "")
            keyField.hint = if (provider.requiresKey) "API key" else "Optional API key"
            modelField.setText(settings.model)
            endpointField.setText(settings.endpoint)
            endpointField.isEnabled = provider in listOf(ModelProvider.OLLAMA, ModelProvider.CUSTOM)
            providerHelp.text = when (provider) {
                ModelProvider.ZAI -> "GLM-4.7-Flash is listed free by Z.ai; account limits and availability apply. Use a standard API key, not the coding-plan endpoint."
                ModelProvider.GROQ -> "Free tier available with a Groq key; account limits apply. No automatic paid fallback."
                ModelProvider.OPENROUTER -> "openrouter/free routes to free models that support tools. Free request limits apply; multi-step tasks use several requests."
                ModelProvider.GEMINI -> "Free tier available for the preset model, subject to account limits. Google may use free-tier inputs to improve its products."
                ModelProvider.HUGGING_FACE -> "Inference Providers requires credits or a qualifying plan; this is not a free-tier preset."
                ModelProvider.OLLAMA -> "Runs on this phone. Open Termux and type phi first. No API key needed; use a model that supports tool calls."
                ModelProvider.CUSTOM -> "Use an OpenAI-compatible chat/completions endpoint and a model with tool support. Remote endpoints require HTTPS; local HTTP is allowed only on this phone."
                ModelProvider.ANTHROPIC -> "Uses Claude API credits. Local vault search and screen learning work without an API key."
            } + if (provider == ModelProvider.OLLAMA) "" else " During agent tasks, screen text and relevant vault excerpts are sent to the selected provider."
            refreshStatus()
        }
        fun saveProvider(): Boolean {
            val provider = settings.provider
            val endpoint = if (endpointField.isEnabled) endpointField.text.toString().trim() else provider.defaultEndpoint
            val url = endpoint.toHttpUrlOrNull()
            if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
                (!url.isHttps && url.host !in setOf("127.0.0.1", "localhost", "::1"))) {
                toast("Use an HTTPS endpoint, or HTTP on localhost. Do not put a key in the URL.")
                return false
            }
            if (modelField.text.toString().isBlank()) { toast("Enter a model name"); return false }
            settings.model = modelField.text.toString()
            settings.endpoint = endpoint
            val key = keyField.text.toString().trim()
            if (key.isNotEmpty() && key != "••••••••") secrets.put(provider.keyName, key)
            if (secrets.has(provider.keyName)) keyField.setText("••••••••")
            refreshStatus()
            return true
        }
        providerPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settings.provider = providers[position]
                showProvider()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        showProvider()
        root.addView(button("Save provider settings") { if (saveProvider()) toast("Provider settings saved") })

        root.addView(CheckBox(this).apply {
            text = "Economy mode · use Flash-Lite for routine Gemini requests"
            isChecked = settings.economy
            setOnCheckedChangeListener { _, checked -> settings.economy = checked }
        })
        root.addView(hint("Chat uses a small prompt. Exact Home/Back/Open-app commands run locally. Use /task to force phone control, /chat to force conversation, or /deep for a larger response budget and Hydrogen on Gemini. /deep may cost more."))
        root.addView(Button(this).apply {
            fun caption() = "Reasoning effort: ${settings.effort.replaceFirstChar { it.uppercase() }}"
            text = caption()
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Reasoning effort")
                    .setSingleChoiceItems(Settings.EFFORTS.toTypedArray(), Settings.EFFORTS.indexOf(settings.effort).coerceAtLeast(0)) { dialog, index ->
                        settings.effort = Settings.EFFORTS[index]
                        text = caption()
                        dialog.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
        })
        root.addView(button("Last task usage") {
            android.app.AlertDialog.Builder(this).setTitle("Last task usage")
                .setMessage(getSharedPreferences("odcm_usage", MODE_PRIVATE).getString("last_task", "No completed requests yet."))
                .setPositiveButton("OK", null).show()
        })

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
            if (!saveProvider()) return@button
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
        root.addView(hint("Accessibility enables screen learning and control. Notification access is only needed for the optional Telegram inbox. Allow restricted settings if Android asks."))

        root.addView(divider())
        root.addView(label("Companion overlay"))
        root.addView(button("Show companion") {
            if (!CompanionService.canDrawOverlays(this)) {
                toast("Allow 'display over other apps' for ODCM, then tap again")
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                CompanionService.start(this)
                toast("Companion shown — mic to speak, ▾ for chat, ◢ to resize")
            }
        })
        root.addView(button("Android voice settings") {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { toast("Open Android Settings → Text-to-speech output") }
        })
        root.addView(hint("Select your installed offline TTS engine and Heart voice in Android settings. Tap the companion mic for each spoken turn; final replies are read aloud. Recognition prefers on-device support; the system fallback may use the network. No voice endpoint or API key is needed."))
        root.addView(button("Hide companion") { CompanionService.stop(this) })
        root.addView(button("Choose companion image") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            startActivityForResult(intent, REQ_PICK_IMAGE)
        })
        root.addView(hint("The avatar fills the panel at every size (edges crop to fit). Drag the top handle to move and ◢ to resize. ▾ opens chat and typing; ▴ hides all conversation text. Expand then open chat for logs. 🎤 speaks, ■ stops task/speech, — minimizes, ✕ closes."))

        root.addView(divider())
        root.addView(label("Try a task"))
        val taskField = edit("", lines = 2)
        root.addView(taskField)
        root.addView(button("Run") {
            val task = taskField.text.toString().trim()
            if (task.isEmpty()) { toast("Type a task first"); return@button }
            if (ControlService.get() == null) { toast("Enable Accessibility for ODCM first"); refreshStatus(); return@button }
            if (!saveProvider()) return@button
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
        val provider = settings.provider
        val key = if (secrets.has(provider.keyName)) "set" else if (provider.requiresKey) "missing" else "not required"
        statusView.text = "Accessibility: $acc\n${provider.label} · API key: $key"
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
