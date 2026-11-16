package com.ether4o4.odcm.control

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small control bar drawn as an accessibility overlay: no "display over other apps" permission,
 * plain Views, and nothing on screen while no task runs.
 */
class Overlay(private val service: AccessibilityService) {

    interface Listener {
        fun onStop()
        fun onPauseToggle()
        fun onApproval(approved: Boolean)
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var bar: LinearLayout? = null
    private var status: TextView? = null
    private var pauseButton: Button? = null
    private var approvalRow: LinearLayout? = null
    private var approvalText: TextView? = null
    private var marker: View? = null
    var listener: Listener? = null

    fun show() = main.post {
        if (bar != null) return@post
        val dp = service.resources.displayMetrics.density
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(Color.argb(230, 18, 22, 32))
            }
        }
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val statusView = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 3
            maxWidth = (220 * dp).toInt()
            text = "Starting…"
        }
        val pause = smallButton("⏸") { listener?.onPauseToggle() }
        val stop = smallButton("✕") { listener?.onStop() }.apply { setTextColor(Color.rgb(255, 120, 120)) }
        row.addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(pause)
        row.addView(stop)
        root.addView(row)

        val approval = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val approvalLabel = TextView(service).apply { setTextColor(Color.rgb(255, 214, 120)); textSize = 13f }
        val buttons = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(smallButton("Allow") { answer(true) })
        buttons.addView(smallButton("Deny") { answer(false) })
        approval.addView(approvalLabel)
        approval.addView(buttons)
        root.addView(approval)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * dp).toInt()
            y = (64 * dp).toInt()
        }
        runCatching { wm.addView(root, lp) }.onSuccess {
            bar = root
            status = statusView
            pauseButton = pause
            approvalRow = approval
            approvalText = approvalLabel
        }
    }

    fun status(text: String) = main.post { status?.text = text }

    fun paused(isPaused: Boolean) = main.post { pauseButton?.text = if (isPaused) "▶" else "⏸" }

    fun askApproval(reason: String) = main.post {
        approvalText?.text = "Allow? $reason"
        approvalRow?.visibility = View.VISIBLE
    }

    fun clearApproval() = main.post { approvalRow?.visibility = View.GONE }

    /** Brief ring where the agent is about to tap. It ignores touches. */
    fun mark(x: Int, y: Int) = main.post {
        val dp = service.resources.displayMetrics.density
        val size = (36 * dp).toInt()
        val ring = marker ?: View(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((3 * dp).toInt(), Color.rgb(80, 200, 255))
                setColor(Color.argb(60, 80, 200, 255))
            }
        }.also { marker = it }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - size / 2
            this.y = y - size / 2
        }
        runCatching {
            if (ring.parent == null) wm.addView(ring, lp) else wm.updateViewLayout(ring, lp)
        }
        main.removeCallbacks(hideMarker)
        main.postDelayed(hideMarker, 700)
    }

    private val hideMarker = Runnable {
        marker?.let { if (it.parent != null) runCatching { wm.removeView(it) } }
    }

    fun hide() = main.post {
        main.removeCallbacks(hideMarker)
        hideMarker.run()
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
        status = null
        pauseButton = null
        approvalRow = null
        approvalText = null
    }

    private fun answer(approved: Boolean) {
        approvalRow?.visibility = View.GONE
        listener?.onApproval(approved)
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(service).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
    }
}
