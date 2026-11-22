"""Optional live validation for official NSE MCP read-only research tools.

Discovers the live catalogue, prints tool descriptions and JSON input schemas,
then invokes explicitly allowlisted read-only tools with schema-valid arguments.
It reports response shape and explicit feed timestamps
without persisting or dumping raw market data.
"""
import asyncio
import json
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from rovexquant.market_data.nse_mcp import (
    NseMcpClient,
    NseMcpError,
    extract_source_timestamp,
    safe_tool_catalog,
)

# Explicitly allowlisted read-only data/status tools. Never select arbitrary tools.
CANDIDATES = {
    "bhavcopy": ("get_market_breadth", "get_market_mood", "get_index_snapshot"),
    "cmmkt": ("cm_get_data_status", "cm_get_allstocks_status", "cm_get_live_indices"),
}

# Only fixed, low-impact arguments for explicitly allowlisted read-only tools.
def _today_nse() -> str:
    return datetime.now(ZoneInfo("Asia/Kolkata")).date().isoformat()

# The Bhavcopy tools require an as-of date; use today's ISO date and let NSE
# return its documented nearest available trading day when the market is shut.
def _safe_arguments(server: str, tool_name: str) -> dict[str, Any] | None:
    if server == "bhavcopy" and tool_name in {"get_market_breadth", "get_market_mood"}:
        return {"date": _today_nse()}
    if server == "bhavcopy" and tool_name == "get_index_snapshot":
        return {"date": _today_nse(), "filter": "Nifty 50"}
    if server == "cmmkt" and tool_name in {"cm_get_data_status", "cm_get_allstocks_status", "cm_get_live_indices"}:
        return {}
    return None


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
            # MCP text blocks frequently wrap JSON payloads. Decode locally and
            # report only schema/shape metadata, never actual market values.
            text_blocks = [
                item.get("text") for item in value
                if isinstance(item, dict) and isinstance(item.get("text"), str)
            ]
            if text_blocks:
                nested_shapes = []
                for block in text_blocks[:3]:
                    try:
                        nested_shapes.append(_summarize(json.loads(block)))
                    except ValueError:
                        nested_shapes.append({"type": "text", "characters": len(block)})
                summary["text_payload_shapes"] = nested_shapes
        return summary
    if isinstance(value, str):
        try:
            return _summarize(json.loads(value))
        except ValueError:
            return {"type": "text", "characters": len(value)}
    return {"type": type(value).__name__}


async def _check_once(server: str) -> None:
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
        eligible_names = {item["name"] for item in eligible}
        selected = None
        arguments = None
        for name in CANDIDATES[server]:
            if name not in by_name or name not in eligible_names:
                continue
            candidate_arguments = _safe_arguments(server, name)
            if candidate_arguments is None:
                continue
            required = set(_required_fields(by_name[name]))
            if required.issubset(candidate_arguments):
                selected = by_name[name]
                arguments = candidate_arguments
                break
        if selected is None or arguments is None:
            required_candidates = {
                name: _required_fields(by_name[name])
                for name in CANDIDATES[server]
                if name in by_name
            }
            raise RuntimeError(
                f"No allowlisted tool has supported safe arguments for {server}; "
                f"candidate required fields: {required_candidates}"
            )

        result = await client.call_tool(selected["name"], arguments)
        structured = result.get("structured_content")
        content = result.get("content")
        timestamp = extract_source_timestamp(result)
        print(json.dumps({
            "validation": "tool_call_completed",
            "server": server,
            "tool": selected["name"],
            "argument_names": sorted(arguments),
            "data_mode": "END_OF_DAY" if server == "bhavcopy" else "DELAYED",
            "source_timestamp": timestamp,
            "source_timestamp_status": "explicit_feed_timestamp" if timestamp is not None else "not_supplied",
            "structured_content_shape": _summarize(structured),
            "content_shape": _summarize(content),
        }, sort_keys=True))
        if structured is None and not content:
            raise RuntimeError(f"NSE {server} tool {selected['name']} returned no content")


async def check(server: str) -> None:
    """Retry only transport/temporary upstream failures; schema or permission failures stay hard failures."""
    for attempt in range(3):
        try:
            await _check_once(server)
            return
        except NseMcpError as error:
            if not error.retryable or attempt == 2:
                raise
            delay = 2 ** attempt
            print(f"{server}: transient upstream failure; retrying in {delay}s")
            await asyncio.sleep(delay)


async def main() -> None:
    failures: list[str] = []
    for server in ("bhavcopy", "cmmkt"):
        try:
            await check(server)
        except Exception as error:
            failures.append(f"{server}: {type(error).__name__}: {error}")
            print(f"{server}: validation blocked: {type(error).__name__}: {error}")
    if failures:
        raise SystemExit("; ".join(failures))


if __name__ == "__main__":
    asyncio.run(main())
