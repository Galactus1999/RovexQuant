"""Application services for authenticated, read-only NSE MCP discovery and calls."""
import asyncio
from typing import Any, Literal

from rovexquant.market_data.nse_mcp import (
    NseMcpClient,
    NseMcpError,
    provider_status,
    safe_tool_catalog,
    extract_source_timestamp,
)


def provider_catalog() -> list[dict[str, Any]]:
    return provider_status()


async def discover_tools(
    server: Literal["bhavcopy", "cmmkt", "both"],
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, str]]:
    selected = ["bhavcopy", "cmmkt"] if server == "both" else [server]

    async def discover(server_name: str):
        try:
            async with NseMcpClient(server_name) as client:
                return server_name, safe_tool_catalog(await client.list_tools()), None
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
    server: Literal["bhavcopy", "cmmkt"], tool: str, arguments: dict[str, Any]
) -> dict[str, Any]:
    async with NseMcpClient(server) as client:
        return await client.call_tool(tool, arguments)
