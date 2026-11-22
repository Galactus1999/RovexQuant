import asyncio
import json

import httpx
import pytest

from rovexquant.market_data.nse_mcp import NseMcpClient, NseMcpError, safe_tool_catalog


def _response(request, payload, *, content_type="application/json", session="sess-1"):
    headers = {"content-type": content_type}
    if session:
        headers["Mcp-Session-Id"] = session
    if content_type == "text/event-stream":
        body = "event: message\ndata: " + json.dumps(payload) + "\n\n"
        return httpx.Response(200, headers=headers, text=body)
    return httpx.Response(200, headers=headers, json=payload)


def _handler_for(responses, seen):
    def handler(request):
        body = json.loads(request.content)
        seen.append((body, dict(request.headers)))
        if body["method"] == "initialize":
            return _response(request, {
                "jsonrpc": "2.0", "id": body["id"],
                "result": {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}},
                           "serverInfo": {"name": "NSE", "version": "1"}},
            })
        if body["method"] == "notifications/initialized":
            return httpx.Response(202)
        if body["method"] == "tools/list":
            return _response(request, {"jsonrpc": "2.0", "id": body["id"],
                "result": {"tools": responses["tools"]}})
        if body["method"] == "tools/call":
            return _response(request, {"jsonrpc": "2.0", "id": body["id"],
                "result": responses.get("call", {"content": [{"type": "text", "text": "ok"}]})},
                content_type=responses.get("content_type", "application/json"))
        raise AssertionError(body["method"])
    return handler


def test_streamable_http_initializes_lists_and_calls_read_only_tool():
    seen = []
    transport = httpx.MockTransport(_handler_for({
        "tools": [{"name": "get_bhavcopy", "description": "Read history",
                   "inputSchema": {"type": "object", "properties": {"symbol": {"type": "string"}}},
                   "annotations": {"readOnlyHint": True}}],
        "call": {"content": [{"type": "text", "text": "history"}],
                 "structuredContent": {"rows": [{"close": 10}]}},
    }, seen))
    async def run():
        async with NseMcpClient("bhavcopy", transport=transport) as client:
            tools = await client.list_tools()
            result = await client.call_tool("get_bhavcopy", {"symbol": "RELIANCE"})
            return tools, result
    tools, result = asyncio.run(run())
    assert tools[0]["name"] == "get_bhavcopy"
    assert "mcp-protocol-version" not in seen[0][1]
    assert seen[2][1]["mcp-protocol-version"] == "2025-03-26"
    assert result["structured_content"]["rows"][0]["close"] == 10
    assert any(headers.get("mcp-session-id") == "sess-1" for _, headers in seen[2:])
    assert seen[-1][0]["method"] == "tools/call"


def test_sse_response_is_decoded():
    seen = []
    transport = httpx.MockTransport(_handler_for({
        "tools": [{"name": "lookup_history", "inputSchema": {"type": "object"}}],
        "content_type": "text/event-stream",
    }, seen))
    async def run():
        async with NseMcpClient("bhavcopy", transport=transport) as client:
            return await client.list_tools()
    assert asyncio.run(run())[0]["name"] == "lookup_history"


def test_mutating_tool_names_are_blocked_before_call():
    seen = []
    transport = httpx.MockTransport(_handler_for({
        "tools": [{"name": "place_order", "inputSchema": {"type": "object"}}],
    }, seen))
    async def run():
        async with NseMcpClient("cmmkt", transport=transport) as client:
            await client.call_tool("place_order", {})
    with pytest.raises(NseMcpError, match="read-only"):
        asyncio.run(run())


def test_server_marked_non_read_only_tool_is_blocked():
    seen = []
    transport = httpx.MockTransport(_handler_for({
        "tools": [{"name": "unsafe_research", "inputSchema": {"type": "object"},
                   "annotations": {"readOnlyHint": False}}],
    }, seen))
    async def run():
        async with NseMcpClient("bhavcopy", transport=transport) as client:
            await client.call_tool("unsafe_research", {})
    with pytest.raises(NseMcpError, match="not marked read-only"):
        asyncio.run(run())


