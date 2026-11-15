package com.ether4o4.odcm.control

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * The persistent companion window: a draggable NPC face plus a readout of what the agent is doing.
 * Three fixed sizes — FULL, BOX, BUBBLE — using the "display over other apps" permission.
 */
class CompanionOverlay(private val context: Context) {

    enum class Mode { FULL, BOX, BUBBLE }

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var textView: TextView? = null
    private var scroll: ScrollView? = null
    private var mode = Mode.BOX
    private var posX = 40
    private var posY = 160
    private val avatar = AvatarView(context)
    var onClose: (() -> Unit)? = null

    /** Reflect an agent state word on the face (thinking, speaking, idle, error…). */
    fun setState(state: String) = avatar.post { avatar.setState(state) }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun show(startMode: Mode = Mode.BOX) {
        mode = startMode
        render()
    }

    fun setText(text: String) {
        textView?.post {
            textView?.text = text
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null; textView = null; scroll = null
    }

    private fun render() {
        hide()
        val root: View = when (mode) {
            Mode.BUBBLE -> buildBubble()
            Mode.BOX -> buildPanel(width = dp(210), height = dp(300), big = false)
            Mode.FULL -> buildPanel(width = screenWidth() - dp(24), height = dp(560), big = true)
        }
        val lp = WindowManager.LayoutParams(
            if (mode == Mode.BUBBLE) dp(64) else root.layoutWidthHint(),
            if (mode == Mode.BUBBLE) dp(64) else root.layoutHeightHint(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
        }
        runCatching { wm.addView(root, lp) }.onSuccess { view = root }
    }

    private fun View.layoutWidthHint(): Int = (tag as? IntArray)?.get(0) ?: WindowManager.LayoutParams.WRAP_CONTENT
    private fun View.layoutHeightHint(): Int = (tag as? IntArray)?.get(1) ?: WindowManager.LayoutParams.WRAP_CONTENT

    private fun buildBubble(): View {
        val holder = LinearLayout(context).apply {
            background = circle(Color.argb(235, 12, 20, 36))
            tag = intArrayOf(dp(64), dp(64))
            addView(detachedAvatar(), LinearLayout.LayoutParams(dp(60), dp(60)))
        }
        attachDrag(holder) { toggleTo(Mode.BOX) }
        return holder
    }

    private fun detachedAvatar(): AvatarView {
        (avatar.parent as? ViewGroup)?.removeView(avatar)
        return avatar
    }

    private fun buildPanel(width: Int, height: Int, big: Boolean): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            tag = intArrayOf(width, height)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val face = detachedAvatar().apply {
            layoutParams = LinearLayout.LayoutParams(dp(if (big) 84 else 48), dp(if (big) 84 else 48))
        }
        val title = TextView(context).apply {
            text = "ODCM"
            setTextColor(Color.WHITE)
            textSize = if (big) 18f else 14f
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(face)
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(sizeButton(if (mode == Mode.FULL) "▭" else "⤢") { toggleTo(if (mode == Mode.FULL) Mode.BOX else Mode.FULL) })
        header.addView(sizeButton("—") { toggleTo(Mode.BUBBLE) })
        header.addView(sizeButton("✕") { onClose?.invoke() })
        root.addView(header)

        val tv = TextView(context).apply {
            setTextColor(Color.rgb(200, 224, 255))
            textSize = if (big) 15f else 12f
            setLineSpacing(0f, 1.1f)
        }
        val sv = ScrollView(context).apply {
            addView(tv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ).also { it.topMargin = dp(6) }
        }
        root.addView(sv)
        textView = tv
        scroll = sv
        attachDrag(header, null)
        return root
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

    private fun sizeButton(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minWidth = dp(36); minimumWidth = dp(36)
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { onClick() }
    }

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
