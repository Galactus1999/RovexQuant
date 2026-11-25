from datetime import datetime, timezone

from fastapi.testclient import TestClient

from rovexquant.api.main import app
from rovexquant.api.routers import market as market_routes
from rovexquant.auth import issue_app_session
from rovexquant.market_data import service
from rovexquant.market_data.providers import MarketQuote


def test_app_quote_snapshot_resolves_company_name_and_preserves_52_week_range(monkeypatch):
    monkeypatch.setenv("QUANT_APP_USERNAME", "quant-user")
    monkeypatch.setenv("QUANT_APP_PASSWORD", "long-test-password")
    monkeypatch.setenv("QUANT_APP_SESSION_SIGNING_KEY", "s" * 48)

    async def resolve(symbol):
        assert symbol == "Reliance Industries"
        return "NSE_EQ|INE002A01018"

    class FakeProvider:
        provider_id = "upstox"
        configured = True

        async def get_quotes(self, instruments):
            assert instruments[0].upstox_instrument_key == "NSE_EQ|INE002A01018"
            now = datetime.now(timezone.utc)
            return [MarketQuote(
                symbol=instruments[0].symbol,
                provider="upstox",
                last_price=1500.0,
                previous_close=1490.0,
                net_change=10.0,
                change_percent=10.0 / 1490.0 * 100,
                volume=1000,
                exchange_timestamp=now,
                received_at=now,
                quality="VALID",
                year_high=1600.0,
                year_low=1100.0,
            )]

    monkeypatch.setattr(market_routes, "resolve_nse_instrument_key", resolve)
    monkeypatch.setattr(service, "build_providers", lambda: {"upstox": FakeProvider()})
    token, _ = issue_app_session("quant-user", "long-test-password")
    response = TestClient(app).post(
        "/api/v1/app/market/quotes",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "provider_order": ["upstox"],
            "instruments": [{"symbol": "Reliance Industries"}],
        },
    )
    assert response.status_code == 200
    quote = response.json()["quotes"][0]
    assert quote["year_high"] == 1600.0
    assert quote["year_low"] == 1100.0
    assert quote["quality"] == "VALID"
