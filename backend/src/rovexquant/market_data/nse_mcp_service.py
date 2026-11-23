"""Application services for authenticated, read-only NSE MCP discovery and calls."""
import asyncio
from typing import Any, Literal

from rovexquant.market_data.nse_mcp import (
    NseMcpClient,
    NseMcpError,
    provider_status,
    safe_tool_catalog,
    extract_source_timestamp as extract_source_timestamp,
)
from rovexquant.market_data.upstox_credentials import get_research_connector_api_key


def provider_catalog() -> list[dict[str, Any]]:
    return provider_status() + [{
        "provider_id": "dalalos_mcp",
        "configured": get_research_connector_api_key("dalalos") is not None,
        "endpoint": "https://mcp.dalalos.in/mcp",
        "mode": "SOURCE_ATTRIBUTED_RESEARCH",
        "expected_delay": "Dataset-specific; check as_of, cached_at and stale fields",
        "connection_status": "not_checked",
        "authentication": "Owner-managed API key stored in the existing encrypted vault",
        "retention": "No raw MCP response persistence by RovexQuant",
        "usage": "Personal research; respect DalalOS plan quotas and source attribution",
    }]


async def discover_tools(
    server: Literal["bhavcopy", "cmmkt", "dalalos", "both"],
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, str]]:
    selected = ["bhavcopy", "cmmkt", "dalalos"] if server == "both" else [server]

    async def discover(server_name: str):
        try:
            api_key = get_research_connector_api_key("dalalos") if server_name == "dalalos" else None
            async with NseMcpClient(server_name, api_key=api_key) as client:
                return server_name, safe_tool_catalog(await client.list_tools(), server_name), None
        except NseMcpError as error:
            return server_name, None, str(error)

    discoveries = await asyncio.gather(*(discover(server_name) for server_name in selected))
    catalog: dict[str, list[dict[str, Any]]] = {}
    failures: dict[str, str] = {}
    for server_name, tools, failure in discoveries:
        if tools is not None:
            catalog[server_name] = tools
        if failure is not None:
            failures[server_name] = failure
    return catalog, failures


async def invoke_tool(
    server: Literal["bhavcopy", "cmmkt", "dalalos"],
    tool: str,
    arguments: dict[str, Any],
    *,
    confirm_non_read_only: bool = False,
) -> dict[str, Any]:
    api_key = get_research_connector_api_key("dalalos") if server == "dalalos" else None
    async with NseMcpClient(server, api_key=api_key) as client:
        return await client.call_tool(
            tool, arguments, confirm_non_read_only=confirm_non_read_only
        )
