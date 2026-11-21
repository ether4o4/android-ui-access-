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
    private var archive: java.io.File? = null

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        archive = java.io.File(context.applicationContext.filesDir, "conversation.jsonl")
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
    @Synchronized fun begin(status: String = "Working") { mutable.value = mutable.value.copy(busy = true, paused = false, partial = "", status = status) }
    @Synchronized fun beginResponse() { mutable.value = mutable.value.copy(partial = "") }
    @Synchronized fun delta(text: String) {
        if (mutable.value.busy) mutable.value = mutable.value.copy(partial = (mutable.value.partial + text).take(20000))
    }
    @Synchronized fun status(text: String) { mutable.value = mutable.value.copy(status = text.take(120)) }
    @Synchronized fun pause(value: Boolean) { mutable.value = mutable.value.copy(paused = value, status = if (value) "Paused" else "Working") }
    @Synchronized fun finish() { mutable.value = mutable.value.copy(busy = false, paused = false, partial = "", status = "Ready") }

    @Synchronized fun context(query: String = ""): String = JSONArray().apply {
        val messages = mutable.value.messages.filter { it.role != "notice" }
        val words = Regex("[a-z0-9]{4,}").findAll(query.lowercase()).map { it.value }.toSet()
        val relevant = messages.dropLast(6).filter { m -> words.any { m.text.contains(it, true) } }.takeLast(2)
        var budget = 4000
        val selected = (relevant + messages.takeLast(6)).distinct().asReversed().mapNotNull { m ->
            if (budget <= 0) null else {
                val text = m.text.take(minOf(budget, 1400))
                budget -= text.length
                JSONObject().put("role", m.role).put("text", text)
            }
        }.asReversed()
        selected.forEach { put(it) }
    }.toString()

    private fun append(message: ChatMessage) {
        // Full history is local; only a small relevant window is sent to the provider.
        runCatching { archive?.appendText(JSONObject().put("role", message.role).put("text", message.text)
            .put("time", System.currentTimeMillis()).toString() + "\n") }
        val entries = (mutable.value.messages + message).takeLast(40).toMutableList()
        while (entries.size > 1 && entries.sumOf { it.text.length } > 48000) entries.removeAt(0)
        mutable.value = mutable.value.copy(messages = entries)
        val json = JSONArray().apply { entries.forEach { put(JSONObject().put("role", it.role).put("text", it.text)) } }
        prefs?.edit()?.putString("messages", json.toString())?.apply()
    }
}
