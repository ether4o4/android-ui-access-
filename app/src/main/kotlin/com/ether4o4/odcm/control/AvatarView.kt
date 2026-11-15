package com.ether4o4.odcm.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * A self-drawn animated companion face (adapted from the PixAvatar project): blink, idle bob,
 * talking mouth and a few expressions. Pure Canvas, no image assets, tiny memory footprint.
 * Idle frames are throttled to ~12fps to spare battery; it animates per-frame only while talking.
 */
class AvatarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = System.currentTimeMillis()
    private var nextBlink = 2500L
    private var blinkUntil = 0L
    private var speakingUntil = 0L
    private var expression = Expression.IDLE

    enum class Expression { IDLE, HAPPY, THINKING, SURPRISED, ANNOYED }

    init {
        paint.strokeCap = Paint.Cap.ROUND
        setBackgroundColor(0x00000000)
    }

    fun setExpression(e: Expression) { expression = e; invalidate() }

    fun startSpeaking(durationMs: Long) {
        speakingUntil = System.currentTimeMillis() + durationMs.coerceIn(500L, 120_000L)
        invalidate()
    }

    /** Maps an agent state word to a face. */
    fun setState(state: String) {
        when (state.lowercase()) {
            "thinking", "working", "reading" -> setExpression(Expression.THINKING)
            "speaking", "reply", "done", "happy" -> { setExpression(Expression.HAPPY); startSpeaking(1800L) }
            "error", "blocked" -> setExpression(Expression.ANNOYED)
            "waiting" -> setExpression(Expression.SURPRISED)
            else -> setExpression(Expression.IDLE)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val t = (now - start) / 1000f
        if (now >= nextBlink && now > blinkUntil) {
            blinkUntil = now + 130L
            nextBlink = now + 2200L + Random.nextLong(1800L)
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.5f
        val scale = minOf(w, h) / 300f
        val talking = now < speakingUntil
        val blink = now < blinkUntil
        val bob = sin(t * 1.6f) * 3f * scale

        // head
        paint.style = Paint.Style.FILL
        paint.color = 0xFF1C3A66.toInt()
        canvas.drawOval(RectF(cx - 130 * scale, cy - 130 * scale + bob, cx + 130 * scale, cy + 130 * scale + bob), paint)
        paint.color = 0xFF10203A.toInt()
        canvas.drawOval(RectF(cx - 112 * scale, cy - 112 * scale + bob, cx + 112 * scale, cy + 112 * scale + bob), paint)

        val eyeY = cy - 20 * scale + bob
        drawEye(canvas, cx - 48 * scale, eyeY, 30 * scale, blink, t)
        drawEye(canvas, cx + 48 * scale, eyeY, 30 * scale, blink, t + .2f)

        // mouth
        paint.color = 0xFF7FE0FF.toInt()
        if (talking) {
            val open = (0.55f + 0.45f * sin(t * 18f)) * scale
            paint.style = Paint.Style.FILL
            canvas.drawOval(RectF(cx - 24 * scale, cy + 48 * scale + bob, cx + 24 * scale, cy + (48 + 20 * open) * scale + bob), paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f * scale
            val smile = if (expression == Expression.HAPPY) 14f else 2f
            canvas.drawArc(RectF(cx - 30 * scale, cy + (34 - smile) * scale + bob, cx + 30 * scale, cy + (66 + smile) * scale + bob), 20f, 140f, false, paint)
            paint.style = Paint.Style.FILL
        }

        if (talking) postInvalidateOnAnimation() else postInvalidateDelayed(80)
    }

    private fun drawEye(canvas: Canvas, x: Float, y: Float, r: Float, blink: Boolean, t: Float) {
        paint.color = 0xFF7FE0FF.toInt()
        if (blink) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            canvas.drawLine(x - r * .55f, y, x + r * .55f, y, paint)
            paint.style = Paint.Style.FILL
            return
        }
        val vertical = when (expression) {
            Expression.SURPRISED -> 1.15f
            Expression.THINKING -> .72f
            Expression.ANNOYED -> .6f
            else -> .9f
        }
        canvas.drawOval(RectF(x - r, y - r * vertical, x + r, y + r * vertical), paint)
        paint.color = 0xFF0A1424.toInt()
        val look = sin(t * .7f) * r * .25f
        canvas.drawCircle(x + look, y, r * .46f, paint)
    }
}
