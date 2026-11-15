package com.agent.eae.android.core.inference

import com.agent.eae.android.core.AgentException
import com.agent.eae.android.core.EngineConfig
import com.agent.eae.android.core.Role
import com.agent.eae.android.core.Turn

/** On-device GGUF inference through the bundled llama.cpp JNI backend. */
class GgufEngine(cfg: EngineConfig) : InferenceEngine {
    override val label: String = "GGUF / llama.cpp"

    private var handle: Long = 0L

    init {
        handle = GgufNative.create(
            cfg.modelPath,
            4096,
            Runtime.getRuntime().availableProcessors().coerceAtLeast(2),
        )
        if (handle == 0L) {
            throw AgentException("GGUF model could not be loaded. Check that the selected file is a valid GGUF model.")
        }
    }

    override suspend fun generate(systemPrompt: String, transcript: List<Turn>): String {
        val prompt = buildString {
            if (systemPrompt.isNotBlank()) append("SYSTEM:\n").append(systemPrompt).append("\n\n")
            transcript.forEach { turn ->
                val role = when (turn.role) {
                    Role.SYSTEM -> "SYSTEM"
                    Role.USER -> "USER"
                    Role.ASSISTANT -> "ASSISTANT"
                    Role.TOOL -> "TOOL"
                }
                append(role).append(":\n").append(turn.content).append("\n\n")
            }
            append("ASSISTANT:\n")
        }
        return GgufNative.generate(handle, prompt, 512)
            ?: throw AgentException("GGUF inference failed.")
    }

    override fun close() {
        if (handle != 0L) {
            GgufNative.close(handle)
            handle = 0L
        }
    }
}

private object GgufNative {
    init {
        System.loadLibrary("iat_gguf")
    }

    external fun create(path: String, contextSize: Int, threads: Int): Long
    external fun generate(handle: Long, prompt: String, maxTokens: Int): String?
    external fun close(handle: Long)
}
