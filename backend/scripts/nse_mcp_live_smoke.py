"""Optional live connectivity smoke test for official NSE MCP endpoints.

This verifies public MCP initialization and tool discovery only. It does not
persist market data or invoke market-data tools.
Run from backend after installing the package:
    python scripts/nse_mcp_live_smoke.py
"""
import asyncio

from rovexquant.market_data.nse_mcp import NseMcpClient, safe_tool_catalog


async def check(server: str) -> None:
    async with NseMcpClient(server, timeout=25.0) as client:
        tools = safe_tool_catalog(await client.list_tools())
    if not tools:
        raise RuntimeError(f"NSE {server} MCP returned an empty tool catalogue")
    eligible = [tool for tool in tools if tool["read_only"]]
    print(f"{server}: connected; {len(tools)} tools discovered; {len(eligible)} read-only candidates")
    for tool in tools:
        print(f"  - {tool['name']} [read_only={tool['read_only']}]")
    if not eligible:
        raise RuntimeError(f"NSE {server} MCP exposed no eligible read-only tools")


async def main() -> None:
    for server in ("bhavcopy", "cmmkt"):
        await check(server)


if __name__ == "__main__":
    asyncio.run(main())
