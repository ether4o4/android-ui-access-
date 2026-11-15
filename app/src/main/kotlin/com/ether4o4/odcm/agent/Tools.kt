package com.ether4o4.odcm.agent

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool

/** The fixed tool menu offered to the model. Kept stable and sorted so the prompt cache holds. */
object Tools {

    private fun obj(vararg props: Pair<String, Map<String, Any>>, required: List<String>): Tool.InputSchema {
        val builder = Tool.InputSchema.Properties.builder()
        for ((name, schema) in props) builder.putAdditionalProperty(name, JsonValue.from(schema))
        return Tool.InputSchema.builder().properties(builder.build()).required(required).build()
    }

    private fun str(desc: String) = mapOf("type" to "string", "description" to desc)
    private fun int(desc: String) = mapOf("type" to "integer", "description" to desc)

    val all: List<Tool> = listOf(
        tool("tap", "Tap the element with this index.", obj("index" to int("element index"), required = listOf("index"))),
        tool(
            "type_text", "Type text into the field with this index.",
            obj("index" to int("field index"), "text" to str("text to type"), required = listOf("index", "text")),
        ),
        tool("swipe", "Scroll the screen up, down, left or right.", obj("direction" to str("up, down, left or right"), required = listOf("direction"))),
        tool("back", "Press the system Back button.", empty()),
        tool("home", "Go to the home screen.", empty()),
        tool("open_app", "Open an app by name.", obj("name" to str("app name, e.g. Gmail"), required = listOf("name"))),
        tool("wait", "Wait briefly for the screen to settle.", obj("ms" to int("milliseconds, up to 5000"), required = listOf("ms"))),
        tool("reply", "Send a message back to the person who gave the task.", obj("text" to str("the reply"), required = listOf("text"))),
        tool("done", "The task is finished.", obj("summary" to str("one-line result"), required = listOf("summary"))),
    )

    private fun empty() = Tool.InputSchema.builder().properties(Tool.InputSchema.Properties.builder().build()).build()

    private fun tool(name: String, desc: String, schema: Tool.InputSchema) =
        Tool.builder().name(name).description(desc).inputSchema(schema).build()
}
