package com.ether4o4.odcm.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ether4o4.odcm.core.AgentAction
import com.ether4o4.odcm.core.TaskControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The hands and eyes. Runs in the app's own process, so the agent calls these methods directly:
 * no cross-process broadcast, nothing exported, and no work while idle.
 */
class ControlService : AccessibilityService(), TaskControl {

    private val reader = ScreenReader()
    @Volatile private var lastRead: ScreenReader.Result? = null

    val overlay by lazy { Overlay(this) }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun readScreen(): com.ether4o4.odcm.core.ScreenSnapshot {
        val metrics = resources.displayMetrics
        val result = reader.read(rootInActiveWindow, metrics.widthPixels, metrics.heightPixels, packageName)
        lastRead = result
        return result.snapshot
    }

    private fun nodeAt(index: Int): AccessibilityNodeInfo? = lastRead?.nodes?.getOrNull(index)

    /** Reports dispatch acceptance; the runner separately observes the resulting screen. */
    override suspend fun perform(action: AgentAction): Boolean = when (action) {
        is AgentAction.Tap -> {
            val node = nodeAt(action.index) ?: return false
            val el = lastRead?.snapshot?.elements?.getOrNull(action.index)
            if (el != null) overlay.mark(el.centerX, el.centerY)
            clickNode(node) || el?.let { tapAt(it.centerX, it.centerY) } ?: false
        }
        is AgentAction.TypeText -> {
            val node = nodeAt(action.index) ?: return false
            setText(node, action.text)
        }
        is AgentAction.Swipe -> swipe(action.direction)
        AgentAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
        AgentAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
        is AgentAction.Wait -> true
        else -> true // OpenApp, Reply, Done handled by the runner, not the hands.
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        repeat(5) {
            val cur = n ?: return false
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = cur.parent
        }
        return false
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private suspend fun swipe(direction: AgentAction.Direction): Boolean {
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val cy = m.heightPixels / 2f
        val dx = m.widthPixels * 0.35f
        val dy = m.heightPixels * 0.35f
        val path = Path()
        when (direction) {
            AgentAction.Direction.UP -> { path.moveTo(cx, cy + dy); path.lineTo(cx, cy - dy) }
            AgentAction.Direction.DOWN -> { path.moveTo(cx, cy - dy); path.lineTo(cx, cy + dy) }
            AgentAction.Direction.LEFT -> { path.moveTo(cx + dx, cy); path.lineTo(cx - dx, cy) }
            AgentAction.Direction.RIGHT -> { path.moveTo(cx - dx, cy); path.lineTo(cx + dx, cy) }
        }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 250)).build())
    }

    private suspend fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), y.toFloat()) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build())
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        val done = CompletableDeferred<Boolean>()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done.complete(true) }
            override fun onCancelled(d: GestureDescription?) { done.complete(false) }
        }, null)
        if (!ok) return false
        return withTimeoutOrNull(3000) { done.await() } ?: false
    }

    companion object {
        @Volatile private var instance: ControlService? = null
        fun get(): ControlService? = instance
    }
}
