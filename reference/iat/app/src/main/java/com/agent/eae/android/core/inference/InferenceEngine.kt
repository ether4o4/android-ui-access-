package com.agent.eae.android.core.inference

import android.content.Context
import com.agent.eae.android.core.AgentException
import com.agent.eae.android.core.EngineConfig
import com.agent.eae.android.core.EngineKind
import com.agent.eae.android.core.Turn
import java.io.File

interface InferenceEngine {
    val label: String

    suspend fun generate(systemPrompt: String, transcript: List<Turn>): String

    fun close()
}

object EngineFactory {
    fun create(context: Context, cfg: EngineConfig): InferenceEngine =
        when (cfg.kind) {
            EngineKind.REMOTE_OPENAI_COMPATIBLE -> {
                if (cfg.baseUrl.isBlank()) {
                    throw AgentException(
                        "No server address set. Enter the base URL of an OpenAI-compatible endpoint " +
                            "in Settings, for example http://10.0.2.2:11434/v1",
                    )
                }
                RemoteEngine(cfg)
            }

            EngineKind.ON_DEVICE_LITERT -> {
                val path = cfg.modelPath.trim()
                if (path.isEmpty()) {
                    throw AgentException(
                        "No on-device model selected. Choose a .litertlm or .gguf model file in Settings, " +
                            "or switch to a remote endpoint.",
                    )
                }
                val file = File(path)
                if (!file.isFile) {
                    throw AgentException(
                        "On-device model not found at $path. Copy the model onto this device and select " +
                            "it again, or switch to a remote endpoint.",
                    )
                }

                if (looksLikeGguf(file)) {
                    GgufEngine(cfg.copy(modelPath = path))
                } else {
                    LiteRtEngine(context, cfg.copy(modelPath = path))
                }
            }
        }

    /** GGUF has a four-byte ASCII magic header, so the picker does not need separate storage paths. */
    private fun looksLikeGguf(file: File): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            }
        }.getOrDefault(false)
    }
}
