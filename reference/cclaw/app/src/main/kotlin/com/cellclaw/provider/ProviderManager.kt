package com.cellclaw.provider

import com.cellclaw.config.AppConfig
import com.cellclaw.config.SecureKeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages available AI providers and handles provider switching.
 * Reads the active provider from AppConfig and configures it with
 * the encrypted API key from SecureKeyStore.
 */
@Singleton
class ProviderManager @Inject constructor(
    private val appConfig: AppConfig,
    private val secureKeyStore: SecureKeyStore,
    private val anthropicProvider: AnthropicProvider,
    private val openAIProvider: OpenAIProvider,
    private val geminiProvider: GeminiProvider,
    private val openRouterProvider: OpenRouterProvider,
    private val localProvider: LocalProvider
) {
    private val providers = mapOf(
        "anthropic" to anthropicProvider,
        "openai" to openAIProvider,
        "gemini" to geminiProvider,
        "openrouter" to openRouterProvider,
        "local" to localProvider
    )

    /** Get the currently active provider, configured with its API key */
    fun activeProvider(): Provider {
        val type = appConfig.providerType
        val provider = providers[type] ?: anthropicProvider
        configureProvider(type, provider)
        return provider
    }

    /** Switch to a different provider */
    fun switchProvider(type: String) {
        if (type in providers) {
            val changed = appConfig.providerType != type
            appConfig.providerType = type
            // Reset the model to the new provider's default so a name from the
            // previous provider (e.g. "claude-sonnet-4-6") isn't sent to a
            // server that doesn't know it — a common cause of 404s with local
            // servers like Ollama that validate the model name.
            if (changed) {
                val default = availableProviders().find { it.type == type }?.defaultModel
                if (!default.isNullOrEmpty()) appConfig.model = default
            }
        }
    }

    /** Get the active provider type name */
    fun activeType(): String = appConfig.providerType

    /** Get all available provider types */
    fun availableProviders(): List<ProviderInfo> = listOf(
        ProviderInfo("anthropic", "Anthropic (Claude)", "claude-sonnet-4-6",
            secureKeyStore.hasApiKey("anthropic"),
            models = listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5")),
        ProviderInfo("openai", "OpenAI (GPT)", "gpt-5.2",
            secureKeyStore.hasApiKey("openai"),
            models = listOf("gpt-5.2", "gpt-5.2-chat-latest", "gpt-5-mini", "gpt-4.1", "gpt-4.1-mini")),
        ProviderInfo("gemini", "Google (Gemini)", "gemini-3-flash-preview",
            secureKeyStore.hasApiKey("gemini"),
            models = listOf("gemini-3.1-pro-preview", "gemini-3-flash-preview", "gemini-2.5-flash", "gemini-2.5-pro")),
        ProviderInfo("openrouter", "OpenRouter", "google/gemini-2.5-flash",
            secureKeyStore.hasApiKey("openrouter"),
            models = listOf("google/gemini-2.5-flash", "google/gemini-2.5-pro", "anthropic/claude-sonnet-4.6", "openai/gpt-5.2")),
        // Local GGUF models need no API key — hasKey is always true so the
        // provider can be activated as soon as a server is reachable.
        ProviderInfo("local", "Local (Termux · llama.cpp)", LocalProvider.DEFAULT_MODEL,
            hasKey = true)
    )

    /** Check if a provider has an API key configured */
    fun hasKey(type: String): Boolean = secureKeyStore.hasApiKey(type)

    /** Store an API key for a provider */
    fun setApiKey(type: String, apiKey: String) {
        secureKeyStore.storeApiKey(type, apiKey)
    }

    /** Remove an API key for a provider */
    fun removeApiKey(type: String) {
        secureKeyStore.deleteApiKey(type)
    }

    private fun configureProvider(type: String, provider: Provider) {
        val model = appConfig.model
        if (provider is LocalProvider) {
            // No API key required; an optional one is passed through if stored.
            provider.configure(appConfig.localEndpoint, model, secureKeyStore.getApiKey(type) ?: "")
            return
        }
        val apiKey = secureKeyStore.getApiKey(type) ?: return
        when (provider) {
            is AnthropicProvider -> provider.configure(apiKey, model)
            is OpenAIProvider -> provider.configure(apiKey, model)
            is GeminiProvider -> provider.configure(apiKey, model)
            is OpenRouterProvider -> provider.configure(apiKey, model)
        }
    }
}

data class ProviderInfo(
    val type: String,
    val displayName: String,
    val defaultModel: String,
    val hasKey: Boolean,
    val models: List<String> = emptyList()
)
