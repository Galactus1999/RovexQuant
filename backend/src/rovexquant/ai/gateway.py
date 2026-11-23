"""Server-side, provider-neutral AI gateway.

Credentials are loaded from backend environment configuration or the encrypted
owner-only provider vault. The gateway never returns credential material, logs
prompts, or rotates keys to evade provider limits.
"""
from __future__ import annotations

import hmac
import json
import os
from dataclasses import dataclass
from typing import Any

import litellm

from rovexquant.ai.providers import SUPPORTED

# Use the imported LiteLLM provider adapter without enabling prompt logging.
litellm.suppress_debug_info = True
litellm.turn_off_message_logging = True

TASK_TYPES = frozenset({"chat", "research", "macro_analysis", "filing_extraction", "claim_verification", "summarization"})


def configured_task_routes() -> dict[str, dict[str, Any]]:
    """Load server-owned task routes; never expose API credentials."""
    raw = os.getenv("QUANT_AI_TASK_ROUTES", "").strip()
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as error:
        raise GatewayError("Invalid QUANT_AI_TASK_ROUTES configuration", 503) from error
    if not isinstance(parsed, dict):
        raise GatewayError("QUANT_AI_TASK_ROUTES must be a JSON object", 503)
    routes: dict[str, dict[str, Any]] = {}
    for task, route in parsed.items():
        if task not in TASK_TYPES or not isinstance(route, dict):
            raise GatewayError("AI task routing contains an unsupported task or route", 503)
        provider, model = route.get("provider_id"), route.get("model")
        fallbacks = route.get("fallback_providers", [])
        if provider not in SUPPORTED or not isinstance(model, str) or not model.strip() or len(model) > 160:
            raise GatewayError(f"Invalid provider/model in AI task route: {task}", 503)
        if not isinstance(fallbacks, list) or len(fallbacks) > 3 or any(item not in SUPPORTED for item in fallbacks):
            raise GatewayError(f"Invalid fallback providers in AI task route: {task}", 503)
        routes[task] = {"provider_id": provider, "model": model.strip(), "fallback_providers": list(dict.fromkeys(fallbacks))}
    return routes


def resolve_task_route(task_type: str, provider_id: str | None, model: str | None, fallback_providers: list[str] | None):
    if task_type not in TASK_TYPES:
        raise GatewayError("Unsupported AI task type", 422)
    if provider_id is not None or model is not None:
        if provider_id not in SUPPORTED or not isinstance(model, str) or not model.strip():
            raise GatewayError("A supported provider and model must be selected together", 422)
        return provider_id, model.strip(), fallback_providers
    route = configured_task_routes().get(task_type)
    if route is None:
        raise GatewayError(f"No model route is configured for task: {task_type}", 503)
    return route["provider_id"], route["model"], route["fallback_providers"]


@dataclass(frozen=True, slots=True)
class Credential:
    provider_id: str
    credential_id: str
    api_key: str


class GatewayError(Exception):
    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message)
        self.status_code = status_code


def _credentials_for(provider_id: str) -> list[Credential]:
    config = SUPPORTED[provider_id]
    result: list[Credential] = []
    # Owner-managed mobile credentials are encrypted in PostgreSQL/SQLite and
    # remain server-side. They take precedence over environment defaults.
    from rovexquant.market_data.upstox_credentials import get_ai_provider_api_key
    stored_key = get_ai_provider_api_key(provider_id)
    if stored_key:
        result.append(Credential(provider_id, "app", stored_key))
    raw_map = os.getenv(config.api_keys_json_env, "").strip()
    if raw_map:
        try:
            parsed = json.loads(raw_map)
        except json.JSONDecodeError as error:
            raise GatewayError(f"Invalid credential configuration for {provider_id}", 503) from error
        if not isinstance(parsed, dict):
            raise GatewayError(f"Invalid credential configuration for {provider_id}", 503)
        for credential_id, api_key in parsed.items():
            if isinstance(credential_id, str) and credential_id and isinstance(api_key, str) and api_key.strip():
                result.append(Credential(provider_id, credential_id, api_key.strip()))
    primary = os.getenv(config.api_key_env, "").strip()
    if primary and not any(item.credential_id == "default" for item in result):
        result.insert(0, Credential(provider_id, "default", primary))
    return result


def configured_providers() -> list[dict[str, Any]]:
    """Return safe provider metadata; credential values are never exposed."""
    result = []
    for provider_id, config in SUPPORTED.items():
        credentials = _credentials_for(provider_id)
        result.append({
            "provider_id": provider_id,
            "configured": bool(credentials),
            "credential_ids": [item.credential_id for item in credentials],
            "default_model": os.getenv(f"{provider_id.upper()}_DEFAULT_MODEL", "").strip() or None,
            "fallback_model_configured": bool(os.getenv(f"{provider_id.upper()}_FALLBACK_MODEL", "").strip()),
            "api_base": config.base_url,
        })
    return result


def _select_credential(provider_id: str, requested_id: str | None) -> Credential:
    credentials = _credentials_for(provider_id)
    if requested_id is not None:
        for credential in credentials:
            if credential.credential_id == requested_id:
                return credential
        raise GatewayError(f"Credential '{requested_id}' is not configured for {provider_id}", 400)
    if not credentials:
        raise GatewayError(f"No server credential configured for {provider_id}", 503)
    # Stable selection is intentional. The owner-managed encrypted app key is
    # preferred when present; otherwise use the configured default/first key.
    # Credentials are never round-robin rotated when a provider returns 429.
    return (
        next((item for item in credentials if item.credential_id == "app"), None)
        or next((item for item in credentials if item.credential_id == "default"), credentials[0])
    )


