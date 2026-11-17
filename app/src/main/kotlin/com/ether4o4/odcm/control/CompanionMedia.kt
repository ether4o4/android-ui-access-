package com.ether4o4.odcm.control

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import com.ether4o4.odcm.R
import com.ether4o4.odcm.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One retained decoder per scene: text/log updates never seek, re-open or restart the working loop. */
class CompanionMedia(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = CompanionScenePlayer()
    private var working: Drawable? = null
    private var sleeping: Drawable? = null
    private var resting: Drawable? = null
    private var active = false
    private var generation = 0

    init { load() }

    fun bind(target: ImageView) {
        player.bind(target)
        display()
    }

    fun setWorking(value: Boolean) {
        if (active == value) return
        active = value
        display()
    }

    fun setScreenOn(value: Boolean) { player.setScreenOn(value) }
    fun setVisible(value: Boolean) { player.setVisible(value) }

    fun reload() { load() }

    private fun load() {
        val current = ++generation
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                val work = working ?: decode(ImageDecoder.createSource(context.resources, R.drawable.companion_working))
                val sleep = sleeping ?: decode(ImageDecoder.createSource(context.resources, R.drawable.companion_sleeping))
                val custom = Settings(context).avatarUri
                val rest = if (custom.isNotBlank()) runCatching {
                    decode(ImageDecoder.createSource(context.contentResolver, Uri.parse(custom)))
                }.getOrNull() else null
                Triple(work, sleep, rest ?: sleep ?: decode(ImageDecoder.createSource(context.resources, R.drawable.companion_resting)))
            }
            if (current != generation) return@launch
            working = decoded.first
            sleeping = decoded.second
            resting = decoded.third
            display()
        }
    }

    private fun decode(source: ImageDecoder.Source): Drawable? = runCatching {
        ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val ratio = minOf(1f, 640f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
        }.also { (it as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE }
    }.getOrNull()

    private fun display() {
        val shown = if (active) working ?: resting else resting
        player.show(shown, if (shown === sleeping) .36f else .5f)
    }

    fun unbind() {
        player.unbind()
    }

    fun close() { generation++; player.close(); scope.cancel(); working = null; sleeping = null; resting = null }
}
