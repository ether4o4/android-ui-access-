package com.ether4o4.odcm.control

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.Editable
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ether4o4.odcm.agent.ChatSnapshot
import kotlin.math.abs

/** Avatar fills the window; conversation is an explicit drawer. The media decoder is retained. */
class CompanionOverlay(private val context: Context) {
    enum class Mode { FULL, BOX, BUBBLE }
    private val wm = context.getSystemService(WindowManager::class.java)
    private val media = CompanionMedia(context)
    private var view: View? = null
    private var mode = Mode.BOX
    private val prefs = context.getSharedPreferences("companion_geometry", Context.MODE_PRIVATE)
    private var geometry = OverlayGeometry(24, 100, 312, 490)
    private var imeBottom = 0
    private var lastApplied: OverlayGeometry? = null
    private var lastFlags = -1
    private var geometryKey = ""
    private var logHeading: View? = null
    private var draft = ""
    private var editing = false
    private var snapshot = ChatSnapshot()
    private var logEntries = emptyList<String>()
    private var chatText: TextView? = null
    private var chatScroll: ScrollView? = null
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private var statusText: TextView? = null
    private var pauseButton: Button? = null
    private var stopButton: Button? = null
    private var input: EditText? = null
    private var face: View? = null
    private var drawer: View? = null
    private var drawerOpen = false
    private var chatButton: Button? = null
    private var preferredHeight = 0
    private var chatScrollY = 0
    private var chatFollows = true
    private var logScrollY = 0
    private var logFollows = true
    var onClose: (() -> Unit)? = null
    var onSend: ((String) -> Boolean)? = null
    var onStop: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onMic: (() -> Unit)? = null

