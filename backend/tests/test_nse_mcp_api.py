from fastapi.testclient import TestClient

from rovexquant.api import main as api
from rovexquant.auth import issue_app_session
from rovexquant.market_data.nse_mcp import NseMcpError


def _session(monkeypatch):
    monkeypatch.setenv("QUANT_APP_USERNAME", "quant-test")
    monkeypatch.setenv("QUANT_APP_PASSWORD", "test-password")
    monkeypatch.setenv("QUANT_APP_SESSION_SIGNING_KEY", "test-signing-key-that-is-long-enough-123")
    return issue_app_session("quant-test", "test-password")[0]


def test_nse_mcp_routes_require_app_session():
    client = TestClient(api.app)
    assert client.get("/api/v1/app/research/nse-mcp/providers").status_code == 401
    assert client.get("/api/v1/app/research/nse-mcp/tools").status_code == 401
    assert client.post("/api/v1/app/research/nse-mcp/call", json={
        "server": "bhavcopy", "tool": "get_history", "arguments": {},
    }).status_code == 401


def test_nse_mcp_catalog_is_authenticated_and_labels_both_sources(monkeypatch):
    token = _session(monkeypatch)

    class FakeClient:
        def __init__(self, server):
            self.server = server
        async def __aenter__(self):
            return self
        async def __aexit__(self, *_):
            return None
        async def list_tools(self):
            return [{"name": "get_history", "inputSchema": {"type": "object"},
                     "annotations": {"readOnlyHint": True}}]

    monkeypatch.setattr(api, "NseMcpClient", FakeClient)
    response = TestClient(api.app).get(
        "/api/v1/app/research/nse-mcp/tools",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["catalog"]["bhavcopy"][0]["name"] == "get_history"
    assert body["catalog"]["cmmkt"][0]["read_only"] is True


def test_nse_mcp_call_labels_delayed_mode_and_does_not_claim_source_timestamp(monkeypatch):
    token = _session(monkeypatch)

    class FakeClient:
        def __init__(self, server):
            self.server = server
        async def __aenter__(self):
            return self
        async def __aexit__(self, *_):
            return None
        async def call_tool(self, name, arguments):
            assert name == "get_quote"
            assert arguments == {"symbol": "RELIANCE"}
            return {"tool": name, "content": [{"type": "text", "text": "quote"}],
                    "structured_content": {"price": 100, "source_timestamp": "2026-11-22T10:15:00+05:30"}}

    monkeypatch.setattr(api, "NseMcpClient", FakeClient)
    response = TestClient(api.app).post(
        "/api/v1/app/research/nse-mcp/call",
        json={"server": "cmmkt", "tool": "get_quote", "arguments": {"symbol": "RELIANCE"}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["data_mode"] == "DELAYED"
    assert body["source_timestamp"] == "2026-11-22T10:15:00+05:30"
    assert body["result"]["structured_content"]["price"] == 100



def test_nse_mcp_arguments_are_size_bounded(monkeypatch):
    token = _session(monkeypatch)
    response = TestClient(api.app).post(
        "/api/v1/app/research/nse-mcp/call",
        json={"server": "bhavcopy", "tool": "get_history", "arguments": {"payload": "x" * 40000}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 422

def test_nse_mcp_remote_schema_failure_uses_safe_structured_error(monkeypatch):
    token = _session(monkeypatch)

    class FakeClient:
        def __init__(self, _server):
            pass
        async def __aenter__(self):
            return self
        async def __aexit__(self, *_):
            return None
        async def call_tool(self, _name, _arguments):
            raise NseMcpError("NSE MCP tool schema references are not supported", 502)

    monkeypatch.setattr(api, "NseMcpClient", FakeClient)
    response = TestClient(api.app).post(
        "/api/v1/app/research/nse-mcp/call",
        json={"server": "bhavcopy", "tool": "get_history", "arguments": {}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "upstream_error"
    assert response.json()["request_id"] == response.headers["X-Request-ID"]
    assert "tool schema references" in response.json()["detail"]
