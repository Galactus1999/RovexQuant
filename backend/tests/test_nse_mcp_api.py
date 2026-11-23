from fastapi.testclient import TestClient

from rovexquant.api import main as api
from rovexquant.auth import issue_app_session
from rovexquant.market_data.nse_mcp import NseMcpError
from rovexquant.market_data import nse_mcp_service


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

    monkeypatch.setattr(nse_mcp_service, "NseMcpClient", FakeClient)
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

    monkeypatch.setattr(nse_mcp_service, "NseMcpClient", FakeClient)
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

    monkeypatch.setattr(nse_mcp_service, "NseMcpClient", FakeClient)
    response = TestClient(api.app).post(
        "/api/v1/app/research/nse-mcp/call",
        json={"server": "bhavcopy", "tool": "get_history", "arguments": {}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "upstream_error"
    assert response.json()["request_id"] == response.headers["X-Request-ID"]
    assert "tool schema references" in response.json()["detail"]


def test_extracted_eod_research_routes_require_app_session():
    client = TestClient(api.app)
    assert client.get("/api/v1/app/research/market-data/providers").status_code == 401
    assert client.get(
        "/api/v1/app/research/market-data/ohlcv/nse/RELIANCE?from=2025-01-01&to=2025-01-31"
    ).status_code == 401
    assert client.get("/api/v1/app/research/market-data/status").status_code == 401


def test_dalalos_credential_routes_require_app_session():
    client = TestClient(api.app)
    assert client.get("/api/v1/app/integrations/research/dalalos/status").status_code == 401
    assert client.put(
        "/api/v1/app/integrations/research/dalalos/api-key",
        json={"api_key": "d" * 32},
    ).status_code == 401
    assert client.delete("/api/v1/app/integrations/research/dalalos/api-key").status_code == 401


def test_dalalos_credential_is_encrypted_and_never_returned(monkeypatch, tmp_path):
    from cryptography.fernet import Fernet
    from rovexquant.market_data.upstox_credentials import get_research_connector_api_key

    token = _session(monkeypatch)
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", Fernet.generate_key().decode("ascii"))
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "sqlite")
    monkeypatch.setenv("QUANT_TOKEN_DB_PATH", str(tmp_path / "quant-vault.sqlite3"))
    client = TestClient(api.app)
    headers = {"Authorization": f"Bearer {token}"}

    saved = client.put(
        "/api/v1/app/integrations/research/dalalos/api-key",
        json={"api_key": "d" * 32},
        headers=headers,
    )
    assert saved.status_code == 200
    assert saved.json()["credential_storage"] == "encrypted_server_side"
    assert "api_key" not in saved.text
    assert get_research_connector_api_key("dalalos") == "d" * 32

    status = client.get("/api/v1/app/integrations/research/dalalos/status", headers=headers)
    assert status.status_code == 200
    assert status.json()["configured"] is True
    assert "d" * 32 not in status.text

    deleted = client.delete("/api/v1/app/integrations/research/dalalos/api-key", headers=headers)
    assert deleted.status_code == 200
    assert deleted.json()["deleted"] is True
    assert get_research_connector_api_key("dalalos") is None


def test_dalalos_tools_route_is_authenticated_and_labels_manual_quota_tools(monkeypatch):
    token = _session(monkeypatch)

    async def fake_discover(server):
        assert server == "dalalos"
        return {"dalalos": [
            {"name": "get_company_exposures", "read_only": True, "requires_confirmation": False},
            {"name": "get_filing_extract", "read_only": False, "requires_confirmation": True},
        ]}, {}

    monkeypatch.setattr(nse_mcp_service, "discover_tools", fake_discover)
    client = TestClient(api.app)
    assert client.get("/api/v1/app/research/nse-mcp/tools?server=dalalos").status_code == 401
    response = client.get(
        "/api/v1/app/research/nse-mcp/tools?server=dalalos",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    catalog = response.json()["catalog"]["dalalos"]
    assert catalog[0]["read_only"] is True
    assert catalog[1]["requires_confirmation"] is True


def test_dalalos_tool_call_passes_explicit_quota_confirmation(monkeypatch):
    token = _session(monkeypatch)
    calls = []

    async def fake_invoke(server, tool, arguments, *, confirm_non_read_only=False):
        calls.append((server, tool, arguments, confirm_non_read_only))
        return {"tool": tool, "content": [{"type": "text", "text": "filing facts"}]}

    monkeypatch.setattr(nse_mcp_service, "invoke_tool", fake_invoke)
    response = TestClient(api.app).post(
        "/api/v1/app/research/nse-mcp/call",
        json={
            "server": "dalalos",
            "tool": "get_filing_extract",
            "arguments": {"query": "RELIANCE", "news_id": "filing-1"},
            "confirm_non_read_only": True,
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    assert response.json()["data_mode"] == "SOURCE_ATTRIBUTED_RESEARCH"
    assert calls == [("dalalos", "get_filing_extract", {"query": "RELIANCE", "news_id": "filing-1"}, True)]
