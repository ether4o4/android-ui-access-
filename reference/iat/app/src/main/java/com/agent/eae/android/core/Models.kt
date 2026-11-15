package com.agent.eae.android.core

import org.json.JSONObject

/** A tool the model may be shown and may call. */
data class ToolSpec(
    val name: String,
    val description: String,
    val keywords: List<String>,
    val schemaJson: String,
    val category: String,
)

/** One decoded decision from the inference layer. */
data class AgentAction(
    val thought: String,
    val action: String,
    val args: JSONObject,
) {
    companion object {
        const val FINAL = "final_response"
        const val SEARCH_TOOLS = "search_tools"
    }
}

/** Result of running a tool. [full] is written to a log file; [summary] is what reaches the model. */
data class ToolResult(
    val summary: String,
    val full: String,
    val ok: Boolean,
    val logPath: String? = null,
)

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class Turn(val role: Role, val content: String, val step: Int = 0, val prunable: Boolean = false)

enum class LogKind { USER, THOUGHT, ACTION, TOOL, FINAL, SYSTEM, ERROR, STATE }

data class LogLine(val kind: LogKind, val text: String, val at: Long)

enum class RunState { IDLE, PLANNING, ACTING, OBSERVING, PRUNING, DONE, ERROR, PAUSED_RESOURCE }

/** Per-run accounting so the UI can show what the platform kept out of the prompt. */
data class TokenLedger(
    val promptCharsSent: Int = 0,
    val charsPruned: Int = 0,
    val charsOffloadedToFiles: Int = 0,
    val schemasWithheld: Int = 0,
) {
    /** Rough 4-chars-per-token estimate; labelled as an estimate everywhere it is shown. */
    fun estTokensSaved(): Int = (charsPruned + charsOffloadedToFiles) / 4
}

enum class EngineKind { ON_DEVICE_LITERT, REMOTE_OPENAI_COMPATIBLE }

data class EngineConfig(
    val kind: EngineKind = EngineKind.REMOTE_OPENAI_COMPATIBLE,
    /** On-device picker currently stores either .litertlm or GGUF content at this path. */
    val modelPath: String = "",
    val baseUrl: String = "http://10.0.2.2:11434/v1",
    val apiKey: String = "",
    val modelName: String = "qwen2.5:7b",
    val maxPromptChars: Int = 6000,
    val maxSteps: Int = 6,
    val sandboxTimeoutMs: Long = 10_000,
)

class AgentException(message: String) : Exception(message)
