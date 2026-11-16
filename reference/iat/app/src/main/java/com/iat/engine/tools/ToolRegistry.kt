package com.iat.engine.tools

import android.util.Log

typealias ToolExecutor = (Map<String, Any>) -> String

class ToolRegistry {
    private val tools = mutableMapOf<String, ToolExecutor>()
    private val schemas = mutableMapOf<String, String>()

    fun registerTool(name: String, schemaJson: String, executor: ToolExecutor) {
        tools[name] = executor
        schemas[name] = schemaJson
        Log.i("IAT-Tools", "Registered Native/MCP Tool: $name")
    }

    fun executeTool(name: String, args: Map<String, Any>): String {
        val executor = tools[name] ?: return "Error: Tool '$name' not found in registry."
        return try {
            executor(args)
        } catch (e: Exception) {
            "Tool Execution Error [$name]: ${e.localizedMessage}"
        }
    }

    fun getRegisteredSchemas(): Map<String, String> = schemas
}
