package com.ether4o4.odcm.control

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView

/** Owns only local drawable playback; provider requests never run here. */
internal class CompanionScenePlayer {
    private var image: ImageView? = null
    private var scene: Drawable? = null
    private var screenOn = true
    private var visible = true

    fun bind(target: ImageView) {
        if (image !== target) image?.setImageDrawable(null)
        image = target
        display()
    }

    fun show(drawable: Drawable?) {
        if (scene !== drawable) (scene as? Animatable)?.stop()
        scene = drawable
        display()
    }

    fun setScreenOn(value: Boolean) { screenOn = value; display() }
    fun setVisible(value: Boolean) { visible = value; display() }

    private fun display() {
        image?.let { if (it.drawable !== scene) it.setImageDrawable(scene) }
        val animation = scene as? Animatable ?: return
        if (image != null && screenOn && visible) {
            if (!animation.isRunning) animation.start()
        } else animation.stop()
    }

    fun unbind() {
        image?.setImageDrawable(null)
        image = null
        display()
    }

    fun close() { unbind(); scene = null }
}
