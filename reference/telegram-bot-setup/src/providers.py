from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ProviderField:
    key: str
    label: str
    required: bool = True
    default: str = ""
    secret: bool = True
    kind: str = "text"


@dataclass(frozen=True)
class ProviderSpec:
    slug: str
    name: str
    fields: tuple[ProviderField, ...]
    default_model: str = ""


def api_key(key: str, label: str = "API key") -> tuple[ProviderField, ...]:
    return (ProviderField(key, label),)


PROVIDERS: tuple[ProviderSpec, ...] = (
    ProviderSpec("openai", "OpenAI", api_key("OPENAI_API_KEY"), "gpt-4.1-mini"),
    ProviderSpec("anthropic", "Anthropic / Claude", api_key("ANTHROPIC_API_KEY"), "claude-sonnet-4-5"),
    ProviderSpec("xai", "xAI / Grok", api_key("XAI_API_KEY"), "grok-4-fast"),
    ProviderSpec("gemini", "Google Gemini", api_key("GEMINI_API_KEY"), "gemini-2.5-flash"),
    ProviderSpec("skywork", "Skywork", api_key("SKYWORK_API_KEY")),
    ProviderSpec("openrouter", "OpenRouter", api_key("OPENROUTER_API_KEY")),
    ProviderSpec("groq", "Groq", api_key("GROQ_API_KEY"), "llama-3.3-70b-versatile"),
    ProviderSpec("deepseek", "DeepSeek", api_key("DEEPSEEK_API_KEY"), "deepseek-chat"),
    ProviderSpec("mistral", "Mistral", api_key("MISTRAL_API_KEY"), "mistral-small-latest"),
    ProviderSpec("together", "Together AI", api_key("TOGETHER_API_KEY")),
    ProviderSpec("fireworks", "Fireworks AI", api_key("FIREWORKS_API_KEY")),
    ProviderSpec("cohere", "Cohere", api_key("COHERE_API_KEY")),
    ProviderSpec("perplexity", "Perplexity", api_key("PERPLEXITY_API_KEY")),
    ProviderSpec("huggingface", "Hugging Face", api_key("HUGGINGFACE_API_KEY", "Access token")),
    ProviderSpec("replicate", "Replicate", api_key("REPLICATE_API_TOKEN", "API token")),
    ProviderSpec("stability", "Stability AI", api_key("STABILITY_API_KEY")),
    ProviderSpec("elevenlabs", "ElevenLabs", api_key("ELEVENLABS_API_KEY")),
    ProviderSpec("runway", "Runway", api_key("RUNWAY_API_KEY")),
    ProviderSpec("fal", "Fal.ai", api_key("FAL_API_KEY")),
    ProviderSpec(
        "anythingllm",
        "AnythingLLM",
        (
            ProviderField("ANYTHINGLLM_API_KEY", "API key"),
            ProviderField("ANYTHINGLLM_URL", "Server URL", default="http://127.0.0.1:3001", secret=False, kind="url"),
        ),
    ),
    ProviderSpec("azure-openai", "Azure OpenAI", (
        ProviderField("AZURE_OPENAI_API_KEY", "API key"),
        ProviderField("AZURE_OPENAI_ENDPOINT", "Endpoint URL", secret=False, kind="url"),
    )),
    ProviderSpec("ollama", "Ollama", (
        ProviderField("OLLAMA_URL", "Server URL", default="http://127.0.0.1:11434", secret=False, kind="url"),
    )),
    ProviderSpec("lmstudio", "LM Studio", (
        ProviderField("LMSTUDIO_URL", "Server URL", default="http://127.0.0.1:1234", secret=False, kind="url"),
    )),
    ProviderSpec("localai", "LocalAI", (
        ProviderField("LOCALAI_URL", "Server URL", default="http://127.0.0.1:8080", secret=False, kind="url"),
        ProviderField("LOCALAI_API_KEY", "API key (optional)", required=False),
    )),
)

PROVIDER_BY_SLUG = {provider.slug: provider for provider in PROVIDERS}
PROVIDER_BY_FIELD = {
    field.key: provider
    for provider in PROVIDERS
    for field in provider.fields
}

# Backwards-compatible shape used by the bot's /providers command.
API_PROVIDERS = [
    (field.key, provider.name)
    for provider in PROVIDERS
    for field in provider.fields
    if field.secret
]
