"""Bounded refresh orchestration for free macro providers and the RBIH DBIE mirror."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from rovexquant.market_data.macro_data import (
    MacroProviderError, fetch_amfi_nav, fetch_fred, fetch_rbi_dbie_mirror, fetch_world_bank,
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
    for name, fetcher in jobs:
        try:
            payload = await fetcher()
            if payload.get("configured") is False:
                providers.append({
                    "provider": name, "status": "SKIPPED",
                    "reason": str(payload.get("reason") or "Provider is not configured")[:240],
                })
                continue
            providers.append(persist_macro_payload(name, payload))
        except Exception as error:
            message = str(error)[:240] or "Provider refresh failed"
            try:
                record_macro_failure(name, message)
            except Exception:
                pass
            providers.append({"provider": name, "status": "FAILED", "error": message})
    return {
        "retrieved_at": datetime.now(timezone.utc).isoformat(),
        "providers": providers,
        "succeeded": sum(item.get("status") == "SUCCEEDED" for item in providers),
        "failed": sum(item.get("status") == "FAILED" for item in providers),
        "skipped": sum(item.get("status") == "SKIPPED" for item in providers),
    }
