package com.ether4o4.odcm.control

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.ether4o4.odcm.agent.ChatMessage
import com.ether4o4.odcm.agent.ChatSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionOverlayTest {
    private fun root(overlay: CompanionOverlay): View = ReflectionHelpers.getField(overlay, "view")

    private fun touch(view: View, action: Int, x: Float = 20f, y: Float = 20f): Boolean {
        val event = MotionEvent.obtain(0, 100, action, x, y, 0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun minimizedBubbleAcceptsTapAndRestoresConversation() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val overlay = CompanionOverlay(controller.get())
            try {
                overlay.show(CompanionOverlay.Mode.BUBBLE)
                val bubble = root(overlay)
                assertEquals("Open companion chat", bubble.contentDescription)
                assertTrue(touch(bubble, MotionEvent.ACTION_DOWN))
                assertTrue(touch(bubble, MotionEvent.ACTION_UP))
                assertNotSame(bubble, root(overlay))
                assertTrue(views(root(overlay)).any { it is EditText })
            } finally { overlay.close() }
        }
    }

    @Test fun minimizedBubbleDragsWithoutOpeningEvenWhenGestureReturnsToStart() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val overlay = CompanionOverlay(controller.get())
            try {
                overlay.show(CompanionOverlay.Mode.BUBBLE)
                val bubble = root(overlay)
                val lp = bubble.layoutParams as WindowManager.LayoutParams
                val originalX = lp.x
                val originalY = lp.y
                assertTrue(touch(bubble, MotionEvent.ACTION_DOWN))
                assertTrue(touch(bubble, MotionEvent.ACTION_MOVE, 90f, 90f))
                assertTrue(lp.x != originalX || lp.y != originalY)
                assertTrue(touch(bubble, MotionEvent.ACTION_MOVE))
                assertTrue(touch(bubble, MotionEvent.ACTION_UP))
                assertSame(bubble, root(overlay))
                assertTrue(touch(bubble, MotionEvent.ACTION_DOWN))
                assertTrue(touch(bubble, MotionEvent.ACTION_CANCEL))
                assertSame(bubble, root(overlay))
                assertTrue(touch(bubble, MotionEvent.ACTION_DOWN))
                assertTrue(touch(bubble, MotionEvent.ACTION_UP))
                assertTrue(views(root(overlay)).any { it is EditText })
            } finally { overlay.close() }
        }
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    @Test fun compactPanelHasBottomComposerAndConversationButNoLogs() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val overlay = CompanionOverlay(controller.get())
            val panel = overlay.buildPanel(700, false) as ViewGroup
            overlay.setLogs(listOf("DIAGNOSTIC-ONLY"))
            overlay.setConversation(ChatSnapshot(messages = listOf(ChatMessage("user", "Hello"), ChatMessage("assistant", "Hi Capo"))))
            val children = views(panel)
            assertTrue(children.filterIsInstance<TextView>().any { "Hi Capo" in it.text.toString() })
            assertFalse(children.filterIsInstance<TextView>().any { "DIAGNOSTIC-ONLY" in it.text.toString() || "Activity log" == it.text.toString() })
            assertTrue(views(panel.getChildAt(panel.childCount - 1)).any { it is EditText })
            overlay.close()
        }
    }

    @Test fun fullPanelShowsLogsAndClosingLayoutPreservesDraft() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val overlay = CompanionOverlay(controller.get())
            val panel = overlay.buildPanel(900, true)
            overlay.setLogs(listOf("Action verified"))
            assertTrue(views(panel).filterIsInstance<TextView>().any { it.text.toString() == "Action verified" })
            views(panel).filterIsInstance<EditText>().single().setText("Keep this draft")
            overlay.hide()
            val restored = overlay.buildPanel(700, false)
            assertEquals("Keep this draft", views(restored).filterIsInstance<EditText>().single().text.toString())
            overlay.close()
        }
    }

    @Test fun sendDeliversTypedTextOnceAndClearsOnlyWhenAccepted() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val overlay = CompanionOverlay(controller.get())
            val panel = overlay.buildPanel(700, false)
            val edit = views(panel).filterIsInstance<EditText>().single()
            val sent = mutableListOf<String>()
            overlay.onSend = { sent += it; true }
            edit.setText("  Find my note  ")
            views(panel).single { it.contentDescription == "Send message" }.performClick()
            assertEquals(listOf("Find my note"), sent)
            assertEquals("", edit.text.toString())
            overlay.onSend = { false }
            edit.setText("Retain on failure")
            views(panel).single { it.contentDescription == "Send message" }.performClick()
            assertEquals("Retain on failure", edit.text.toString())
            overlay.close()
        }
    }
}
