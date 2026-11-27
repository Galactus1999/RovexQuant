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

import httpx

from rovexquant.ai.providers import SUPPORTED

TASK_TYPES = frozenset({
    "chat", "research", "macro_analysis", "filing_extraction",
    "claim_verification", "summarization",
})


def configured_task_routes() -> dict[str, dict[str, Any]]:
    """Load validated, server-owned task routes without exposing credentials."""
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
        if (
            not isinstance(provider, str) or provider not in SUPPORTED
            or not isinstance(model, str) or not model.strip() or len(model) > 160
        ):
            raise GatewayError(f"Invalid provider/model in AI task route: {task}", 503)
        if (
            not isinstance(fallbacks, list) or len(fallbacks) > 3
            or any(not isinstance(item, str) or item not in SUPPORTED for item in fallbacks)
        ):
            raise GatewayError(f"Invalid fallback providers in AI task route: {task}", 503)
        routes[task] = {
            "provider_id": provider,
            "model": model.strip(),
            "fallback_providers": list(dict.fromkeys(fallbacks)),
        }
    return routes


def resolve_task_route(
    task_type: str,
    provider_id: str | None,
    model: str | None,
    fallback_providers: list[str] | None,
) -> tuple[str, str, list[str] | None]:
    if task_type not in TASK_TYPES:
        raise GatewayError("Unsupported AI task type", 422)
    if provider_id is not None or model is not None:
        if (
            not isinstance(provider_id, str) or provider_id not in SUPPORTED
            or not isinstance(model, str) or not model.strip() or len(model) > 160
        ):
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


async def discover_provider_model(provider_id: str) -> str | None:
    """Discover a usable chat model from the provider catalogue; never require a caller model ID."""
    if provider_id not in SUPPORTED:
        raise GatewayError("Unsupported AI provider", 400)
    credentials = _credentials_for(provider_id)
    if not credentials:
        return None
    credential = _select_credential(provider_id, None)
    config = SUPPORTED[provider_id]
    catalogue_url = f"{config.base_url}/models"
    if provider_id == "openrouter":
        catalogue_url += "?input_modalities=text"
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(12.0, connect=5.0)) as client:
            response = await client.get(
                catalogue_url,
                headers=_provider_headers(provider_id, credential.api_key),
            )
    except (httpx.TimeoutException, httpx.RequestError):
        return None
    if response.status_code in (401, 403):
        raise GatewayError(f"{provider_id} rejected its configured credential during model discovery", 502)
    if response.status_code == 429 or response.status_code >= 500:
        return None
    if response.status_code >= 400:
        return None
    try:
        payload = response.json()
    except (ValueError, TypeError):
        return None
    rows = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        return None
    excluded = (
        "embed", "embedding", "whisper", "moderation", "rerank", "tts",
        "transcri", "speech", "audio", "image", "vision", "video", "realtime",
    )
    candidates: list[str] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        model_id = row.get("id")
        if not isinstance(model_id, str) or not model_id.strip() or len(model_id) > 180:
            continue
        if row.get("active") is False or row.get("shutdown_date"):
            continue
        # Gemini's OpenAI-compatible model catalogue may prefix IDs with "models/".
        if provider_id == "gemini" and model_id.startswith("models/"):
            model_id = model_id.split("/", 1)[1]
        normalized = model_id.lower()
        if len(model_id) > 160 or any(term in normalized for term in excluded):
            continue
        # Provider metadata, when supplied, can explicitly identify non-chat models.
        architecture = row.get("architecture")
        modality = architecture.get("modality") if isinstance(architecture, dict) else None
        if isinstance(modality, str) and "text" not in modality.lower():
            continue
        input_modalities = row.get("input_modalities")
        if isinstance(input_modalities, list) and input_modalities and "text" not in input_modalities:
            continue
        output_modalities = row.get("output_modalities")
        if isinstance(output_modalities, list) and output_modalities and "text" not in output_modalities:
            continue
        supported = row.get("supported_parameters")
        if isinstance(supported, list) and supported and not any(
            item in {"messages", "chat", "tools", "response_format"} for item in supported
        ):
            continue
        candidates.append(model_id.strip())
    # Preserve provider catalogue order: providers may rank their current/recommended
    # models first. The selected ID is never exposed as a user-editable setting.
    return candidates[0] if candidates else None


