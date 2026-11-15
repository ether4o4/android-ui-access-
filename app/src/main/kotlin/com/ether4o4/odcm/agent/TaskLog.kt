package com.ether4o4.odcm.agent

import android.os.Handler
import android.os.Looper

/**
 * A small in-memory log of what the agent is doing, so the app can show progress on screen.
 * Survives the activity going to the background; the activity subscribes while visible.
 */
object TaskLog {
    private const val CAP = 200
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: ((List<String>) -> Unit)? = null

    @Synchronized
    fun add(line: String) {
        val stamped = "${time()}  $line"
        lines.addLast(stamped)
        while (lines.size > CAP) lines.removeFirst()
        val snap = lines.toList()
        val l = listener
        if (l != null) main.post { l(snap) }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        val l = listener
        if (l != null) main.post { l(emptyList()) }
    }

    fun setListener(l: ((List<String>) -> Unit)?) {
        listener = l
        if (l != null) { val snap = snapshot(); main.post { l(snap) } }
    }

    private fun time(): String {
        val now = System.currentTimeMillis() / 1000
        val h = (now / 3600) % 24
        val m = (now / 60) % 60
        val s = now % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
