"""Read-only client for NSE's official Streamable HTTP MCP servers.

NSE data is used only for the authenticated owner's on-demand educational
research. No raw responses are persisted and no model-training interface exists.
"""
from __future__ import annotations

import json
import re
from typing import Any, Literal
from pathlib import Path

import httpx
from jsonschema.exceptions import SchemaError
from jsonschema.validators import validator_for


NSE_MCP_ENDPOINTS = {
    "bhavcopy": "https://mcp.nseindia.in/bhavcopy/cm/mcp",
    "cmmkt": "https://mcp.nseindia.in/cmmkt/mcp",
    "dalalos": "https://mcp.dalalos.in/mcp",
}
_DALALOS_CAPABILITIES_PATH = Path(__file__).with_name("data") / "dalalos_capabilities.json"
_DALALOS_DISABLED_TOOLS = {"add_to_watchlist", "remove_from_watchlist"}
_PROTOCOL_VERSION = "2025-03-26"
_TOOL_NAME = re.compile(r"^[A-Za-z0-9_.-]{1,128}$")
_BLOCKED_TOOL_PARTS = ("trade", "order", "execute", "cancel", "modify", "place", "submit", "write", "delete")
# NSE's live MCP catalogue omits readOnlyHint annotations. These exact data-query
# tools were inspected in the live catalogue and correspond to the read-only
# capabilities published on NSE's official MCP page. Unknown tools remain denied.
_NSE_READ_ONLY_TOOL_ALLOWLIST = frozenset({
    "get_top_by_volume", "get_top_movers", "nse_lookup_symbol", "get_market_mood",
    "get_index_valuation", "get_market_breadth", "get_corporate_actions",
    "compare_indices", "get_index_movers", "get_ltp_by_date", "get_bulk_quote",
    "get_volume_analysis", "get_stock_history", "get_index_snapshot", "search_symbols",
    "get_stock_vs_index", "compare_stocks", "get_index_history", "moving_average",
    "get_52_week_high_low", "get_index_performance",
    "cm_get_live_market_data", "cm_get_equity_stocks", "nse_get_losers",
    "cm_get_call_auction_stocks", "nse_get_gainers", "cm_get_data_status",
    "cm_get_stock_quote", "cm_get_index_quote", "cm_get_sme_stocks",
    "cm_get_live_gainers", "cm_get_bond_stocks", "cm_get_live_losers",
    "cm_get_live_indices", "nse_get_market_movers", "cm_get_allstocks_status",
})
_READ_ONLY_DESCRIPTION_VERBS = {
    "get", "search", "compare", "calculate", "calculates", "find",
    "list", "lookup", "look", "retrieve", "fetch", "returns",
    "check", "analyse", "analyze",
}
_MUTATING_DESCRIPTION_PARTS = (
    "place order", "submit order", "modify order", "cancel order",
    "delete", "create order", "write", "execute trade",
)


class NseMcpError(Exception):
    def __init__(self, message: str, status_code: int = 502, *, retryable: bool = False):
        super().__init__(message)
        self.status_code = status_code
        self.retryable = retryable


def _decode_response(response: httpx.Response, expected_id: int) -> dict[str, Any]:
    content_type = response.headers.get("content-type", "").lower()
    body = response.text
    if len(body.encode("utf-8", errors="replace")) > 10_000_000:
        raise NseMcpError("NSE MCP response exceeded the 10 MB safety limit", 502)
    if "text/event-stream" in content_type:
        candidates: list[dict[str, Any]] = []
        for event in body.replace("\r\n", "\n").split("\n\n"):
            data = "\n".join(
                line[5:].lstrip() for line in event.splitlines() if line.startswith("data:")
            )
            if not data or data == "[DONE]":
                continue
            try:
                item = json.loads(data)
            except ValueError:
                continue
            if isinstance(item, dict):
                candidates.append(item)
        matching = [item for item in candidates if item.get("id") == expected_id]
        if not matching:
            raise NseMcpError("NSE MCP event stream contained no matching JSON-RPC response", 502)
        payload = matching[-1]
    else:
        try:
            payload = json.loads(body)
        except (TypeError, ValueError) as error:
            raise NseMcpError("NSE MCP returned an invalid JSON-RPC response", 502) from error
    if not isinstance(payload, dict):
        raise NseMcpError("NSE MCP returned an invalid response envelope", 502)
    return payload


