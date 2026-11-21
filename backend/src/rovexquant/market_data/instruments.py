"""Resolve official NSE index instrument keys from Upstox's daily BOD master."""
from __future__ import annotations

import gzip
import json
import re
import time

import httpx


_MASTER_URL = "https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz"
_KEY_PATTERN = re.compile(r"^NSE_INDEX\|[A-Za-z0-9 _&()./-]{1,80}$")
_CACHE_TTL_SECONDS = 6 * 60 * 60
_cache: list[dict[str, str]] = []
_cache_expires = 0.0


class InstrumentMasterError(Exception):
    def __init__(self, message: str, status_code: int = 503):
        super().__init__(message)
        self.status_code = status_code


def normalize_nse_index_master(raw: bytes) -> list[dict[str, str]]:
    try:
        content = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw
        records = json.loads(content)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise InstrumentMasterError("Upstox NSE instrument master is unreadable") from error
    if not isinstance(records, list):
        raise InstrumentMasterError("Upstox NSE instrument master has an invalid shape")
    output: list[dict[str, str]] = []
    seen: set[str] = set()
    for record in records:
        if not isinstance(record, dict):
            continue
        if record.get("segment") != "NSE_INDEX" or record.get("instrument_type") != "INDEX":
            continue
        key = record.get("instrument_key")
        name = record.get("name")
        trading_symbol = record.get("trading_symbol")
        if not isinstance(key, str) or not _KEY_PATTERN.fullmatch(key) or key in seen:
            continue
        if not isinstance(name, str) or not name.strip():
            continue
        if not isinstance(trading_symbol, str):
            trading_symbol = ""
        seen.add(key)
        output.append({
            "instrument_key": key,
            "name": name.strip(),
            "trading_symbol": trading_symbol.strip(),
        })
    if not output:
        raise InstrumentMasterError("Upstox NSE instrument master contains no valid index instruments")
    return output


async def get_nse_index_instruments(*, force_refresh: bool = False) -> list[dict[str, str]]:
    global _cache, _cache_expires
    now = time.monotonic()
    if not force_refresh and _cache and now < _cache_expires:
        return list(_cache)
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(30.0, connect=8.0), follow_redirects=True) as client:
            response = await client.get(_MASTER_URL, headers={"Accept": "application/gzip, application/json"})
        if response.status_code != 200:
            raise InstrumentMasterError(f"Upstox instrument master request failed (HTTP {response.status_code})")
        resolved = normalize_nse_index_master(response.content)
    except httpx.HTTPError as error:
        if _cache:
            return list(_cache)
        raise InstrumentMasterError("Upstox instrument master is temporarily unavailable") from error
    _cache = resolved
    _cache_expires = time.monotonic() + _CACHE_TTL_SECONDS
    return list(_cache)
