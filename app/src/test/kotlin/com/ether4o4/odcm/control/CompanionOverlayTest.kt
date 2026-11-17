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
            assertEquals(View.GONE, children.single { it.contentDescription == "Companion text drawer" }.visibility)
            assertTrue(children.any { it.contentDescription == "Start voice conversation" })
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
    @Test @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun resizeKeepsAnchorAndRestoresSizeAndPositionAfterRecreation() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.getSharedPreferences("companion_geometry", 0).edit().clear().commit()
            val overlay = CompanionOverlay(activity)
            overlay.show()
            val panel = root(overlay)
            val lp = panel.layoutParams as WindowManager.LayoutParams
            val x = lp.x; val y = lp.y; val w = lp.width; val h = lp.height
            val grip = views(panel).single { it.contentDescription == "Resize companion" }
            assertTrue(touch(grip, MotionEvent.ACTION_DOWN))
            assertTrue(touch(grip, MotionEvent.ACTION_MOVE, 60f, 70f))
            assertTrue(touch(grip, MotionEvent.ACTION_UP, 60f, 70f))
            assertEquals(x, lp.x); assertEquals(y, lp.y)
            assertEquals(w + 40, lp.width); assertEquals(h + 50, lp.height)
            val saved = listOf(lp.x, lp.y, lp.width, lp.height)
            overlay.close()
            val restored = CompanionOverlay(activity)
            try {
                restored.show()
                val params = root(restored).layoutParams as WindowManager.LayoutParams
                assertEquals(saved, listOf(params.x, params.y, params.width, params.height))
            } finally { restored.close() }
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun keyboardFitKeepsComposerVisibleAndRestoresSavedGeometry() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.getSharedPreferences("companion_geometry", 0).edit().clear().commit()
            val overlay = CompanionOverlay(activity)
            try {
                overlay.show()
                val panel = root(overlay)
                val lp = panel.layoutParams as WindowManager.LayoutParams
                val saved = listOf(lp.x, lp.y, lp.width, lp.height)
                views(panel).single { it.contentDescription == "Show conversation and keyboard" }.performClick()
                val edit = views(panel).filterIsInstance<EditText>().single()
                edit.setText("Keep typing here")
                touch(edit, MotionEvent.ACTION_DOWN)
                val insets = android.view.WindowInsets.Builder()
                    .setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 420, 420))
                    .setVisible(android.view.WindowInsets.Type.ime(), true).build()
                panel.dispatchApplyWindowInsets(insets)
                assertTrue(lp.y + lp.height <= 891 - 420)
                panel.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, lp.width, lp.height)
                val composer = edit.parent as View
                assertTrue(composer.bottom <= panel.height)
                assertTrue(edit.height > 0)
                assertEquals("Keep typing here", edit.text.toString())
                assertEquals(0, lp.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                panel.dispatchApplyWindowInsets(android.view.WindowInsets.Builder().setVisible(android.view.WindowInsets.Type.ime(), false).build())
                assertEquals(saved, listOf(lp.x, lp.y, lp.width, lp.height))
            } finally { overlay.close() }
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun avatarFillsSmallPanelAndDrawerTogglesWithoutLosingDraftOrGeometry() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.getSharedPreferences("companion_geometry", 0).edit().clear().commit()
            val overlay = CompanionOverlay(activity)
            try {
                overlay.show()
                val panel = root(overlay)
                val grip = views(panel).single { it.contentDescription == "Resize companion" }
                touch(grip, MotionEvent.ACTION_DOWN)
                touch(grip, MotionEvent.ACTION_MOVE, 20f, -220f)
                touch(grip, MotionEvent.ACTION_UP, 20f, -220f)
                val lp = panel.layoutParams as WindowManager.LayoutParams
                val saved = listOf(lp.x, lp.y, lp.width, lp.height)
                panel.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, lp.width, lp.height)
                val scene = views(panel).single { it.contentDescription == "Companion scene" }
                assertEquals(View.VISIBLE, scene.visibility)
                assertEquals(panel.width, scene.width); assertEquals(panel.height, scene.height)
                val drawer = views(panel).single { it.contentDescription == "Companion text drawer" }
                assertEquals(View.GONE, drawer.visibility)
                views(panel).single { it.contentDescription == "Show conversation and keyboard" }.performClick()
                assertEquals(View.VISIBLE, drawer.visibility)
                val edit = views(panel).filterIsInstance<EditText>().single()
                edit.setText("My unsent draft")
                views(panel).single { it.contentDescription == "Hide conversation and keyboard" }.performClick()
                assertEquals(View.GONE, drawer.visibility)
                views(panel).single { it.contentDescription == "Show conversation and keyboard" }.performClick()
                assertEquals("My unsent draft", edit.text.toString())
                assertEquals(saved, listOf(lp.x, lp.y, lp.width, lp.height))
                var micTaps = 0
                overlay.onMic = { micTaps++ }
                views(panel).single { it.contentDescription == "Start voice conversation" }.performClick()
                assertEquals(1, micTaps)
            } finally { overlay.close() }
        }
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    fun landscapeKeyboardLeavesTheTypedLineInsideTheDrawer() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            c.get().getSharedPreferences("companion_geometry", 0).edit().clear().commit()
            val overlay = CompanionOverlay(c.get())
            try {
                overlay.show()
                val panel = root(overlay)
                views(panel).single { it.contentDescription == "Show conversation and keyboard" }.performClick()
                val edit = views(panel).filterIsInstance<EditText>().single()
                edit.setText("first line\nsecond line\nthird line")
                touch(edit, MotionEvent.ACTION_DOWN)
                panel.dispatchApplyWindowInsets(android.view.WindowInsets.Builder()
                    .setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, 240))
                    .setVisible(android.view.WindowInsets.Type.ime(), true).build())
                val lp = panel.layoutParams as WindowManager.LayoutParams
                panel.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, lp.width, lp.height)
                val composer = edit.parent as View
                val drawer = composer.parent as View
                assertEquals(1, edit.maxLines)
                assertTrue(edit.height > 0)
                assertTrue(composer.bottom <= drawer.height)
                assertTrue(drawer.bottom <= panel.height)
                assertEquals(View.VISIBLE, views(panel).single { it.contentDescription == "Companion scene" }.visibility)
            } finally { overlay.close() }
        }
    }

}
