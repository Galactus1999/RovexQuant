"""Read-only client for NSE's official Streamable HTTP MCP servers.

NSE data is used only for the authenticated owner's on-demand educational
research. No raw responses are persisted and no model-training interface exists.
"""
from __future__ import annotations

from datetime import datetime, timezone
import json
import re
from typing import Any, Literal
from uuid import uuid4

import httpx


NSE_MCP_ENDPOINTS = {
    "bhavcopy": "https://mcp.nseindia.in/bhavcopy/cm/mcp",
    "cmmkt": "https://mcp.nseindia.in/cmmkt/mcp",
}
_PROTOCOL_VERSION = "2025-03-26"
_TOOL_NAME = re.compile(r"^[A-Za-z0-9_.-]{1,128}$")
_BLOCKED_TOOL_PARTS = ("trade", "order", "execute", "cancel", "modify", "place", "submit", "write", "delete")


class NseMcpError(Exception):
    def __init__(self, message: str, status_code: int = 502, *, retryable: bool = False):
        super().__init__(message)
        self.status_code = status_code
        self.retryable = retryable


def _decode_response(response: httpx.Response) -> dict[str, Any]:
    content_type = response.headers.get("content-type", "").lower()
    body = response.text
    if "text/event-stream" in content_type:
        messages: list[str] = []
        for event in body.replace("\r\n", "\n").split("\n\n"):
            data = "\n".join(
                line[5:].lstrip() for line in event.splitlines() if line.startswith("data:")
            )
            if data and data != "[DONE]":
                messages.append(data)
        if not messages:
            raise NseMcpError("NSE MCP returned an empty event stream", 502)
        body = messages[-1]
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
        server: Literal["bhavcopy", "cmmkt"],
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout: float = 20.0,
    ):
        if server not in NSE_MCP_ENDPOINTS:
            raise NseMcpError("Unknown NSE MCP server", 422)
        self.server = server
        self.endpoint = NSE_MCP_ENDPOINTS[server]
        self._transport = transport
        self._timeout = timeout
        self._session_id: str | None = None
        self._request_id = 0
        self._client: httpx.AsyncClient | None = None
        self._initialized = False

    async def __aenter__(self) -> "NseMcpClient":
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(self._timeout, connect=7.0),
            follow_redirects=False,
            transport=self._transport,
            headers={"Accept": "application/json, text/event-stream", "Content-Type": "application/json"},
        )
        await self._initialize()
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
        headers = {"MCP-Protocol-Version": _PROTOCOL_VERSION}
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        try:
            response = await self._client.post(self.endpoint, json=message, headers=headers)
        except httpx.TimeoutException as error:
            raise NseMcpError("NSE MCP request timed out", 503, retryable=True) from error
        except httpx.RequestError as error:
            raise NseMcpError("NSE MCP service is unreachable", 503, retryable=True) from error

        if response.status_code == 429 or response.status_code >= 500:
            raise NseMcpError(f"NSE MCP temporarily unavailable (HTTP {response.status_code})", 503, retryable=True)
        if response.status_code in (401, 403):
            raise NseMcpError("NSE MCP refused the connection", 502)
        if response.status_code >= 400:
            raise NseMcpError(f"NSE MCP rejected the request (HTTP {response.status_code})", 502)
        session_id = response.headers.get("Mcp-Session-Id")
        if session_id:
            self._session_id = session_id
        if not expect_response or response.status_code == 202:
            return None
        payload = _decode_response(response)
        if payload.get("jsonrpc") != "2.0":
            raise NseMcpError("NSE MCP returned an invalid JSON-RPC version", 502)
        if "error" in payload:
            error = payload.get("error")
            message_text = error.get("message", "Remote JSON-RPC error") if isinstance(error, dict) else "Remote JSON-RPC error"
            raise NseMcpError(f"NSE MCP JSON-RPC error: {str(message_text)[:240]}", 502)
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
        if not isinstance(negotiated, str) or not negotiated:
            raise NseMcpError("NSE MCP did not return a protocol version", 502)
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
            if not cursor:
                return tools
        if cursor:
            raise NseMcpError("NSE MCP tool catalogue exceeded pagination safety limit", 502)
        return tools

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if not _TOOL_NAME.fullmatch(name):
            raise NseMcpError("Invalid NSE MCP tool name", 422)
        if any(part in name.lower() for part in _BLOCKED_TOOL_PARTS):
            raise NseMcpError("Quant permits read-only NSE research tools only", 403)
        tools = await self.list_tools()
        tool = next((item for item in tools if item["name"] == name), None)
        if tool is None:
            raise NseMcpError("Tool is not published by this NSE MCP server", 404)
        annotations = tool.get("annotations")
        if isinstance(annotations, dict) and annotations.get("readOnlyHint") is False:
            raise NseMcpError("NSE MCP tool is not marked read-only", 403)
        schema = tool.get("inputSchema")
        if not isinstance(schema, dict) or schema.get("type") not in (None, "object"):
            raise NseMcpError("NSE MCP tool has an unsupported input schema", 502)
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
            "provider_metadata": result.get("_meta"),
        }


def safe_tool_catalog(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Return tool descriptions and schemas without leaking server metadata."""
    safe: list[dict[str, Any]] = []
    for tool in tools:
        name = tool.get("name")
        if not isinstance(name, str):
            continue
        safe.append({
            "name": name,
            "description": str(tool.get("description", ""))[:2000],
            "input_schema": tool.get("inputSchema") if isinstance(tool.get("inputSchema"), dict) else {},
            "read_only": not any(part in name.lower() for part in _BLOCKED_TOOL_PARTS)
                and not (isinstance(tool.get("annotations"), dict) and tool["annotations"].get("readOnlyHint") is False),
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
            "received_at": datetime.now(timezone.utc).isoformat(),
            "retention": "No raw response persistence",
            "usage": "Authenticated owner's private educational research only",
        }
        for server, endpoint in NSE_MCP_ENDPOINTS.items()
    ]