async def resolve_auto_route(
    enabled_provider_ids: list[str] | None = None,
) -> tuple[str, str, list[str]]:
    """Choose the first enabled, credentialed provider with a discoverable chat model."""
    enabled = list(SUPPORTED) if enabled_provider_ids is None else enabled_provider_ids
    if not isinstance(enabled, list) or len(enabled) > len(SUPPORTED):
        raise GatewayError("Invalid enabled AI provider selection", 422)
    if any(not isinstance(item, str) or item not in SUPPORTED for item in enabled):
        raise GatewayError("Enabled AI provider selection contains an unsupported provider", 422)
    enabled = list(dict.fromkeys(enabled))
    if not enabled:
        raise GatewayError("All AI providers are disabled. Enable at least one configured provider.", 503)
    for index, provider in enumerate(enabled):
        if not _credentials_for(provider):
            continue
        model = await discover_provider_model(provider)
        if model:
            # Fallbacks are resolved lazily only if the primary provider is unavailable.
            fallbacks = [
                candidate for candidate in enabled[index + 1:]
                if _credentials_for(candidate)
            ][:3]
            return provider, model, fallbacks
    raise GatewayError("No enabled AI provider has a usable credential and discoverable chat model", 503)


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
    *,
    provider_id: str | None,
    model: str | None,
    messages: list[dict[str, str]],
    max_tokens: int = 1200,
    temperature: float = 0.2,
    credential_id: str | None = None,
    fallback_providers: list[str] | None = None,
    task_type: str = "chat",
    enabled_provider_ids: list[str] | None = None,
) -> dict[str, Any]:
    if provider_id is None and model is None:
        provider_id, model, auto_fallbacks = await resolve_auto_route(enabled_provider_ids)
        fallback_providers = auto_fallbacks
    else:
        provider_id, model, fallback_providers = resolve_task_route(
            task_type, provider_id, model, fallback_providers
        )
    if provider_id not in SUPPORTED:
        raise GatewayError("Unsupported AI provider", 400)
    if not model.strip() or len(model) > 160:
        raise GatewayError("A valid provider model ID is required", 422)
    if not messages or len(messages) > 32:
        raise GatewayError("Provide between 1 and 32 chat messages", 422)
    allowed_roles = {"system", "user", "assistant"}
    if any(item.get("role") not in allowed_roles or not isinstance(item.get("content"), str) for item in messages):
        raise GatewayError("Messages must contain supported roles and text content", 422)
    if sum(len(item["content"]) for item in messages) > 24000:
        raise GatewayError("Combined message content exceeds the 24,000 character limit", 413)
    if max_tokens < 1 or max_tokens > 4096:
        raise GatewayError("max_tokens must be between 1 and 4096", 422)
    if temperature < 0 or temperature > 2:
        raise GatewayError("temperature must be between 0 and 2", 422)

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
            selected_id = credential_id if index == 0 else None
            credential = _select_credential(candidate, selected_id)
            config = SUPPORTED[candidate]
            selected_model = model if index == 0 else await discover_provider_model(candidate)
            if not selected_model:
                last_error = GatewayError(f"No discoverable chat model is available for {candidate}", 503)
                continue
            payload = {
                "model": selected_model,
                "messages": messages,
                "max_tokens": max_tokens,
                "temperature": temperature,
            }
            async with httpx.AsyncClient(timeout=httpx.Timeout(25.0, connect=8.0)) as client:
                response = await client.post(
                    f"{config.base_url}/chat/completions",
                    headers=_provider_headers(candidate, credential.api_key),
                    json=payload,
                )
            if response.status_code == 429 or response.status_code >= 500:
                last_error = GatewayError(
                    f"{candidate} temporarily unavailable (HTTP {response.status_code})", 503
                )
                continue
            if response.status_code in (401, 403):
                raise GatewayError(f"{candidate} rejected its configured credential", 502)
            if response.status_code in (400, 404, 422):
                try:
                    error_payload = response.json()
                except (ValueError, TypeError):
                    error_payload = {}
                error_text = json.dumps(error_payload, ensure_ascii=False).lower()
                model_unavailable = "model" in error_text and any(
                    marker in error_text for marker in (
                        "not found", "does not exist", "not available",
                        "not supported", "model_not_found", "unsupported model",
                    )
                )
                if model_unavailable:
                    last_error = GatewayError(
                        f"{candidate} no longer accepts the discovered model; trying the next enabled provider",
                        503,
                    )
                    continue
                raise GatewayError(f"{candidate} rejected the request (HTTP {response.status_code})", 502)
            if response.status_code >= 400:
                raise GatewayError(f"{candidate} rejected the request (HTTP {response.status_code})", 502)
            data = response.json()
            choices = data.get("choices") if isinstance(data, dict) else None
            if not isinstance(choices, list) or not choices:
                raise GatewayError(f"{candidate} returned an invalid completion response", 502)
            message = choices[0].get("message", {})
            content = message.get("content") if isinstance(message, dict) else None
            if not isinstance(content, str):
                raise GatewayError(f"{candidate} returned no text completion", 502)
            usage = data.get("usage", {})
            safe_usage = {
                key: usage[key]
                for key in ("prompt_tokens", "completion_tokens", "total_tokens")
                if isinstance(usage, dict) and isinstance(usage.get(key), (int, float))
            }
            return {
                "provider_id": candidate,
                "credential_id": credential.credential_id,
                "model": selected_model,
                "content": content,
                "usage": safe_usage,
                "fallback_used": index > 0,
                "task_type": task_type,
            }
        except GatewayError as error:
            if error.status_code == 503:
                last_error = error
                continue
            raise
        except httpx.TimeoutException:
            last_error = GatewayError(f"{candidate} request timed out", 503)
        except httpx.RequestError:
            last_error = GatewayError(f"{candidate} could not be reached", 503)
    raise last_error or GatewayError("No configured AI provider was available", 503)


def authorize_gateway(supplied_token: str | None) -> None:
    expected = os.getenv("QUANT_AI_GATEWAY_TOKEN", "").strip()
    if not expected:
        raise GatewayError("AI gateway is disabled until QUANT_AI_GATEWAY_TOKEN is configured", 503)
    if not supplied_token or not hmac.compare_digest(expected, supplied_token):
        raise GatewayError("Invalid AI gateway credential", 401)
