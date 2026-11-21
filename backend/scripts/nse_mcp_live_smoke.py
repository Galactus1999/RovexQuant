"""Optional live validation for official NSE MCP read-only research tools.

Discovers the live catalogue, prints tool descriptions and JSON input schemas,
then invokes one explicitly allowlisted, zero-required-argument status/market
summary tool per source. It reports response shape and explicit feed timestamps
without persisting or dumping raw market data.
"""
import asyncio
import json
from typing import Any

from rovexquant.market_data.nse_mcp import (
    NseMcpClient,
    extract_source_timestamp,
    safe_tool_catalog,
)

# Explicitly allowlisted read-only data/status tools. Never select arbitrary tools.
CANDIDATES = {
    "bhavcopy": ("get_market_mood", "get_market_breadth", "get_index_snapshot"),
    "cmmkt": ("cm_get_data_status", "cm_get_allstocks_status", "cm_get_live_indices"),
}


def _required_fields(tool: dict[str, Any]) -> list[str]:
    schema = tool.get("inputSchema")
    if not isinstance(schema, dict):
        return []
    required = schema.get("required", [])
    return [name for name in required if isinstance(name, str)] if isinstance(required, list) else []


def _summarize(value: Any) -> dict[str, Any]:
    """Return shape-only diagnostics; never print market-data values."""
    if isinstance(value, dict):
        summary: dict[str, Any] = {"type": "object", "keys": sorted(str(k) for k in value.keys())[:80]}
        for key, item in value.items():
            if isinstance(item, list):
                summary["list_field"] = key
                summary["list_count"] = len(item)
                if item and isinstance(item[0], dict):
                    summary["first_item_keys"] = sorted(str(k) for k in item[0].keys())[:80]
                break
        return summary
    if isinstance(value, list):
        summary = {"type": "array", "count": len(value)}
        if value and isinstance(value[0], dict):
            summary["first_item_keys"] = sorted(str(k) for k in value[0].keys())[:80]
        return summary
    if isinstance(value, str):
        try:
            return _summarize(json.loads(value))
        except ValueError:
            return {"type": "text", "characters": len(value)}
    return {"type": type(value).__name__}


async def check(server: str) -> None:
    async with NseMcpClient(server, timeout=25.0) as client:
        raw_tools = await client.list_tools()
        tools = safe_tool_catalog(raw_tools)
        if not tools:
            raise RuntimeError(f"NSE {server} MCP returned an empty tool catalogue")

        eligible = [tool for tool in tools if tool["read_only"]]
        print(f"{server}: connected; {len(tools)} tools discovered; {len(eligible)} read-only candidates")
        for tool in raw_tools:
            print(json.dumps({
                "tool": tool.get("name"),
                "description": str(tool.get("description", ""))[:500],
                "inputSchema": tool.get("inputSchema", {}),
                "annotations": tool.get("annotations", {}),
            }, sort_keys=True))

        by_name = {tool.get("name"): tool for tool in raw_tools}
        selected = next(
            (
                by_name[name]
                for name in CANDIDATES[server]
                if name in by_name
                and name in {item["name"] for item in eligible}
                and not _required_fields(by_name[name])
            ),
            None,
        )
        if selected is None:
            required_candidates = {
                name: _required_fields(by_name[name])
                for name in CANDIDATES[server]
                if name in by_name
            }
            raise RuntimeError(
                f"No allowlisted zero-required-argument tool available for {server}; "
                f"candidate required fields: {required_candidates}"
            )

        result = await client.call_tool(selected["name"], {})
        structured = result.get("structured_content")
        content = result.get("content")
        timestamp = extract_source_timestamp(result)
        print(json.dumps({
            "validation": "tool_call_completed",
            "server": server,
            "tool": selected["name"],
            "data_mode": "END_OF_DAY" if server == "bhavcopy" else "DELAYED",
            "source_timestamp": timestamp,
            "source_timestamp_status": "explicit_feed_timestamp" if timestamp is not None else "not_supplied",
            "structured_content_shape": _summarize(structured),
            "content_shape": _summarize(content),
        }, sort_keys=True))
        if structured is None and not content:
            raise RuntimeError(f"NSE {server} tool {selected['name']} returned no content")


async def main() -> None:
    for server in ("bhavcopy", "cmmkt"):
        await check(server)


if __name__ == "__main__":
    asyncio.run(main())