class NseMcpClient:
    """One-request MCP session; discovers schemas before invoking a tool."""

    def __init__(
        self,
        server: Literal["bhavcopy", "cmmkt", "dalalos"],
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout: float = 20.0,
        api_key: str | None = None,
    ):
        if server not in NSE_MCP_ENDPOINTS:
            raise NseMcpError("Unknown research MCP server", 422)
        self.server = server
        self.endpoint = NSE_MCP_ENDPOINTS[server]
        self._api_key = api_key.strip() if isinstance(api_key, str) else None
        if server == "dalalos" and not self._api_key:
            raise NseMcpError("DalalOS API key is not configured", 409)
        self._transport = transport
        self._timeout = timeout
        self._session_id: str | None = None
        self._request_id = 0
        self._client: httpx.AsyncClient | None = None
        self._initialized = False
        self._protocol_version = _PROTOCOL_VERSION

    async def __aenter__(self) -> "NseMcpClient":
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(self._timeout, connect=7.0),
            follow_redirects=False,
            transport=self._transport,
            headers={"Accept": "application/json, text/event-stream", "Content-Type": "application/json"},
        )
        try:
            await self._initialize()
        except BaseException:
            await self._client.aclose()
            self._client = None
            raise
        return self

    async def __aexit__(self, *_: object) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    def _next_id(self) -> int:
        self._request_id += 1
        return self._request_id

    async def _post(self, message: dict[str, Any], *, expect_response: bool = True) -> dict[str, Any] | None:
        if self._client is None:
            raise NseMcpError("NSE MCP client is not initialized", 500)
        headers: dict[str, str] = {}
        if self.server == "dalalos" and self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        if message.get("method") != "initialize":
            headers["MCP-Protocol-Version"] = self._protocol_version
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        try:
            async with self._client.stream(
                "POST", self.endpoint, json=message, headers=headers
            ) as streamed:
                body = bytearray()
                async for chunk in streamed.aiter_bytes():
                    if len(body) + len(chunk) > 10_000_000:
                        raise NseMcpError("NSE MCP response exceeded the 10 MB safety limit", 502)
                    body.extend(chunk)
                # aiter_bytes() has already decoded content-encoding (e.g. gzip).
                # Reusing the original encoding/length headers makes httpx attempt
                # a second decompression when response.text is accessed.
                response_headers = dict(streamed.headers)
                response_headers.pop("content-encoding", None)
                response_headers.pop("content-length", None)
                response = httpx.Response(
                    streamed.status_code,
                    headers=response_headers,
                    content=bytes(body),
                    request=streamed.request,
                )
        except NseMcpError:
            raise
        except httpx.TimeoutException as error:
            raise NseMcpError("NSE MCP request timed out", 503, retryable=True) from error
        except httpx.RequestError as error:
            raise NseMcpError("NSE MCP service is unreachable", 503, retryable=True) from error

        if response.status_code == 429 or response.status_code >= 500:
            raise NseMcpError(f"NSE MCP temporarily unavailable (HTTP {response.status_code})", 503, retryable=True)
        if response.status_code in (401, 403):
            if self.server == "dalalos":
                raise NseMcpError("DalalOS authentication failed; check the API key and plan quota", 401)
            raise NseMcpError("NSE MCP refused the connection", 502)
        if response.status_code >= 400:
            raise NseMcpError(f"NSE MCP rejected the request (HTTP {response.status_code})", 502)
        session_id = response.headers.get("Mcp-Session-Id")
        if session_id:
            self._session_id = session_id
        if not expect_response:
            return None
        if response.status_code == 202:
            raise NseMcpError("NSE MCP accepted a request without returning its JSON-RPC response", 502)
        payload = _decode_response(response, message.get("id"))
        if payload.get("jsonrpc") != "2.0":
            raise NseMcpError("NSE MCP returned an invalid JSON-RPC version", 502)
        if "error" in payload:
            # Do not forward arbitrary remote error text into API responses or logs.
            raise NseMcpError("NSE MCP returned a remote JSON-RPC error", 502)
        return payload

    async def _initialize(self) -> None:
        request_id = self._next_id()
        payload = await self._post({
            "jsonrpc": "2.0",
            "id": request_id,
            "method": "initialize",
            "params": {
                "protocolVersion": _PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "RovexQuant", "version": "0.1.0"},
            },
        })
        if not payload or payload.get("id") != request_id or not isinstance(payload.get("result"), dict):
            raise NseMcpError("NSE MCP initialization response did not match the request", 502)
        negotiated = payload["result"].get("protocolVersion")
        if negotiated != _PROTOCOL_VERSION:
            raise NseMcpError("NSE MCP negotiated an unsupported protocol version", 502)
        self._protocol_version = negotiated
        self._initialized = True
        await self._post({"jsonrpc": "2.0", "method": "notifications/initialized"}, expect_response=False)

    async def list_tools(self) -> list[dict[str, Any]]:
        if not self._initialized:
            raise NseMcpError("NSE MCP session is not initialized", 500)
        tools: list[dict[str, Any]] = []
        cursor: str | None = None
        for _ in range(10):
            params = {"cursor": cursor} if cursor else {}
            request_id = self._next_id()
            payload = await self._post({
                "jsonrpc": "2.0", "id": request_id, "method": "tools/list", "params": params,
            })
            if not payload or payload.get("id") != request_id:
                raise NseMcpError("NSE MCP tools/list response did not match the request", 502)
            result = payload.get("result")
            if not isinstance(result, dict) or not isinstance(result.get("tools", []), list):
                raise NseMcpError("NSE MCP returned an invalid tools/list result", 502)
            page = result.get("tools", [])
            if any(not isinstance(tool, dict) or not isinstance(tool.get("name"), str) for tool in page):
                raise NseMcpError("NSE MCP returned an invalid tool definition", 502)
            tools.extend(page)
            cursor = result.get("nextCursor")
            if cursor is not None and not isinstance(cursor, str):
                raise NseMcpError("NSE MCP returned an invalid pagination cursor", 502)
            if not cursor:
                return tools
        if cursor:
            raise NseMcpError("NSE MCP tool catalogue exceeded pagination safety limit", 502)
        return tools

    async def call_tool(
        self,
        name: str,
        arguments: dict[str, Any],
        *,
        confirm_non_read_only: bool = False,
    ) -> dict[str, Any]:
        if not _TOOL_NAME.fullmatch(name):
            raise NseMcpError("Invalid MCP tool name", 422)
        if self.server != "dalalos" and any(part in name.lower() for part in _BLOCKED_TOOL_PARTS):
            raise NseMcpError("Quant permits read-only NSE research tools only", 403)
        tools = await self.list_tools()
        tool = next((item for item in tools if item["name"] == name), None)
        if tool is None:
            raise NseMcpError("Tool is not published by this NSE MCP server", 404)
        if self.server == "dalalos":
            capability = _dalalos_capability(name)
            if capability is None or capability.get("deprecated") is True:
                raise NseMcpError("DalalOS tool is not in the imported, current capability allowlist", 403)
            if name in _DALALOS_DISABLED_TOOLS:
                raise NseMcpError("DalalOS watchlist write tools are disabled", 403)
            if capability.get("destructive") is True:
                raise NseMcpError("Destructive DalalOS tools are disabled", 403)
            read_only = capability.get("read_only") is True
            annotations = tool.get("annotations")
            if isinstance(annotations, dict) and annotations.get("readOnlyHint") is False:
                raise NseMcpError("DalalOS tool metadata contradicts its read-only capability", 403)
            if not read_only and not confirm_non_read_only:
                raise NseMcpError(
                    "This DalalOS tool may trigger processing or consume plan credits; explicit confirmation is required",
                    403,
                )
        else:
            reviewed = {
                item["name"]: item["read_only"]
                for item in safe_tool_catalog(tools)
            }
            if not reviewed.get(name, False):
                raise NseMcpError(
                    "NSE MCP tool is not marked read-only or otherwise positively identified", 403
                )
        schema = tool.get("inputSchema")
        if not isinstance(schema, dict) or schema.get("type") != "object":
            raise NseMcpError("NSE MCP tool has an unsupported input schema", 502)

        # MCP schemas are remote input. Reject all $ref values so validation cannot
        # resolve remote URLs or turn a catalogue response into an SSRF primitive.
        def contains_ref(value: Any) -> bool:
            if isinstance(value, dict):
                return "$ref" in value or any(contains_ref(item) for item in value.values())
            if isinstance(value, list):
                return any(contains_ref(item) for item in value)
            return False

        schema_size = len(json.dumps(schema, ensure_ascii=True, separators=(",", ":")).encode("utf-8"))
        if schema_size > 128 * 1024:
            raise NseMcpError("NSE MCP tool schema exceeded the 128 KB safety limit", 502)
        if contains_ref(schema):
            raise NseMcpError("NSE MCP tool schema references are not supported", 502)
        try:
            validator_class = validator_for(schema)
            validator_class.check_schema(schema)
            errors = list(validator_class(schema).iter_errors(arguments))
        except SchemaError as error:
            raise NseMcpError("NSE MCP published an invalid input schema", 502) from error
        if errors:
            raise NseMcpError("NSE MCP arguments do not match the tool schema", 422)

        request_id = self._next_id()
        payload = await self._post({
            "jsonrpc": "2.0",
            "id": request_id,
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments},
        })
        if not payload or payload.get("id") != request_id or not isinstance(payload.get("result"), dict):
            raise NseMcpError("NSE MCP tools/call response did not match the request", 502)
        result = payload["result"]
        if result.get("isError") is True:
            raise NseMcpError("NSE MCP tool reported an execution error", 502)
        return {
            "tool": name,
            "content": result.get("content", []),
            "structured_content": result.get("structuredContent"),
        }


