package com.ether4o4.odcm.control

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AvatarViewTest {
    @Test fun tallSleepingCropKeepsFaceVisibleAndCoversPanelAfterResize() {
        val context = RuntimeEnvironment.getApplication()
        val avatar = AvatarView(context)
        avatar.setImageDrawable(BitmapDrawable(context.resources, Bitmap.createBitmap(576, 324, Bitmap.Config.ARGB_8888)))
        avatar.focusX = .36f
        avatar.layout(0, 0, 312, 490)
        val face = floatArrayOf(200f, 100f)
        avatar.imageMatrix.mapPoints(face)
        assertTrue(face[0] in 80f..230f)
        for ((width, height) in listOf(312 to 490, 400 to 230)) {
            avatar.layout(0, 0, width, height)
            val bounds = RectF(0f, 0f, 576f, 324f)
            avatar.imageMatrix.mapRect(bounds)
            assertTrue(bounds.left <= 0f && bounds.top <= 0f)
            assertTrue(bounds.right >= width - .01f && bounds.bottom >= height - .01f)
        }
    }
}
