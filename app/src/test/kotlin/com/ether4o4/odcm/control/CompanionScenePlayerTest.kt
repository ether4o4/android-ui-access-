package com.ether4o4.odcm.control

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionScenePlayerTest {
    private class Loop : Drawable(), Animatable {
        var starts = 0
        private var running = false
        override fun start() { starts++; running = true }
        override fun stop() { running = false }
        override fun isRunning() = running
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun image() = ImageView(RuntimeEnvironment.getApplication())

    @Test fun sleepingRunsWithoutARequestAndUpdatesDoNotRestartIt() {
        val player = CompanionScenePlayer()
        val sleeping = Loop()
        val target = image()
        player.show(sleeping)
        assertFalse(sleeping.isRunning)
        player.bind(target)
        repeat(30) { player.show(sleeping) }
        assertSame(sleeping, target.drawable)
        assertTrue(sleeping.isRunning)
        assertEquals(1, sleeping.starts)
        player.close()
        assertFalse(sleeping.isRunning)
        assertNull(target.drawable)
    }

    @Test fun taskSwitchStopsSleepingAndCompletionReturnsToSleeping() {
        val player = CompanionScenePlayer()
        val sleeping = Loop()
        val writing = Loop()
        player.bind(image())
        player.show(sleeping)
        player.show(writing)
        assertFalse(sleeping.isRunning)
        assertTrue(writing.isRunning)
        player.show(sleeping)
        assertFalse(writing.isRunning)
        assertTrue(sleeping.isRunning)
        player.close()
    }

    @Test fun screenOffKeyboardHiddenAndClosedNeverKeepAnimating() {
        val player = CompanionScenePlayer()
        val sleeping = Loop()
        player.bind(image())
        player.show(sleeping)
        player.setScreenOn(false)
        assertFalse(sleeping.isRunning)
        player.bind(image())
        assertFalse(sleeping.isRunning)
        player.setScreenOn(true)
        assertTrue(sleeping.isRunning)
        player.setVisible(false)
        assertFalse(sleeping.isRunning)
        player.setScreenOn(false)
        player.setVisible(true)
        assertFalse(sleeping.isRunning)
        player.setScreenOn(true)
        assertTrue(sleeping.isRunning)
        player.unbind()
        assertFalse(sleeping.isRunning)
        player.setScreenOn(true)
        assertFalse(sleeping.isRunning)
        player.bind(image())
        assertTrue(sleeping.isRunning)
        player.close()
        assertFalse(sleeping.isRunning)
    }

    @Test fun sceneReplacementAndPanelRebindReleaseOldViewAndAnimation() {
        val player = CompanionScenePlayer()
        val old = Loop()
        val replacement = Loop()
        val first = image()
        val second = image()
        player.bind(first)
        player.show(old)
        player.bind(second)
        assertNull(first.drawable)
        assertSame(old, second.drawable)
        assertEquals(1, old.starts)
        player.show(replacement)
        assertFalse(old.isRunning)
        assertTrue(replacement.isRunning)
        player.show(null)
        assertFalse(replacement.isRunning)
        assertNull(second.drawable)
        player.close()
    }
}