def _dalalos_capabilities() -> dict[str, dict[str, Any]]:
    try:
        payload = json.loads(_DALALOS_CAPABILITIES_PATH.read_text(encoding="utf-8"))
        records = payload.get("tools", [])
        if not isinstance(records, list):
            return {}
        return {
            item["name"]: item for item in records
            if isinstance(item, dict) and isinstance(item.get("name"), str)
        }
    except (OSError, ValueError, TypeError):
        return {}


def _dalalos_capability(name: str) -> dict[str, Any] | None:
    return _dalalos_capabilities().get(name)


def safe_tool_catalog(
    tools: list[dict[str, Any]],
    server: Literal["bhavcopy", "cmmkt", "dalalos"] = "bhavcopy",
) -> list[dict[str, Any]]:
    """Return only locally allowlisted DalalOS tools or reviewed read-only NSE tools."""
    safe: list[dict[str, Any]] = []
    capabilities = _dalalos_capabilities() if server == "dalalos" else {}
    for tool in tools:
        name = tool.get("name")
        if not isinstance(name, str):
            continue
        description = str(tool.get("description", ""))[:2000]
        annotations = tool.get("annotations")
        if server == "dalalos":
            capability = capabilities.get(name)
            if capability is None or capability.get("deprecated") is True or name in _DALALOS_DISABLED_TOOLS:
                continue
            destructive = capability.get("destructive") is True
            read_only = capability.get("read_only") is True
            if destructive or (isinstance(annotations, dict) and annotations.get("readOnlyHint") is False):
                read_only = False
            safe.append({
                "name": name,
                "description": description,
                "input_schema": tool.get("inputSchema") if isinstance(tool.get("inputSchema"), dict) else {},
                "read_only": read_only,
                "requires_confirmation": not read_only and not destructive,
                "destructive": destructive,
            })
            continue

        read_only_hint = annotations.get("readOnlyHint") if isinstance(annotations, dict) else None
        name_is_safe = not any(part in name.lower() for part in _BLOCKED_TOOL_PARTS)
        if read_only_hint is False:
            read_only = False
        elif read_only_hint is True:
            read_only = name_is_safe
        else:
            description_words = description.strip().lower().split()
            first_verb = description_words[0] if description_words else ""
            description_is_safe = (
                first_verb in _READ_ONLY_DESCRIPTION_VERBS
                and not any(part in description.lower() for part in _MUTATING_DESCRIPTION_PARTS)
            )
            read_only = (
                name in _NSE_READ_ONLY_TOOL_ALLOWLIST
                and name_is_safe
                and description_is_safe
            )
        safe.append({
            "name": name,
            "description": description,
            "input_schema": tool.get("inputSchema") if isinstance(tool.get("inputSchema"), dict) else {},
            "read_only": read_only,
        })
    return safe


