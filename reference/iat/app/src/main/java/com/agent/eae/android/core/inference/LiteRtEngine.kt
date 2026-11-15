package com.agent.eae.android.core.inference

import android.content.Context
import com.agent.eae.android.core.AgentException
import com.agent.eae.android.core.EngineConfig
import com.agent.eae.android.core.Role
import com.agent.eae.android.core.Turn
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Layer 1, on-device. Runs `.litertlm` bundles (Gemma 3n/4 E2B etc.) through Google's LiteRT-LM
 * runtime — the successor to MediaPipe LLM Inference, which is maintenance-only and cannot read
 * the `.litertlm` format.
 *
 * LiteRT-LM has a Kotlin tool-calling API of its own, but this engine deliberately does not use
 * it: the platform's tool registry lives outside the model runtime (Layer 3), so decisions stay
 * strict-JSON via prompt + parse-with-repair like every other engine. There is no
 * grammar-constrained decoding hook here either.
 */
class LiteRtEngine(context: Context, private val cfg: EngineConfig) : InferenceEngine {
    private val appContext = context.applicationContext
    private val lock = Mutex()

    @Volatile
    private var engine: Engine? = null
    private var usedBackend: String = "gpu"

    override val label: String
        get() = "on-device litert-lm ${File(cfg.modelPath).name} ($usedBackend)"

    override suspend fun generate(systemPrompt: String, transcript: List<Turn>): String =
        withContext(Dispatchers.IO) {
            val runtime = obtainEngine()
            try {
                val history = transcript.dropLast(1).map { turn ->
                    when (turn.role) {
                        Role.ASSISTANT -> Message.model(turn.content)
                        Role.TOOL -> Message.user("TOOL RESULT: " + turn.content)
                        else -> Message.user(turn.content)
                    }
                }
                val latest = transcript.lastOrNull()
                    ?: throw AgentException("Nothing to send to the model.")
                val latestText = when (latest.role) {
                    Role.TOOL -> "TOOL RESULT: " + latest.content
                    else -> latest.content
                }

                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    initialMessages = history,
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2),
                )
                runtime.createConversation(conversationConfig).use { conversation ->
                    conversation.sendMessage(latestText).toString()
                }
            } catch (e: AgentException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw AgentException(
                    "Out of memory while generating. This model may be too large for this device; " +
                        "try a smaller .litertlm model.",
                )
            } catch (e: Exception) {
                throw AgentException(
                    "On-device generation failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }

    private suspend fun obtainEngine(): Engine {
        engine?.let { return it }
        return lock.withLock {
            engine?.let { return it }
            createEngine().also { engine = it }
        }
    }

    /** GPU first (needs the manifest uses-native-library entries), CPU as the fallback. */
    private suspend fun createEngine(): Engine {
        val model = File(cfg.modelPath)
        if (!model.isFile) {
            throw AgentException("Model file not found: ${cfg.modelPath}")
        }
        val cache = appContext.cacheDir.absolutePath
        val attempts = listOf<Pair<String, () -> Engine>>(
            "gpu" to {
                Engine(
                    com.google.ai.edge.litertlm.EngineConfig(
                        modelPath = model.absolutePath,
                        backend = Backend.GPU(),
                        cacheDir = cache,
                    ),
                )
            },
            "cpu" to {
                Engine(
                    com.google.ai.edge.litertlm.EngineConfig(
                        modelPath = model.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = cache,
                    ),
                )
            },
        )
        var lastError: Exception? = null
        for ((name, make) in attempts) {
            try {
                val candidate = make()
                candidate.initialize()
                usedBackend = name
                return candidate
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw AgentException(
            "Could not initialise LiteRT-LM with ${model.name} on GPU or CPU: " +
                "${lastError?.message ?: "unknown error"}. Check the file is a valid .litertlm bundle.",
        )
    }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}
