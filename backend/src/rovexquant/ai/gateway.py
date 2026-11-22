"""Server-side, provider-neutral AI gateway.

Credentials are loaded only from the backend environment/secret manager. The gateway
never returns credential material, logs prompts, or rotates keys to evade provider limits.
"""
from __future__ import annotations

import hmac
import json
import os
from dataclasses import dataclass
from typing import Any

import httpx

from rovexquant.ai.providers import SUPPORTED


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
    # Stable selection is intentional. Named credentials are explicitly selectable;
    # they are never round-robin rotated when a provider returns 429.
    return next((item for item in credentials if item.credential_id == "default"), credentials[0])


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
    provider_id: str,
    model: str,
    messages: list[dict[str, str]],
    max_tokens: int = 1200,
    temperature: float = 0.2,
    credential_id: str | None = None,
    fallback_providers: list[str] | None = None,
) -> dict[str, Any]:
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
            fallback_model = os.getenv(f"{candidate.upper()}_FALLBACK_MODEL", "").strip()
            selected_model = model if index == 0 else fallback_model
            if not selected_model:
                last_error = GatewayError(f"No fallback model configured for {candidate}", 503)
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
