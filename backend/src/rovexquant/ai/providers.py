from dataclasses import dataclass

@dataclass(frozen=True, slots=True)
class ProviderConfig:
    provider_id: str
    base_url: str
    model: str
    api_key_env: str

# Metadata only. No provider call until current official API contracts are verified.
SUPPORTED = {
    "deepseek": ProviderConfig("deepseek", "https://api.deepseek.com", "", "DEEPSEEK_API_KEY"),
    "groq": ProviderConfig("groq", "https://api.groq.com/openai/v1", "", "GROQ_API_KEY"),
    "openrouter": ProviderConfig("openrouter", "https://openrouter.ai/api/v1", "", "OPENROUTER_API_KEY"),
}
