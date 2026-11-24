"""Resolve official NSE index instrument keys from Upstox's daily BOD master."""
from __future__ import annotations

import asyncio
from io import BytesIO
import gzip
import json
import re
import time
import zlib

import httpx


_MASTER_URL = "https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz"
_KEY_PATTERN = re.compile(r"^NSE_INDEX\|[A-Za-z0-9 _&()./-]{1,80}$")
_EQUITY_KEY_PATTERN = re.compile(r"^NSE_EQ\|INE[A-Z0-9]{9}$")
_CACHE_TTL_SECONDS = 6 * 60 * 60
_MAX_MASTER_BYTES = 100 * 1024 * 1024
_cache: list[dict[str, str]] = []
_cache_expires = 0.0
_equity_cache: list[dict[str, str]] = []
_equity_cache_expires = 0.0
_cache_lock = asyncio.Lock()


class InstrumentMasterError(Exception):
    def __init__(self, message: str, status_code: int = 503):
        super().__init__(message)
        self.status_code = status_code


def normalize_nse_index_master(raw: bytes) -> list[dict[str, str]]:
    if len(raw) > _MAX_MASTER_BYTES:
        raise InstrumentMasterError("Upstox NSE instrument master exceeds the size limit")
    try:
        if raw[:2] == b"\x1f\x8b":
            with gzip.GzipFile(fileobj=BytesIO(raw)) as compressed:
                content = compressed.read(_MAX_MASTER_BYTES + 1)
            if len(content) > _MAX_MASTER_BYTES:
                raise InstrumentMasterError("Decompressed Upstox instrument master exceeds the size limit")
        else:
            content = raw
        records = json.loads(content)
    except InstrumentMasterError:
        raise
    except (OSError, EOFError, zlib.error, UnicodeDecodeError, json.JSONDecodeError) as error:
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


def normalize_nse_equity_master(raw: bytes) -> list[dict[str, str]]:
    """Return verified NSE cash-equity and ETF instruments from the BOD master."""
    if len(raw) > _MAX_MASTER_BYTES:
        raise InstrumentMasterError("Upstox NSE instrument master exceeds the size limit")
    try:
        if raw[:2] == b"\x1f\x8b":
            with gzip.GzipFile(fileobj=BytesIO(raw)) as compressed:
                content = compressed.read(_MAX_MASTER_BYTES + 1)
            if len(content) > _MAX_MASTER_BYTES:
                raise InstrumentMasterError("Decompressed Upstox instrument master exceeds the size limit")
        else:
            content = raw
        records = json.loads(content)
    except InstrumentMasterError:
        raise
    except (OSError, EOFError, zlib.error, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise InstrumentMasterError("Upstox NSE instrument master is unreadable") from error
    if not isinstance(records, list):
        raise InstrumentMasterError("Upstox NSE instrument master has an invalid shape")

    output: list[dict[str, str]] = []
    seen: set[str] = set()
    for record in records:
        if not isinstance(record, dict):
            continue
        if record.get("segment") != "NSE_EQ" or record.get("instrument_type") not in {"EQ", "BE", "ETF"}:
            continue
        key, name = record.get("instrument_key"), record.get("name")
        symbol, isin = record.get("trading_symbol"), record.get("isin")
        if not isinstance(key, str) or not _EQUITY_KEY_PATTERN.fullmatch(key) or key in seen:
            continue
        if not isinstance(name, str) or not name.strip():
            continue
        if not isinstance(symbol, str) or not symbol.strip():
            continue
        if not isinstance(isin, str) or len(isin) != 12 or key != f"NSE_EQ|{isin}":
            continue
        seen.add(key)
        output.append({
            "instrument_key": key, "name": name.strip(),
            "trading_symbol": symbol.strip(), "isin": isin,
        })
    if not output:
        raise InstrumentMasterError("Upstox NSE instrument master contains no valid cash-equity instruments")
    return sorted(output, key=lambda item: item["trading_symbol"])


async def get_nse_equity_instruments(*, force_refresh: bool = False) -> list[dict[str, str]]:
    """Fetch/cache the official Upstox NSE BOD master and return its cash-equity universe."""
    global _equity_cache, _equity_cache_expires
    async with _cache_lock:
        now = time.monotonic()
        if not force_refresh and _equity_cache and now < _equity_cache_expires:
            return list(_equity_cache)
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(30.0, connect=8.0), follow_redirects=True
            ) as client:
                async with client.stream(
                    "GET", _MASTER_URL, headers={"Accept": "application/gzip, application/json"}
                ) as response:
                    if response.status_code != 200:
                        raise InstrumentMasterError(
                            f"Upstox instrument master request failed (HTTP {response.status_code})"
                        )
                    chunks: list[bytes] = []
                    total = 0
                    async for chunk in response.aiter_raw():
                        total += len(chunk)
                        if total > _MAX_MASTER_BYTES:
                            raise InstrumentMasterError("Upstox NSE instrument master exceeds the size limit")
                        chunks.append(chunk)
            resolved = normalize_nse_equity_master(b"".join(chunks))
        except InstrumentMasterError:
            raise
        except httpx.HTTPError as error:
            raise InstrumentMasterError("Upstox instrument master is temporarily unavailable") from error
        _equity_cache = resolved
        _equity_cache_expires = time.monotonic() + _CACHE_TTL_SECONDS
        return list(_equity_cache)


async def get_nse_index_instruments(*, force_refresh: bool = False) -> list[dict[str, str]]:
    global _cache, _cache_expires
    async with _cache_lock:
        now = time.monotonic()
        if not force_refresh and _cache and now < _cache_expires:
            return list(_cache)
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(30.0, connect=8.0), follow_redirects=True
            ) as client:
                async with client.stream(
                    "GET", _MASTER_URL, headers={"Accept": "application/gzip, application/json"}
                ) as response:
                    if response.status_code != 200:
                        raise InstrumentMasterError(
                            f"Upstox instrument master request failed (HTTP {response.status_code})"
                        )
                    chunks: list[bytes] = []
                    total = 0
                    async for chunk in response.aiter_raw():
                        total += len(chunk)
                        if total > _MAX_MASTER_BYTES:
                            raise InstrumentMasterError("Upstox NSE instrument master exceeds the size limit")
                        chunks.append(chunk)
            resolved = normalize_nse_index_master(b"".join(chunks))
        except InstrumentMasterError:
            raise
        except httpx.HTTPError as error:
            raise InstrumentMasterError("Upstox instrument master is temporarily unavailable") from error
        _cache = resolved
        _cache_expires = time.monotonic() + _CACHE_TTL_SECONDS
        return list(_cache)
