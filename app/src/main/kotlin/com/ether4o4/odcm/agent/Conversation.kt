package com.ether4o4.odcm.agent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(val role: String, val text: String)
data class ChatSnapshot(
    val messages: List<ChatMessage> = emptyList(),
    val partial: String = "",
    val busy: Boolean = false,
    val paused: Boolean = false,
    val status: String = "Ready",
)

/** Small private conversation, separate from diagnostics. Stream fragments are never saved to disk. */
object Conversation {
    private val mutable = MutableStateFlow(ChatSnapshot())
    val state = mutable.asStateFlow()
    private var prefs: SharedPreferences? = null

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("companion_chat", Context.MODE_PRIVATE)
        val saved = runCatching {
            val array = JSONArray(prefs?.getString("messages", "[]"))
            (maxOf(0, array.length() - 40) until array.length()).mapNotNull { n ->
                val m = array.getJSONObject(n)
                val role = m.getString("role")
                if (role !in setOf("user", "assistant", "notice")) null else ChatMessage(role, m.getString("text").take(12000))
            }
        }.getOrDefault(emptyList())
        mutable.value = mutable.value.copy(messages = saved)
    }

    @Synchronized fun user(text: String) { append(ChatMessage("user", text.take(4000))) }
    @Synchronized fun reply(text: String) {
        if (text.isNotBlank() && mutable.value.messages.lastOrNull() != ChatMessage("assistant", text)) append(ChatMessage("assistant", text.take(12000)))
        mutable.value = mutable.value.copy(partial = "")
    }
    @Synchronized fun notice(text: String) { append(ChatMessage("notice", text.take(2000))) }
    @Synchronized fun begin() { mutable.value = mutable.value.copy(busy = true, paused = false, partial = "", status = "Working") }
    @Synchronized fun beginResponse() { mutable.value = mutable.value.copy(partial = "") }
    @Synchronized fun delta(text: String) {
        if (mutable.value.busy) mutable.value = mutable.value.copy(partial = (mutable.value.partial + text).take(20000))
    }
    @Synchronized fun status(text: String) { mutable.value = mutable.value.copy(status = text.take(120)) }
    @Synchronized fun pause(value: Boolean) { mutable.value = mutable.value.copy(paused = value, status = if (value) "Paused" else "Working") }
    @Synchronized fun finish() { mutable.value = mutable.value.copy(busy = false, paused = false, partial = "", status = "Ready") }

    @Synchronized fun context(): String = JSONArray().apply {
        var remaining = 12000
        val recent = mutable.value.messages.filter { it.role != "notice" }.takeLast(12)
        for (m in recent.asReversed()) {
            if (remaining <= 0) break
            val text = m.text.take(remaining)
            put(JSONObject().put("role", m.role).put("text", text))
            remaining -= text.length
        }
    }.let { reversed ->
        JSONArray().apply { for (i in reversed.length() - 1 downTo 0) put(reversed.get(i)) }.toString()
    }

    private fun append(message: ChatMessage) {
        val entries = (mutable.value.messages + message).takeLast(40).toMutableList()
        while (entries.size > 1 && entries.sumOf { it.text.length } > 48000) entries.removeAt(0)
        mutable.value = mutable.value.copy(messages = entries)
        val json = JSONArray().apply { entries.forEach { put(JSONObject().put("role", it.role).put("text", it.text)) } }
        prefs?.edit()?.putString("messages", json.toString())?.apply()
    }
}
