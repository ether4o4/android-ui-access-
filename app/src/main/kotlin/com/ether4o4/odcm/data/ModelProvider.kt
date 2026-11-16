package com.ether4o4.odcm.data

/** Provider presets use complete request URLs. Secrets remain in SecretStore, one key per provider. */
enum class ModelProvider(
    val id: String,
    val label: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val keyName: String,
    val requiresKey: Boolean = true,
) {
    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com/v1/messages", "claude-opus-5-5", "anthropic_api_key"),
    OPENROUTER("openrouter", "OpenRouter Free", "https://openrouter.ai/api/v1/chat/completions", "openrouter/free", "openrouter_api_key"),
    GROQ("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-20b", "groq_api_key"),
    GEMINI("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-4-hydrogen", "gemini_api_key"),
    HUGGING_FACE("hugging_face", "Hugging Face (credits required)", "https://router.huggingface.co/v1/chat/completions", "openai/gpt-oss-120b", "hugging_face_api_key"),
    // Phi-4 Mini supports tool calls, but device-agent reliability must be tested on the phone.
    OLLAMA("ollama", "Ollama (local)", "http://127.0.0.1:11434/v1/chat/completions", "phi4-mini", "ollama_api_key", false),
    CUSTOM("custom", "OpenAI-compatible", "", "", "custom_api_key", false);

    companion object {
        fun fromId(id: String?): ModelProvider = entries.firstOrNull { it.id == id } ?: ANTHROPIC
    }
}