def _provider_headers(provider_id: str, api_key: str) -> dict[str, str]:
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    if provider_id == "openrouter":
        app_url = os.getenv("OPENROUTER_HTTP_REFERER", "").strip()
        app_title = os.getenv("OPENROUTER_APP_TITLE", "Quant").strip()
        if app_url:
            headers["HTTP-Referer"] = app_url
        if app_title:
            headers["X-Title"] = app_title
    return headers


async def complete_chat(
    *, provider_id: str | None, model: str | None, messages: list[dict[str, str]],
    max_tokens: int = 1200, temperature: float = 0.2, credential_id: str | None = None,
    fallback_providers: list[str] | None = None, task_type: str = "chat",
) -> dict[str, Any]:
    provider_id, model, fallback_providers = resolve_task_route(task_type, provider_id, model, fallback_providers)
    if not messages or len(messages) > 32:
        raise GatewayError("Provide between 1 and 32 chat messages", 422)
    allowed_roles = {"system", "user", "assistant"}
    if any(item.get("role") not in allowed_roles or not isinstance(item.get("content"), str) for item in messages):
        raise GatewayError("Messages must contain supported roles and text content", 422)
    if sum(len(item["content"]) for item in messages) > 24000:
        raise GatewayError("Combined message content exceeds the 24,000 character limit", 413)
    if max_tokens < 1 or max_tokens > 4096 or temperature < 0 or temperature > 2:
        raise GatewayError("Invalid generation limits", 422)
    candidates = [provider_id]
    for candidate in fallback_providers or []:
        if candidate not in SUPPORTED:
            raise GatewayError(f"Unsupported fallback provider: {candidate}", 400)
        if candidate not in candidates:
            candidates.append(candidate)
    if len(candidates) > 4:
        raise GatewayError("At most three fallback providers are allowed", 422)
    last_error: GatewayError | None = None
    for index, candidate in enumerate(candidates):
        try:
            credential = _select_credential(candidate, credential_id if index == 0 else None)
            config = SUPPORTED[candidate]
            selected_model = model if index == 0 else os.getenv(f"{candidate.upper()}_FALLBACK_MODEL", "").strip()
            if not selected_model:
                last_error = GatewayError(f"No fallback model configured for {candidate}", 503)
                continue
            extra_headers = None
            if candidate == "openrouter":
                extra_headers = {}
                if os.getenv("OPENROUTER_HTTP_REFERER", "").strip():
                    extra_headers["HTTP-Referer"] = os.getenv("OPENROUTER_HTTP_REFERER", "").strip()
                extra_headers["X-Title"] = os.getenv("OPENROUTER_APP_TITLE", "Quant").strip()
            litellm_model = f"{candidate}/{selected_model}"
            response = await litellm.acompletion(
                model=litellm_model, api_base=config.base_url, api_key=credential.api_key,
                messages=messages, max_tokens=max_tokens, temperature=temperature,
                timeout=25, extra_headers=extra_headers,
            )
            choices = getattr(response, "choices", None)
            message = getattr(choices[0], "message", None) if choices else None
            content = getattr(message, "content", None)
            if not isinstance(content, str):
                raise GatewayError(f"{candidate} returned no text completion", 502)
            usage = getattr(response, "usage", None)
            safe_usage = {}
            for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
                value = getattr(usage, key, None) if usage is not None else None
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    safe_usage[key] = value
            cost_usd, cost_status = None, "UNPRICED_MODEL"
            try:
                hidden = getattr(response, "_hidden_params", {}) or {}
                reported = hidden.get("response_cost") if isinstance(hidden, dict) else None
                if isinstance(reported, (int, float)) and reported >= 0:
                    cost_usd, cost_status = float(reported), "LITELLM_PRICED"
                else:
                    calculated = litellm.completion_cost(completion_response=response, model=litellm_model)
                    if isinstance(calculated, (int, float)) and calculated > 0:
                        cost_usd, cost_status = float(calculated), "LITELLM_PRICED"
            except Exception:
                pass  # Unknown pricing is never represented as free.
            return {
                "provider_id": candidate, "credential_id": credential.credential_id,
                "model": selected_model, "task_type": task_type, "content": content,
                "usage": safe_usage, "cost_usd": cost_usd, "cost_status": cost_status,
                "fallback_used": index > 0,
            }
        except GatewayError as error:
            if error.status_code == 503:
                last_error = error
                continue
            raise
        except Exception as error:
            error_name = type(error).__name__
            if error_name in {"AuthenticationError", "PermissionDeniedError"}:
                raise GatewayError(f"{candidate} rejected its configured credential", 502) from error
            if error_name in {"BadRequestError", "UnprocessableEntityError"}:
                raise GatewayError(f"{candidate} rejected the model request", 502) from error
            last_error = GatewayError(f"{candidate} temporarily unavailable ({error_name})", 503)
    raise last_error or GatewayError("No configured AI provider was available", 503)


def authorize_gateway(supplied_token: str | None) -> None:
    expected = os.getenv("QUANT_AI_GATEWAY_TOKEN", "").strip()
    if not expected:
        raise GatewayError("AI gateway is disabled until QUANT_AI_GATEWAY_TOKEN is configured", 503)
    if not supplied_token or not hmac.compare_digest(expected, supplied_token):
        raise GatewayError("Invalid AI gateway credential", 401)
