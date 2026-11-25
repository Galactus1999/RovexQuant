"""Bounded refresh orchestration for free macro providers and the RBIH DBIE mirror."""
from __future__ import annotations

import asyncio
import os
from datetime import datetime, timezone
from typing import Any

from rovexquant.market_data.macro_data import (
    fetch_amfi_nav, fetch_fred, fetch_rbi_dbie_mirror, fetch_world_bank,
)
from rovexquant.market_data.macro_store import persist_macro_payload, record_macro_failure


async def refresh_macro_sources() -> dict[str, Any]:
    providers: list[dict[str, Any]] = []
    jobs = (
        ("WORLD_BANK", fetch_world_bank),
        ("FRED", fetch_fred),
        ("AMFI", lambda: fetch_amfi_nav(limit=100)),
        ("RBIH_DBIE_MIRROR", fetch_rbi_dbie_mirror),
    )
    async def fetch_bounded(name: str, fetcher: Any) -> tuple[str, dict[str, Any] | None, Exception | None]:
        try:
            payload = await asyncio.wait_for(fetcher(), timeout=25.0)
            return name, payload, None
        except Exception as error:
            return name, None, error

    # Fetch independent providers concurrently. The old serial orchestration could
    # spend 15s x several series on each source inside one synchronous HTTP request,
    # allowing the Render edge/worker to terminate the request before persistence.
    fetched = await asyncio.gather(*(fetch_bounded(name, fetcher) for name, fetcher in jobs))
    for name, payload, error in fetched:
        if error is not None or payload is None:
            message = (str(error) if error else "Provider returned no payload")[:240] or "Provider refresh failed"
            try:
                record_macro_failure(name, message)
            except Exception:
                pass
            providers.append({
                "provider": name, "status": "FAILED", "error": message,
                "configured": bool(os.getenv("FRED_API_KEY", "").strip()) if name == "FRED" else True,
            })
            continue
        if payload.get("configured") is False:
            providers.append({
                "provider": name, "status": "SKIPPED",
                "reason": str(payload.get("reason") or "Provider is not configured")[:240],
                "configured": False,
            })
            continue
        try:
            persisted = persist_macro_payload(name, payload)
            persisted["configured"] = bool(payload.get("configured", True))
            warnings = payload.get("provider_warnings")
            if isinstance(warnings, list) and warnings:
                persisted["warnings"] = [str(item)[:200] for item in warnings[:5]]
            providers.append(persisted)
        except Exception as persist_error:
            message = str(persist_error)[:240] or "Provider persistence failed"
            try:
                record_macro_failure(name, message)
            except Exception:
                pass
            providers.append({
                "provider": name, "status": "FAILED", "error": message,
                "configured": bool(payload.get("configured", True)),
            })
    return {
        "retrieved_at": datetime.now(timezone.utc).isoformat(),
        "providers": providers,
        "succeeded": sum(item.get("status") == "SUCCEEDED" for item in providers),
        "failed": sum(item.get("status") == "FAILED" for item in providers),
        "skipped": sum(item.get("status") == "SKIPPED" for item in providers),
    }


def macro_refresh_exit_code(result: dict[str, Any]) -> int:
    """Fail scheduled refreshes when any enabled provider fails or nothing persists."""
    if int(result.get("failed", 0)) > 0 or int(result.get("succeeded", 0)) == 0:
        return 1
    return 0
