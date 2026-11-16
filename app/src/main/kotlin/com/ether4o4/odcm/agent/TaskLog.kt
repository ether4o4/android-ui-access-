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
    private val listeners = ArrayList<(List<String>) -> Unit>()

    @Synchronized
    fun add(line: String) {
        val stamped = "${time()}  $line"
        lines.addLast(stamped)
        while (lines.size > CAP) lines.removeFirst()
        notifyAll(lines.toList())
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun latest(): String = lines.lastOrNull() ?: ""

    @Synchronized
    fun clear() {
        lines.clear()
        notifyAll(emptyList())
    }

    @Synchronized
    fun addListener(l: (List<String>) -> Unit) {
        listeners.add(l)
        val snap = lines.toList()
        main.post { l(snap) }
    }

    @Synchronized
    fun removeListener(l: (List<String>) -> Unit) { listeners.remove(l) }

    private fun notifyAll(snap: List<String>) {
        val copy = listeners.toList()
        main.post { copy.forEach { it(snap) } }
    }

    private fun time(): String {
        val now = System.currentTimeMillis() / 1000
        val h = (now / 3600) % 24
        val m = (now / 60) % 60
        val s = now % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
