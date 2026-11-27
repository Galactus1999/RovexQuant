from dataclasses import dataclass

@dataclass(frozen=True, slots=True)
class ProviderConfig:
    provider_id: str
    base_url: str
    api_key_env: str
    api_keys_json_env: str

# These providers expose OpenAI-compatible chat-completions interfaces.
# The gateway discovers model IDs from live provider catalogues; callers need not pin them.
SUPPORTED = {
    "deepseek": ProviderConfig(
        "deepseek", "https://api.deepseek.com", "DEEPSEEK_API_KEY", "DEEPSEEK_API_KEYS_JSON"
    ),
    "groq": ProviderConfig(
        "groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY", "GROQ_API_KEYS_JSON"
    ),
    "openrouter": ProviderConfig(
        "openrouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", "OPENROUTER_API_KEYS_JSON"
    ),
    "gemini": ProviderConfig(
        "gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
        "GEMINI_API_KEY", "GEMINI_API_KEYS_JSON"
    ),
    "openai": ProviderConfig(
        "openai", "https://api.openai.com/v1", "OPENAI_API_KEY", "OPENAI_API_KEYS_JSON"
    ),
}