def test_tool_error_and_invalid_json_are_not_silently_accepted():
    seen = []
    transport = httpx.MockTransport(_handler_for({
        "tools": [{"name": "get_history", "inputSchema": {"type": "object"},
                   "annotations": {"readOnlyHint": True}}],
        "call": {"isError": True, "content": [{"type": "text", "text": "private detail"}]},
    }, seen))
    async def run():
        async with NseMcpClient("bhavcopy", transport=transport) as client:
            await client.call_tool("get_history", {})
    with pytest.raises(NseMcpError, match="execution error"):
        asyncio.run(run())


def test_safe_catalog_hides_mutating_tools():
    catalog = safe_tool_catalog([
        {"name": "get_quote", "inputSchema": {"type": "object"},
         "annotations": {"readOnlyHint": True}},
        {"name": "delete_history", "inputSchema": {"type": "object"}},
    ])
    assert catalog[0]["read_only"] is True
    assert catalog[1]["read_only"] is False



def test_extract_source_timestamp_only_uses_explicit_source_fields():
    from rovexquant.market_data.nse_mcp import extract_source_timestamp

    assert extract_source_timestamp({
        "structured_content": {"meta": {"source_timestamp": "2026-11-22T10:15:00+05:30"}}
    }) == "2026-11-22T10:15:00+05:30"
    assert extract_source_timestamp({"structured_content": {"retrieved_at": "now"}}) is None
    assert extract_source_timestamp({"structured_content": {"rows": [{"timestamp": "2026-11-22", "source_timestamp": "row-time"}]}}) is None



def test_invalid_json_initialization_response_fails_closed():
    def handler(_request):
        return httpx.Response(200, headers={"content-type": "application/json"}, text="not-json")
    async def run():
        async with NseMcpClient("bhavcopy", transport=httpx.MockTransport(handler)):
            pass
    with pytest.raises(NseMcpError, match="invalid JSON-RPC"):
        asyncio.run(run())


def test_http_rate_limit_is_retryable():
    def handler(_request):
        return httpx.Response(429, json={"message": "rate limited"})
    async def run():
        async with NseMcpClient("cmmkt", transport=httpx.MockTransport(handler)):
            pass
    with pytest.raises(NseMcpError) as error:
        asyncio.run(run())
    assert error.value.retryable is True
    assert "rate limited" not in str(error.value)



def test_unsupported_protocol_version_fails_closed():
    def handler(request):
        body = json.loads(request.content)
        return httpx.Response(200, json={
            "jsonrpc": "2.0", "id": body["id"],
            "result": {"protocolVersion": "2099-01-01", "capabilities": {}, "serverInfo": {}},
        })
    async def run():
        async with NseMcpClient("bhavcopy", transport=httpx.MockTransport(handler)):
            pass
    with pytest.raises(NseMcpError, match="unsupported protocol"):
        asyncio.run(run())



def test_sse_ignores_unrelated_notification_and_matches_request_id():
    def handler(request):
        body = json.loads(request.content)
        if body["method"] == "initialize":
            return _response(request, {
                "jsonrpc": "2.0", "id": body["id"],
                "result": {"protocolVersion": "2025-03-26", "capabilities": {}, "serverInfo": {}},
            })
        if body["method"] == "notifications/initialized":
            return httpx.Response(202)
        if body["method"] == "tools/list":
            notification = {"jsonrpc": "2.0", "method": "notifications/progress", "params": {"progress": 1}}
            result = {"jsonrpc": "2.0", "id": body["id"], "result": {"tools": [{"name": "get_quote"}]}}
            stream = "event: message\ndata: " + json.dumps(notification) + "\n\n"
            stream += "event: message\ndata: " + json.dumps(result) + "\n\n"
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, text=stream)
        raise AssertionError(body["method"])

    async def run():
        async with NseMcpClient("bhavcopy", transport=httpx.MockTransport(handler)) as client:
            return await client.list_tools()

    assert asyncio.run(run())[0]["name"] == "get_quote"



