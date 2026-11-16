package com.ether4o4.odcm.control

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ether4o4.odcm.data.Settings
import kotlin.math.abs

/** A draggable companion with a central portrait and a compact, expandable activity log. */
class CompanionOverlay(private val context: Context) {

    enum class Mode { FULL, BOX, BUBBLE }

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var textView: TextView? = null
    private var previewLines: List<TextView> = emptyList()
    private var scroll: ScrollView? = null
    private var mode = Mode.BOX
    private var posX = 40
    private var posY = 160
    private var logsExpanded = false
    private var logEntries: List<String> = emptyList()
    private var historyScrollY = 0
    private var historyFollowingTail = true
    private val avatar = AvatarView(context)
    private var customImage: Drawable? = loadCustomImage()
    var onClose: (() -> Unit)? = null

    /** Reflect an agent state word on the drawn face (ignored when a custom image is set). */
    fun setState(state: String) {
        if (customImage == null) avatar.post { avatar.setState(state) }
    }

    fun reloadAvatar() {
        (customImage as? AnimatedImageDrawable)?.stop()
        customImage = loadCustomImage()
        refreshLayout()
    }

    /** Refit the visible window after rotation or display-size changes. */
    fun refreshLayout() {
        if (view != null) render()
    }

    private fun loadCustomImage(): Drawable? {
        val uri = Settings(context).avatarUri.ifBlank { return null }
        return runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri)))
        }.getOrNull()
    }

    private fun faceView(fillPanel: Boolean): View {
        val img = customImage
        return if (img != null) {
            ImageView(context).apply {
                setImageDrawable(img)
                // Fill the portrait panel while preserving the image's aspect ratio.
                scaleType = if (fillPanel) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                contentDescription = "Companion image"
                (img as? AnimatedImageDrawable)?.start()
            }
        } else {
            detachedAvatar().apply { contentDescription = "Companion face" }
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun show(startMode: Mode = Mode.BOX) {
        mode = startMode
        render()
    }

    /** Keep entries independently of the current view, including while minimized to a bubble. */
    fun setLogs(lines: List<String>) {
        logEntries = lines.takeLast(200).toList()
        view?.post { updateLogViews() }
    }

    private fun updateLogViews() {
        val entries = logEntries.ifEmpty { listOf("Idle. Give me a task.") }
        val preview = entries.takeLast(3)
        previewLines.forEachIndexed { index, line ->
            line.text = preview.getOrNull(index)?.replace('\n', ' ')?.replace('\r', ' ').orEmpty()
            line.visibility = if (index < preview.size) View.VISIBLE else View.GONE
        }
        scroll?.let { sv ->
            val child = sv.getChildAt(0)
            val followTail = child == null || child.height - sv.height - sv.scrollY <= dp(24)
            textView?.text = entries.joinToString("\n\n")
            if (followTail) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun hide() {
        scroll?.let { sv ->
            historyScrollY = sv.scrollY
            historyFollowingTail = (sv.getChildAt(0)?.height ?: 0) - sv.height - sv.scrollY <= dp(24)
        }
        view?.let { runCatching { wm.removeView(it) } }
        (customImage as? AnimatedImageDrawable)?.stop()
        view = null
        textView = null
        previewLines = emptyList()
        scroll = null
    }

    private fun render() {
        hide()
        val (availableWidth, availableHeight) = availableSize()
        val margin = dp(12)
        val maxWidth = (availableWidth - margin * 2).coerceAtLeast(1)
        val maxHeight = (availableHeight - margin * 2).coerceAtLeast(1)
        val width = when (mode) {
            Mode.BUBBLE -> dp(64).coerceAtMost(maxWidth)
            Mode.BOX -> dp(260).coerceAtMost(maxWidth)
            Mode.FULL -> maxWidth
        }
        val height = when (mode) {
            Mode.BUBBLE -> dp(64).coerceAtMost(maxHeight)
            Mode.BOX -> dp(390).coerceAtMost(maxHeight)
            Mode.FULL -> maxHeight
        }
        val root = if (mode == Mode.BUBBLE) buildBubble() else buildPanel(height, mode == Mode.FULL)
        val lp = WindowManager.LayoutParams(
            width, height, overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (mode == Mode.FULL) margin else posX
            y = if (mode == Mode.FULL) margin else posY
        }
        clampPosition(lp)
        runCatching { wm.addView(root, lp) }.onSuccess {
            view = root
            updateLogViews()
            scroll?.let { sv -> sv.post {
                if (historyFollowingTail) sv.fullScroll(View.FOCUS_DOWN) else sv.scrollTo(0, historyScrollY)
            } }
        }
    }

    private fun buildBubble(): View {
        val holder = FrameLayout(context).apply {
            background = circle(Color.argb(235, 12, 20, 36))
            clipToOutline = true
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(faceView(false), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            contentDescription = "Open companion"
        }
        attachDrag(holder) { toggleTo(Mode.BOX) }
        return holder
    }

    private fun detachedAvatar(): AvatarView {
        (avatar.parent as? ViewGroup)?.removeView(avatar)
        return avatar
    }

    private fun buildPanel(height: Int, big: Boolean): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "ODCM"
            setTextColor(Color.WHITE)
            textSize = if (big) 18f else 15f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(40)
            contentDescription = "Drag companion"
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(sizeButton(if (big) "▭" else "⤢", if (big) "Compact companion" else "Enlarge companion") {
            toggleTo(if (big) Mode.BOX else Mode.FULL)
        })
        header.addView(sizeButton("—", "Minimize companion") { toggleTo(Mode.BUBBLE) })
        header.addView(sizeButton("✕", "Close companion") { onClose?.invoke() })
        root.addView(header)
        attachDrag(title, null)

        val face = FrameLayout(context).apply {
            background = panelBg()
            clipToOutline = true
            addView(faceView(true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val faceHeight = if (logsExpanded) minOf(height / 4, dp(if (big) 160 else 104)) else 0
        root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, faceHeight, if (logsExpanded) 0f else 1f).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

        val logHeader = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "Activity"
                textSize = 12f
                setTextColor(Color.rgb(155, 182, 213))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = if (logsExpanded) "Collapse logs" else "Expand logs"
                isAllCaps = false
                textSize = 12f
                minHeight = dp(36)
                minimumHeight = dp(36)
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { logsExpanded = !logsExpanded; render() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        }
        root.addView(logHeader)

        if (logsExpanded) {
            val tv = logText(big)
            val sv = ScrollView(context).apply {
                addView(tv)
                contentDescription = "Companion activity history"
            }
            root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            textView = tv
            scroll = sv
        } else {
            val preview = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            previewLines = List(3) {
                logText(big).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    preview.addView(this)
                }
            }
            root.addView(preview)
        }
        return root
    }

    private fun logText(big: Boolean) = TextView(context).apply {
        setTextColor(Color.rgb(200, 224, 255))
        textSize = if (big) 14f else 12f
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun toggleTo(newMode: Mode) {
        mode = newMode
        render()
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private fun attachDrag(handle: View, onTap: (() -> Unit)?) {
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    val lp = view?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
                    startX = lp.x; startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = view?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    clampPosition(lp)
                    posX = lp.x; posY = lp.y
                    runCatching { wm.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(e.rawX - downX) + abs(e.rawY - downY)
                    if (moved < dp(10) && onTap != null) onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun availableSize(): Pair<Int, Int> = runCatching {
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1) to
            (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
    }.getOrElse {
        context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    private fun clampPosition(lp: WindowManager.LayoutParams) {
        val (width, height) = availableSize()
        lp.x = lp.x.coerceIn(0, (width - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (height - lp.height).coerceAtLeast(0))
    }

    private fun panelBg() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.argb(238, 10, 16, 28))
        setStroke(dp(1), Color.argb(120, 127, 224, 255))
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.argb(150, 127, 224, 255))
    }

    private fun sizeButton(label: String, description: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        textSize = 14f
        minWidth = dp(40); minimumWidth = dp(40)
        minHeight = dp(40); minimumHeight = dp(40)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
