package com.ether4o4.odcm.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The fixed tool menu sent to the model, as a JSON array built once.
 * Kept stable and in a fixed order so the cached prompt prefix stays valid.
 */
object Tools {

    val json: JSONArray by lazy {
        JSONArray().apply {
            put(tool("tap", "Tap the element with this index.", obj("index" to int("element index")), listOf("index")))
            put(tool("type_text", "Type text into the field with this index.",
                obj("index" to int("field index"), "text" to str("text to type")), listOf("index", "text")))
            put(tool("swipe", "Scroll the screen up, down, left or right.",
                obj("direction" to str("up, down, left or right")), listOf("direction")))
            put(tool("back", "Press the system Back button.", JSONObject(), emptyList()))
            put(tool("home", "Go to the home screen.", JSONObject(), emptyList()))
            put(tool("open_app", "Open an installed app by exact package or unambiguous name.",
                obj("name" to str("installed package from app context, or app name, e.g. Gmail")), listOf("name")))
            put(tool("wait", "Wait briefly for the screen to settle.", obj("ms" to int("milliseconds, up to 5000")), listOf("ms")))
            put(tool("reply", "Send a message back to the person who gave the task.", obj("text" to str("the reply")), listOf("text")))
            put(tool("done", "The task is finished.", obj("summary" to str("one-line result")), listOf("summary")))
        }
    }

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", props)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", desc).put("input_schema", schema)
    }

    private fun obj(vararg props: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply { for ((k, v) in props) put(k, v) }

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
}