def test_tool_catalog_paginates_and_carries_session():
    requests = []

    def handler(request):
        body = json.loads(request.content)
        requests.append((body, dict(request.headers)))
        if body["method"] == "initialize":
            return _response(request, {
                "jsonrpc": "2.0", "id": body["id"],
                "result": {"protocolVersion": "2025-03-26", "capabilities": {}, "serverInfo": {}},
            })
        if body["method"] == "notifications/initialized":
            return httpx.Response(202)
        if body["method"] == "tools/list":
            if body.get("params", {}).get("cursor") == "page-2":
                tools = [{"name": "get_second"}]
                result = {"tools": tools}
            else:
                result = {"tools": [{"name": "get_first"}], "nextCursor": "page-2"}
            return _response(request, {"jsonrpc": "2.0", "id": body["id"], "result": result})
        raise AssertionError(body["method"])

    async def run():
        async with NseMcpClient("bhavcopy", transport=httpx.MockTransport(handler)) as client:
            return await client.list_tools()

    tools = asyncio.run(run())
    assert [tool["name"] for tool in tools] == ["get_first", "get_second"]
    assert requests[-1][0]["params"]["cursor"] == "page-2"
    assert requests[-1][1].get("mcp-session-id") == "sess-1"


def test_http_timeout_is_retryable():
    def handler(_request):
        raise httpx.ReadTimeout("upstream timeout")

    async def run():
        async with NseMcpClient("cmmkt", transport=httpx.MockTransport(handler)):
            pass

    with pytest.raises(NseMcpError) as error:
        asyncio.run(run())
    assert error.value.retryable is True
    assert "timed out" in str(error.value).lower()



def test_tool_without_positive_read_only_annotation_is_denied():
    seen = []
    transport = httpx.MockTransport(_handler_for({
        "tools": [{"name": "get_history", "inputSchema": {"type": "object"}}],
    }, seen))

    async def run():
        async with NseMcpClient("bhavcopy", transport=transport) as client:
            await client.call_tool("get_history", {})

    with pytest.raises(NseMcpError, match="readOnlyHint must be true"):
        asyncio.run(run())
    assert not any(body["method"] == "tools/call" for body, _ in seen)


def test_tool_arguments_are_validated_against_published_schema():
    seen = []
    transport = httpx.MockTransport(_handler_for({
        "tools": [{
            "name": "get_history",
            "inputSchema": {
                "type": "object",
                "properties": {"symbol": {"type": "string"}},
                "required": ["symbol"],
                "additionalProperties": False,
            },
            "annotations": {"readOnlyHint": True},
        }],
    }, seen))

    async def run():
        async with NseMcpClient("bhavcopy", transport=transport) as client:
            await client.call_tool("get_history", {"symbol": 123})

    with pytest.raises(NseMcpError, match="arguments do not match"):
        asyncio.run(run())
    assert not any(body["method"] == "tools/call" for body, _ in seen)


def test_remote_schema_references_are_rejected_without_fetching():
    seen = []
    transport = httpx.MockTransport(_handler_for({
        "tools": [{
            "name": "get_history",
            "inputSchema": {
                "type": "object",
                "properties": {"symbol": {"$ref": "https://example.invalid/schema.json"}},
            },
            "annotations": {"readOnlyHint": True},
        }],
    }, seen))

    async def run():
        async with NseMcpClient("bhavcopy", transport=transport) as client:
            await client.call_tool("get_history", {"symbol": "RELIANCE"})

    with pytest.raises(NseMcpError, match="references are not supported"):
        asyncio.run(run())
    assert not any(body["method"] == "tools/call" for body, _ in seen)


def test_safe_catalog_marks_missing_read_only_hint_as_not_read_only():
    catalog = safe_tool_catalog([
        {"name": "unknown_tool", "inputSchema": {"type": "object"}},
    ])
    assert catalog[0]["read_only"] is False


