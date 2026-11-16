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

/** Bubble, conversation panel, and full conversation + diagnostics. The media decoder is retained. */
class CompanionOverlay(private val context: Context) {
    enum class Mode { FULL, BOX, BUBBLE }
    private val wm = context.getSystemService(WindowManager::class.java)
    private val media = CompanionMedia(context)
    private var view: View? = null
    private var mode = Mode.BOX
    private var posX = 24
    private var posY = 100
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
    private var preferredHeight = 0
    private var chatScrollY = 0
    private var chatFollows = true
    private var logScrollY = 0
    private var logFollows = true
    var onClose: (() -> Unit)? = null
    var onSend: ((String) -> Boolean)? = null
    var onStop: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null

    fun show(startMode: Mode = Mode.BOX) { mode = startMode; render() }
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
        logText = null; logScroll = null; statusText = null; face = null
        pauseButton = null; stopButton = null
    }
    fun close() { hide(); media.close() }

    private fun render() {
        hide()
        val (availableWidth, availableHeight) = availableSize()
        val margin = dp(8)
        val maxWidth = (availableWidth - 2 * margin).coerceAtLeast(1)
        val maxHeight = (availableHeight - 2 * margin).coerceAtLeast(1)
        val width = when (mode) { Mode.BUBBLE -> dp(64); Mode.BOX -> dp(312); Mode.FULL -> maxWidth }.coerceAtMost(maxWidth)
        preferredHeight = when (mode) { Mode.BUBBLE -> dp(64); Mode.BOX -> dp(490); Mode.FULL -> maxHeight }.coerceAtMost(maxHeight)
        val root = if (mode == Mode.BUBBLE) buildBubble() else buildPanel(preferredHeight, mode == Mode.FULL)
        val params = WindowManager.LayoutParams(width, preferredHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (mode == Mode.FULL) margin else posX
            y = if (mode == Mode.FULL) margin else posY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        clamp(params)
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) releaseKeyboard()
            false
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val imeHeight = insets.getInsets(WindowInsets.Type.ime()).bottom
            val keyboard = editing && imeHeight > 0
            face?.visibility = if (keyboard) View.GONE else View.VISIBLE
            val lp = view?.layoutParams as? WindowManager.LayoutParams
            if (lp != null) {
                val available = availableSize().second
                val targetHeight = minOf(preferredHeight, (available - if (keyboard) imeHeight else 0).coerceAtLeast(dp(160)))
                val targetY = if (keyboard) minOf(lp.y, (available - imeHeight - targetHeight).coerceAtLeast(0)) else if (mode == Mode.FULL) dp(8) else posY
                if (lp.height != targetHeight || lp.y != targetY) {
                    lp.height = targetHeight; lp.y = targetY
                    runCatching { wm.updateViewLayout(root, lp) }
                }
            }
            insets
        }
        runCatching { wm.addView(root, params) }.onSuccess {
            view = root
            updateChat(); updateLogs()
            chatScroll?.let { s -> s.post { if (chatFollows) s.fullScroll(View.FOCUS_DOWN) else s.scrollTo(0, chatScrollY) } }
            logScroll?.let { s -> s.post { if (logFollows) s.fullScroll(View.FOCUS_DOWN) else s.scrollTo(0, logScrollY) } }
        }
    }

    private fun portrait(): ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
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
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = background()
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = label("ODCM", 16f).apply { setTypeface(typeface, Typeface.BOLD); contentDescription = "Drag companion" }
        header.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))
        header.addView(button(if (full) "▭" else "⤢", if (full) "Compact companion" else "Expand companion and logs") { switchMode(if (full) Mode.BOX else Mode.FULL) })
        header.addView(button("—", "Minimize companion") { switchMode(Mode.BUBBLE) })
        header.addView(button("×", "Close companion") { onClose?.invoke() })
        root.addView(header)
        attachDrag(title, null)
        face = portrait().also {
            root.addView(it, LinearLayout.LayoutParams(-1, minOf(dp(if (full) 190 else 164), height / 3)).apply { bottomMargin = dp(5) })
        }
        val status = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        statusText = label("Ready", 11f).apply { maxLines = 2 }
        status.addView(statusText, LinearLayout.LayoutParams(0, dp(34), 1f))
        pauseButton = button("Ⅱ", "Pause or resume task") { onPause?.invoke() }
        stopButton = button("■", "Stop task") { onStop?.invoke() }
        status.addView(pauseButton); status.addView(stopButton)
        root.addView(status)
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
        root.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (full) {
            root.addView(label("Activity log", 12f).apply { setTextColor(Color.rgb(155, 163, 179)); setPadding(dp(4), dp(8), 0, dp(4)) })
            logText = label("", 11f).apply { setPadding(dp(4), dp(2), dp(4), dp(4)) }
            logScroll = ScrollView(context).apply { contentDescription = "Companion activity history"; addView(logText) }
            root.addView(logScroll, LinearLayout.LayoutParams(-1, 0, .55f))
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
        root.addView(composer)
        return root
    }

    private fun send() {
        val message = input?.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) return
        if (onSend?.invoke(message) == true) { draft = ""; input?.setText(""); releaseKeyboard() }
    }
    private fun focusComposer() {
        if (editing) return
        val root = view ?: return
        val lp = root.layoutParams as WindowManager.LayoutParams
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        editing = true
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
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            lp.height = preferredHeight
            lp.y = if (mode == Mode.FULL) dp(8) else posY
            clamp(lp)
            runCatching { wm.updateViewLayout(root, lp) }
        }
        face?.visibility = View.VISIBLE
    }

    private fun updateChat() {
        statusText?.text = snapshot.status
        pauseButton?.visibility = if (snapshot.busy) View.VISIBLE else View.GONE
        pauseButton?.text = if (snapshot.paused) "▶" else "Ⅱ"
        stopButton?.visibility = if (snapshot.busy) View.VISIBLE else View.GONE
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
    internal fun switchMode(value: Mode) { mode = value; render() }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private fun attachDrag(handle: View, tap: (() -> Unit)?) {
        handle.setOnTouchListener { _, e ->
            val lp = view?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                    clamp(lp); posX = lp.x; posY = lp.y
                    runCatching { wm.updateViewLayout(view, lp) }; true
                }
                MotionEvent.ACTION_UP -> { if (abs(e.rawX - downX) + abs(e.rawY - downY) < dp(10)) tap?.invoke(); true }
                else -> false
            }
        }
    }
    private fun availableSize(): Pair<Int, Int> = runCatching {
        val metrics = wm.currentWindowMetrics
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