    fun show(startMode: Mode = Mode.BOX) {
        mode = runCatching { Mode.valueOf(prefs.getString("mode", startMode.name) ?: startMode.name) }.getOrDefault(startMode)
        // Explicit bubble calls are also used by accessibility and tests.
        if (startMode == Mode.BUBBLE) mode = startMode
        render()
    }
    fun reloadAvatar() { media.reload() }
    fun refreshLayout() { if (view != null) render() }
    fun setScreenOn(on: Boolean) { media.setScreenOn(on) }
    fun setLogs(lines: List<String>) { logEntries = lines.takeLast(200); updateLogs() }
    fun setConversation(value: ChatSnapshot) {
        snapshot = value
        media.setWorking(value.busy && !value.paused)
        updateChat()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun follows(s: ScrollView) = (s.getChildAt(0)?.height ?: 0) - s.height - s.scrollY <= dp(28)

    fun hide() {
        draft = input?.text?.toString() ?: draft
        chatScroll?.let { chatScrollY = it.scrollY; chatFollows = follows(it) }
        logScroll?.let { logScrollY = it.scrollY; logFollows = follows(it) }
        releaseKeyboard()
        media.unbind()
        view?.let { runCatching { wm.removeView(it) } }
        view = null; input = null; chatText = null; chatScroll = null
        logText = null; logScroll = null; logHeading = null; statusText = null; face = null
        pauseButton = null; stopButton = null; drawer = null; chatButton = null
    }
    fun close() { hide(); media.close() }

    private fun render() {
        hide()
        val (availableWidth, availableHeight) = availableSize()
        val margin = dp(8)
        val maxWidth = (availableWidth - 2 * margin).coerceAtLeast(1)
        val maxHeight = (availableHeight - 2 * margin).coerceAtLeast(1)
        geometryKey = "${context.resources.configuration.orientation}_${mode.name}"
        val defaultWidth = when (mode) { Mode.BUBBLE -> dp(64); Mode.BOX -> dp(312); Mode.FULL -> maxWidth }.coerceAtMost(maxWidth)
        val defaultHeight = when (mode) { Mode.BUBBLE -> dp(64); Mode.BOX -> dp(490); Mode.FULL -> maxHeight }.coerceAtMost(maxHeight)
        val density = context.resources.displayMetrics.density
        geometry = OverlayGeometry(
            (prefs.getFloat("${geometryKey}_x", if (mode == Mode.FULL) 8f else 24f) * density).toInt(),
            (prefs.getFloat("${geometryKey}_y", if (mode == Mode.FULL) 8f else 100f) * density).toInt(),
            (prefs.getFloat("${geometryKey}_w", defaultWidth / density) * density).toInt(),
            (prefs.getFloat("${geometryKey}_h", defaultHeight / density) * density).toInt()
        ).fit(availableWidth, availableHeight)
        preferredHeight = geometry.height
        imeBottom = 0
        lastApplied = null
        val root = if (mode == Mode.BUBBLE) buildBubble() else buildPanel(preferredHeight, mode == Mode.FULL)
        val params = WindowManager.LayoutParams(geometry.width, preferredHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = geometry.x
            y = geometry.y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        }
        clamp(params)
        // The bubble already owns its touch listener for dragging and opening chat.
        // Only panels need outside-touch handling for the keyboard.
        if (mode != Mode.BUBBLE) {
            root.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) releaseKeyboard()
                false
            }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val navigation = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
            imeBottom = if (insets.isVisible(WindowInsets.Type.ime()))
                (insets.getInsets(WindowInsets.Type.ime()).bottom - navigation).coerceAtLeast(0) else 0
            applyGeometry()
            insets
        }
        runCatching { wm.addView(root, params) }.onSuccess {
            view = root
            applyGeometry()
            root.requestApplyInsets()
            updateChat(); updateLogs()
            chatScroll?.let { s -> s.post { if (chatFollows) s.fullScroll(View.FOCUS_DOWN) else s.scrollTo(0, chatScrollY) } }
            logScroll?.let { s -> s.post { if (logFollows) s.fullScroll(View.FOCUS_DOWN) else s.scrollTo(0, logScrollY) } }
        }.onFailure { media.unbind() }
    }

    private fun portrait(): ImageView = AvatarView(context).apply {
        setBackgroundColor(Color.BLACK)
        contentDescription = "Companion scene"
        media.bind(this)
    }
    private fun buildBubble(): View = FrameLayout(context).apply {
        background = background(true)
        clipToOutline = true
        addView(portrait(), FrameLayout.LayoutParams(-1, -1))
        contentDescription = "Open companion chat"
        attachDrag(this) { switchMode(Mode.BOX) }
    }

    internal fun buildPanel(height: Int, full: Boolean): View {
        val root = FrameLayout(context).apply {
            background = background()
            clipToOutline = true
        }
        face = portrait().also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setBackgroundColor(0x99000000.toInt())
        }
        val title = label("⠿", 22f).apply { contentDescription = "Drag companion" }
        header.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))
        header.addView(button(if (full) "▭" else "⤢", if (full) "Compact companion" else "Expand companion and logs") { switchMode(if (full) Mode.BOX else Mode.FULL) })
        header.addView(button("—", "Minimize companion") { switchMode(Mode.BUBBLE) })
        header.addView(button("×", "Close companion") { onClose?.invoke() })
        root.addView(header, FrameLayout.LayoutParams(-1, dp(46), Gravity.TOP))
        attachDrag(title, null)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = background()
            setPadding(dp(8), dp(4), dp(8), dp(4))
            contentDescription = "Companion text drawer"
            visibility = if (drawerOpen) View.VISIBLE else View.GONE
        }
        drawer = content
        root.addView(content, FrameLayout.LayoutParams(-1, drawerHeight(height), Gravity.BOTTOM).apply { bottomMargin = dp(46) })
        statusText = label("Ready", 11f).apply { maxLines = 2 }
        content.addView(statusText, LinearLayout.LayoutParams(-1, dp(30)))
        chatText = label("", 14f).apply {
            setTextIsSelectable(true)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        chatScroll = ScrollView(context).apply {
            contentDescription = "Companion conversation"
            isFillViewport = true
            addView(chatText)
        }
        content.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (full) {
            logHeading = label("Activity log", 12f).apply { setTextColor(Color.rgb(155, 163, 179)); setPadding(dp(4), dp(8), 0, dp(4)) }
            content.addView(logHeading)
            logText = label("", 11f).apply { setPadding(dp(4), dp(2), dp(4), dp(4)) }
            logScroll = ScrollView(context).apply { contentDescription = "Companion activity history"; addView(logText) }
            content.addView(logScroll, LinearLayout.LayoutParams(-1, 0, .55f))
        }
        val composer = LinearLayout(context).apply { gravity = Gravity.BOTTOM; setPadding(0, dp(5), 0, 0) }
        val edit = object : EditText(context) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) releaseKeyboard()
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            hint = "Message ODCM…"
            contentDescription = "Message ODCM"
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(163, 166, 173))
            textSize = 14f
            minLines = 1; maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            filters = arrayOf(InputFilter.LengthFilter(4000))
            setPadding(dp(10), dp(10), dp(8), dp(10))
            background = background()
            setText(draft)
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) focusComposer(); false }
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft = s?.toString().orEmpty() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        input = edit
        composer.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(button("↑", "Send message") { send() })
        content.addView(composer)
        val controls = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setBackgroundColor(0xB0000000.toInt())
        }
        controls.addView(button("🎤", "Start voice conversation") { releaseKeyboard(); onMic?.invoke() })
        chatButton = button(if (drawerOpen) "▴" else "▾", if (drawerOpen) "Hide conversation and keyboard" else "Show conversation and keyboard") { toggleDrawer() }
        controls.addView(chatButton)
        pauseButton = button("Ⅱ", "Pause or resume task") { onPause?.invoke() }
        stopButton = button("■", "Stop task and speech") { onStop?.invoke() }
        controls.addView(pauseButton); controls.addView(stopButton)
        controls.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val grip = label("◢", 22f).apply {
            gravity = Gravity.BOTTOM or Gravity.RIGHT
            contentDescription = "Resize companion"
            setPadding(dp(4), 0, 0, 0)
        }
        controls.addView(grip, LinearLayout.LayoutParams(dp(40), dp(40)))
        attachResize(grip)
        root.addView(controls, FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM))
        updateChat()
        return root
    }

    private fun drawerHeight(height: Int): Int = if (editing || imeBottom > 0)
        (height - dp(92)).coerceAtLeast(1)
    else minOf((height * .60f).toInt(), (height - dp(92)).coerceAtLeast(1))

    private fun toggleDrawer() {
        drawerOpen = !drawerOpen
        if (!drawerOpen) releaseKeyboard()
        drawer?.visibility = if (drawerOpen) View.VISIBLE else View.GONE
        chatButton?.text = if (drawerOpen) "▴" else "▾"
        chatButton?.contentDescription = if (drawerOpen) "Hide conversation and keyboard" else "Show conversation and keyboard"
        applyGeometry()
    }

    private fun send() {
        val message = input?.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) return
        if (onSend?.invoke(message) == true) { draft = ""; input?.setText(""); releaseKeyboard() }
    }
    private fun focusComposer() {
        if (editing || !drawerOpen) return
        val root = view ?: return
        val lp = root.layoutParams as WindowManager.LayoutParams
        lp.flags = lp.flags and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM).inv()
        editing = true
        applyGeometry()
        root.requestApplyInsets()
        runCatching { wm.updateViewLayout(root, lp) }
        input?.post {
            input?.requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    private fun releaseKeyboard() {
        if (!editing) return
        editing = false
        input?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
        view?.let { root ->
            val lp = root.layoutParams as WindowManager.LayoutParams
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            imeBottom = 0
            applyGeometry()
            runCatching { wm.updateViewLayout(root, lp) }
            root.requestApplyInsets()
        }
    }

    private fun applyGeometry() {
        val root = view ?: return
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        val (w, h) = availableSize()
        val visible = geometry.fit(w, (h - imeBottom).coerceAtLeast(1))
        lp.x = visible.x; lp.y = visible.y; lp.width = visible.width; lp.height = visible.height
        // The scene remains the entire background at every size, including while typing.
        media.setVisible(true)
        statusText?.visibility = if ((editing || imeBottom > 0) && visible.height < dp(330)) View.GONE else View.VISIBLE
        input?.maxLines = if (visible.height < dp(350)) 1 else 3
        drawer?.layoutParams?.let {
            val height = drawerHeight(visible.height)
            if (it.height != height) { it.height = height; drawer?.layoutParams = it }
        }
        logHeading?.visibility = if (editing || visible.height < dp(500)) View.GONE else View.VISIBLE
        logScroll?.visibility = if (editing || visible.height < dp(500)) View.GONE else View.VISIBLE
        if (lastApplied != visible || lastFlags != lp.flags) {
            lastApplied = visible; lastFlags = lp.flags
            runCatching { wm.updateViewLayout(root, lp) }
            if (editing) input?.post { input?.let { it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height), false) } }
        }
    }

    private fun saveGeometry() {
        val d = context.resources.displayMetrics.density
        prefs.edit().putString("mode", mode.name)
            .putFloat("${geometryKey}_x", geometry.x / d).putFloat("${geometryKey}_y", geometry.y / d)
            .putFloat("${geometryKey}_w", geometry.width / d).putFloat("${geometryKey}_h", geometry.height / d).apply()
    }

    private fun attachResize(handle: View) {
        var initial = geometry
        var x = 0f; var y = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = view?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
                    initial = OverlayGeometry(lp.x, lp.y, lp.width, lp.height)
                    x = e.rawX; y = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (w, h) = availableSize()
                    geometry = initial.resize((e.rawX - x).toInt(), (e.rawY - y).toInt(), w, (h - imeBottom).coerceAtLeast(1), dp(260), dp(230))
                    preferredHeight = geometry.height
                    applyGeometry()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { saveGeometry(); true }
                else -> false
            }
        }
    }

    private fun updateChat() {
        statusText?.text = snapshot.status
        pauseButton?.visibility = if (snapshot.busy) View.VISIBLE else View.GONE
        pauseButton?.text = if (snapshot.paused) "▶" else "Ⅱ"
        stopButton?.visibility = View.VISIBLE
        val text = SpannableStringBuilder()
        fun entry(role: String, message: String) {
            if (text.isNotEmpty()) text.append("\n\n")
            val start = text.length
            text.append(role).append('\n')
            text.setSpan(StyleSpan(Typeface.BOLD), start, start + role.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(if (role == "You") Color.rgb(240, 147, 135) else Color.rgb(210, 215, 225)), start, start + role.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(message)
        }
        snapshot.messages.takeLast(30).forEach { entry(when (it.role) { "user" -> "You"; "assistant" -> "ODCM"; else -> "Status" }, it.text) }
        if (snapshot.partial.isNotEmpty()) entry("ODCM · writing", snapshot.partial)
        if (text.isEmpty()) text.append("Ask me something or give me a phone task.")
        val follow = chatScroll?.let(::follows) ?: true
        if (chatText?.text?.toString() != text.toString()) chatText?.text = text
        if (follow) chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }
    private fun updateLogs() {
        val follow = logScroll?.let(::follows) ?: true
        logText?.text = logEntries.joinToString("\n\n").ifEmpty { "No activity yet." }
        if (follow) logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }
    internal fun switchMode(value: Mode) { saveGeometry(); mode = value; prefs.edit().putString("mode", value.name).apply(); render() }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private fun attachDrag(handle: View, tap: (() -> Unit)?) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var dragging = false
        if (tap != null) handle.setOnClickListener { tap() }
        handle.setOnTouchListener { _, e ->
            val lp = view?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > touchSlop || abs(e.rawY - downY) > touchSlop) dragging = true
                    if (dragging) {
                        lp.x = startX + (e.rawX - downX).toInt()
                        lp.y = startY + (e.rawY - downY).toInt()
                        clamp(lp)
                        geometry = geometry.copy(x = lp.x, y = lp.y)
                        applyGeometry()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) saveGeometry()
                    if (!dragging && abs(e.rawX - downX) <= touchSlop && abs(e.rawY - downY) <= touchSlop) handle.performClick()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> { if (dragging) saveGeometry(); dragging = false; true }
                else -> false
            }
        }
    }
    private fun availableSize(): Pair<Int, Int> = runCatching {
        val maximum = wm.maximumWindowMetrics
        val metrics = if (maximum.bounds.width() > 1 && maximum.bounds.height() > 1) maximum else wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1) to
            (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
    }.getOrElse { context.resources.displayMetrics.let { it.widthPixels to it.heightPixels } }
    private fun clamp(lp: WindowManager.LayoutParams) {
        val (width, height) = availableSize()
        lp.x = lp.x.coerceIn(0, (width - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (height - lp.height).coerceAtLeast(0))
    }
    private fun background(circle: Boolean = false) = GradientDrawable().apply {
        if (circle) shape = GradientDrawable.OVAL else cornerRadius = dp(14).toFloat()
        setColor(Color.rgb(17, 18, 22)); setStroke(dp(1), Color.rgb(65, 43, 46))
    }
    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Color.rgb(231, 232, 237)); gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(value: String, description: String, click: () -> Unit) = Button(context).apply {
        text = value; contentDescription = description; isAllCaps = false; textSize = 17f
        minWidth = dp(40); minimumWidth = dp(40); minHeight = dp(40); minimumHeight = dp(40)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setPadding(0, 0, 0, 0); setOnClickListener { click() }
    }
}