def test_streamed_mcp_response_limit_is_enforced_before_json_decode():
    def handler(request):
        body = json.loads(request.content)
        if body["method"] == "initialize":
            return _response(request, {
                "jsonrpc": "2.0", "id": body["id"],
                "result": {"protocolVersion": "2025-03-26", "capabilities": {}, "serverInfo": {}},
            })
        if body["method"] == "notifications/initialized":
            return httpx.Response(202)
        if body["method"] == "tools/list":
            return httpx.Response(
                200,
                headers={"content-type": "application/json"},
                content=b" " * (10_000_001),
            )
        raise AssertionError(body["method"])

    async def run():
        async with NseMcpClient("bhavcopy", transport=httpx.MockTransport(handler)) as client:
            await client.list_tools()

    with pytest.raises(NseMcpError, match="10 MB safety limit"):
        asyncio.run(run())


def test_oversized_remote_tool_schema_is_rejected_before_invocation():
    seen = []
    transport = httpx.MockTransport(_handler_for({
        "tools": [{
            "name": "get_history",
            "inputSchema": {"type": "object", "description": "x" * (128 * 1024 + 1)},
            "annotations": {"readOnlyHint": True},
        }],
    }, seen))

    async def run():
        async with NseMcpClient("bhavcopy", transport=transport) as client:
            await client.call_tool("get_history", {})

    with pytest.raises(NseMcpError, match="128 KB safety limit"):
        asyncio.run(run())
    assert not any(body["method"] == "tools/call" for body, _ in seen)


def test_remote_json_rpc_error_text_is_not_reflected():
    remote_secret = "REMOTE_ERROR_SECRET_SHOULD_NOT_ESCAPE"

    def handler(request):
        body = json.loads(request.content)
        if body["method"] == "initialize":
            return _response(request, {
                "jsonrpc": "2.0", "id": body["id"],
                "result": {"protocolVersion": "2025-03-26", "capabilities": {}, "serverInfo": {}},
            })
        if body["method"] == "notifications/initialized":
            return httpx.Response(202)
        if body["method"] == "tools/list":
            return _response(request, {
                "jsonrpc": "2.0", "id": body["id"],
                "result": {"tools": [{
                    "name": "get_history",
                    "inputSchema": {"type": "object"},
                    "annotations": {"readOnlyHint": True},
                }]},
            })
        if body["method"] == "tools/call":
            return _response(request, {
                "jsonrpc": "2.0", "id": body["id"],
                "error": {"code": -32000, "message": remote_secret},
            })
        raise AssertionError(body["method"])

    async def run():
        async with NseMcpClient("bhavcopy", transport=httpx.MockTransport(handler)) as client:
            await client.call_tool("get_history", {})

    with pytest.raises(NseMcpError) as error:
        asyncio.run(run())
    assert remote_secret not in str(error.value)


def test_gzip_encoded_mcp_response_is_decompressed_exactly_once():
    import gzip

    def handler(request):
        message = json.loads(request.content)
        if message["method"] == "initialize":
            return _response(request, {
                "jsonrpc": "2.0", "id": message["id"],
                "result": {"protocolVersion": "2025-03-26", "capabilities": {},
                           "serverInfo": {"name": "NSE", "version": "1"}},
            })
        if message["method"] == "notifications/initialized":
            return httpx.Response(202)
        if message["method"] == "tools/list":
            payload = json.dumps({
                "jsonrpc": "2.0",
                "id": message["id"],
                "result": {"tools": [{
                    "name": "get_market_breadth",
                    "inputSchema": {"type": "object"},
                    "annotations": {"readOnlyHint": True},
                }]},
            }).encode("utf-8")
            return httpx.Response(
                200,
                headers={
                    "content-type": "application/json",
                    "content-encoding": "gzip",
                    "content-length": str(len(gzip.compress(payload))),
                    "Mcp-Session-Id": "gzip-session",
                },
                content=gzip.compress(payload),
            )
        raise AssertionError(message["method"])

    transport = httpx.MockTransport(handler)

    async def run():
        async with NseMcpClient("bhavcopy", transport=transport) as client:
            return await client.list_tools()

    tools = asyncio.run(run())
    assert tools[0]["name"] == "get_market_breadth"
