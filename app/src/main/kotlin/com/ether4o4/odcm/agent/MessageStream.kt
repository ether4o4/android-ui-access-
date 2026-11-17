package com.ether4o4.odcm.agent

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** SSE assembly only. No tool is executable until a complete response passes ModelClient validation. */
internal class MessageStream(private val anthropic: Boolean, private val onText: (String) -> Unit) {
    private val blocks = sortedMapOf<Int, JSONObject>()
    private val arguments = mutableMapOf<Int, StringBuilder>()
    private val closed = mutableSetOf<Int>()
    private val calls = sortedMapOf<Int, JSONObject>()
    private val reasoning = sortedMapOf<Int, JSONObject>()
    private val text = StringBuilder()
    private var stopReason: String? = null
    private var started = false
    var complete = false
        private set
    private var size = 0
    private val usage = JSONObject()
    private val reasoningContent = StringBuilder()

    fun accept(data: String) {
        size += data.length
        requireValid(size <= 2_000_000 && !complete)
        if (!anthropic && data == "[DONE]") {
            requireValid(stopReason != null)
            complete = true
            return
        }
        try {
            val event = JSONObject(data)
            if (event.has("error") || event.optString("type") == "error") fail()
            if (anthropic) acceptAnthropic(event) else acceptCompatible(event)
        } catch (e: ModelException) { throw e }
        catch (_: Exception) { fail() }
    }

    private fun acceptAnthropic(e: JSONObject) {
        when (e.optString("type")) {
            "ping" -> Unit
            "message_start" -> {
                requireValid(!started); started = true
                e.optJSONObject("message")?.optJSONObject("usage")?.let { u -> for (k in u.keys()) usage.put(k, u.get(k)) }
            }
            "content_block_start" -> {
                requireValid(started)
                val i = e.getInt("index")
                requireValid(i in 0..32 && !blocks.containsKey(i))
                val block = e.getJSONObject("content_block")
                blocks[i] = block
                if (block.optString("type") == "text") {
                    val initial = block.optString("text")
                    if (initial.isNotEmpty()) onText(initial)
                }
            }
            "content_block_delta" -> {
                val i = e.getInt("index")
                requireValid(i !in closed)
                val block = blocks[i] ?: fail()
                val delta = e.getJSONObject("delta")
                when (delta.optString("type")) {
                    "text_delta" -> {
                        requireValid(block.optString("type") == "text")
                        val part = delta.getString("text")
                        block.put("text", block.optString("text") + part)
                        if (part.isNotEmpty()) onText(part)
                    }
                    "input_json_delta" -> {
                        requireValid(block.optString("type") == "tool_use")
                        arguments.getOrPut(i) { StringBuilder() }.append(delta.getString("partial_json"))
                    }
                    "thinking_delta" -> block.put("thinking", block.optString("thinking") + delta.optString("thinking"))
                    "signature_delta" -> block.put("signature", block.optString("signature") + delta.optString("signature"))
                }
            }
            "content_block_stop" -> {
                val i = e.getInt("index")
                requireValid(blocks.containsKey(i) && closed.add(i))
                arguments[i]?.let {
                    val parser = JSONTokener(it.toString())
                    val value = parser.nextValue()
                    requireValid(value is JSONObject && parser.nextClean() == '\u0000')
                    blocks.getValue(i).put("input", value)
                }
            }
            "message_delta" -> {
                stopReason = e.getJSONObject("delta").opt("stop_reason") as? String ?: stopReason
                e.optJSONObject("usage")?.let { u -> for (k in u.keys()) usage.put(k, u.get(k)) }
            }
            "message_stop" -> {
                requireValid(started && stopReason != null && blocks.keys.all { it in closed })
                complete = true
            }
        }
    }

    private fun acceptCompatible(e: JSONObject) {
        e.optJSONObject("usage")?.let { u -> for (k in u.keys()) usage.put(k, u.get(k)) }
        val choices = e.getJSONArray("choices")
        if (choices.length() == 0) return // Usage-only final chunk.
        requireValid(choices.length() == 1)
        val choice = choices.getJSONObject(0)
        requireValid(choice.getInt("index") == 0)
        val delta = choice.getJSONObject("delta")
        requireValid(!delta.has("function_call"))
        if (delta.has("role")) requireValid(delta.getString("role") == "assistant")
        val part = delta.opt("content")
        if (part != null && part != JSONObject.NULL) {
            requireValid(part is String)
            text.append(part as String)
            if (part.isNotEmpty()) onText(part)
        }
        delta.optJSONArray("reasoning_details")?.let { details ->
            for (n in 0 until details.length()) {
                val update = details.getJSONObject(n)
                val index = update.optInt("index", n)
                requireValid(index in 0..128)
                val saved = reasoning.getOrPut(index) { JSONObject() }
                for (key in update.keys()) {
                    val value = update.get(key)
                    if (key in setOf("text", "data", "signature") && value is String) saved.put(key, saved.optString(key) + value)
                    else saved.put(key, value)
                }
            }
        }
        delta.opt("reasoning_content")?.let { if (it is String) reasoningContent.append(it) }
        val updates = delta.optJSONArray("tool_calls") ?: JSONArray()
        for (n in 0 until updates.length()) {
            val update = updates.getJSONObject(n)
            val i = update.getInt("index")
            requireValid(i == 0) // The agent accepts one action per decision.
            val call = calls.getOrPut(i) { JSONObject().put("function", JSONObject().put("name", "").put("arguments", "")) }
            for (key in listOf("id", "type")) if (update.has(key)) {
                val value = update.getString(key)
                requireValid(!call.has(key) || call.getString(key) == value)
                call.put(key, value)
            }
            update.optJSONObject("function")?.let { f ->
                val target = call.getJSONObject("function")
                for (key in listOf("name", "arguments")) if (f.has(key)) target.put(key, target.getString(key) + f.getString(key))
            }
        }
        val reason = choice.opt("finish_reason")
        if (reason is String) stopReason = reason
    }

    fun response(): JSONObject {
        requireValid(complete)
        if (stopReason in setOf("length", "max_tokens", "content_filter")) {
            throw ModelException("The model response was cut short; no action was executed. Please retry or shorten the request.")
        }
        if (anthropic) return JSONObject().put("content", JSONArray(blocks.values.toList())).put("stop_reason", stopReason).apply { if (usage.length() > 0) put("usage", usage) }
        val message = JSONObject().put("role", "assistant").put("content", text.toString())
        if (reasoningContent.isNotEmpty()) message.put("reasoning_content", reasoningContent.toString())
        if (calls.isNotEmpty()) message.put("tool_calls", JSONArray(calls.values.toList()))
        if (reasoning.isNotEmpty()) message.put("reasoning_details", JSONArray(reasoning.values.toList()))
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", stopReason))).apply { if (usage.length() > 0) put("usage", usage) }
    }

    private fun requireValid(condition: Boolean) { if (!condition) fail() }
    private fun fail(): Nothing = throw ModelException("The response stream was incomplete or invalid; no action was executed.")
}