def provider_status() -> list[dict[str, Any]]:
    return [
        {
            "provider_id": f"nse_mcp_{server}",
            "configured": True,
            "endpoint": endpoint,
            "mode": "END_OF_DAY" if server == "bhavcopy" else "DELAYED",
            "expected_delay": None if server == "bhavcopy" else "approximately 1–3 minutes",
            "connection_status": "not_checked",
            "authentication": "No API key documented by NSE",
            "retention": "No raw response persistence",
            "usage": "Authenticated owner's private educational research only",
        }
        for server, endpoint in NSE_MCP_ENDPOINTS.items()
        if server != "dalalos"
    ]


def extract_source_timestamp(result: dict[str, Any]) -> str | int | float | None:
    """Read explicit feed-level timestamps, never timestamps from individual data rows."""
    timestamp_keys = {
        "source_timestamp", "sourcetimestamp", "nsetimestamp", "updatedat",
        "timestamp", "as_of", "asof", "last_updated", "lastupdated",
        "last_updated_at", "lastupdatedat", "feed_timestamp", "market_time",
        "exchange_timestamp",
    }
    metadata_keys = {"meta", "metadata", "freshness", "feed_status", "market_status"}
    roots: list[Any] = [result.get("structured_content")]
    content = result.get("content")
    if isinstance(content, list):
        roots.extend(item.get("text") for item in content if isinstance(item, dict))

    pending: list[tuple[Any, int]] = [(value, 0) for value in roots]
    while pending:
        value, depth = pending.pop()
        if depth > 4:
            continue
        if isinstance(value, str):
            try:
                value = json.loads(value)
            except ValueError:
                continue
        if not isinstance(value, dict):
            continue
        for key, item in value.items():
            if key.lower() in timestamp_keys and isinstance(item, (str, int, float)) and not isinstance(item, bool):
                return item
        for key, item in value.items():
            if key.lower() in metadata_keys and isinstance(item, dict):
                pending.append((item, depth + 1))
    return None
