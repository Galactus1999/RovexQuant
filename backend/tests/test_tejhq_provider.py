import asyncio
from datetime import date

import httpx
import pytest

from rovexquant.market_data.tejhq import TejHqEodProvider, TejHqError


def sample_payload():
    return {"data": [{
        "date": "2025-05-28", "open": 1420.1, "high": 1422.6,
        "low": 1410.7, "close": 1412.9, "last": 1412.8,
        "prev_close": 1422.1, "volume": 5944611,
        "turnover": 8411575745.5, "trades": 169185,
        "adj_factor_cumulative": 0.991438, "adj_close": 1400.8,
    }], "meta": {"exchange": "NSE", "symbol": "RELIANCE", "count": 1}}


def test_keyless_ohlcv_uses_documented_endpoint_and_normalizes_eod_bar():
    def handler(request):
        assert request.url.path == "/v1/ohlcv/nse/RELIANCE"
        assert request.url.params["from"] == "2025-05-28"
        assert request.url.params["to"] == "2025-05-28"
        assert "Authorization" not in request.headers
        return httpx.Response(200, json=sample_payload())

    provider = TejHqEodProvider(transport=httpx.MockTransport(handler))
    bars = asyncio.run(provider.get_ohlcv(
        "NSE", "reliance", date(2025, 5, 28), date(2025, 5, 28)
    ))
    assert len(bars) == 1
    assert bars[0].date == date(2025, 5, 28)
    assert bars[0].close == 1412.9
    assert bars[0].volume == 5944611
    assert bars[0].trades == 169185


def test_adjusted_history_requires_free_key_and_sends_bearer_header():
    provider = TejHqEodProvider()
    with pytest.raises(TejHqError, match="optional free API key") as error:
        asyncio.run(provider.get_ohlcv(
            "NSE", "RELIANCE", date(2025, 5, 28), date(2025, 5, 28), adjusted=True
        ))
    assert error.value.status_code == 503

    def handler(request):
        assert request.url.path == "/v1/adjusted/nse/RELIANCE"
        assert request.headers["Authorization"] == "Bearer free-key"
        return httpx.Response(200, json=sample_payload())

    keyed = TejHqEodProvider("free-key", transport=httpx.MockTransport(handler))
    adjusted_bars = asyncio.run(keyed.get_ohlcv(
        "NSE", "RELIANCE", date(2025, 5, 28), date(2025, 5, 28), adjusted=True
    ))
    assert len(adjusted_bars) == 1
    assert adjusted_bars[0].close == 1412.9
    assert adjusted_bars[0].adjusted_close == 1400.8
    assert adjusted_bars[0].adjustment_factor == 0.991438


def test_delivery_requires_key_but_snapshot_and_actions_are_keyless():
    seen = []

    def handler(request):
        seen.append((request.url.path, request.headers.get("Authorization")))
        if "/delivery/" in request.url.path:
            return httpx.Response(200, json={"data": [{"date": "2025-05-28", "delivery_pct": 42.0}]})
        if "/snapshot/" in request.url.path:
            return httpx.Response(200, json={"data": [{"symbol": "RELIANCE", "close": 100.0}]})
        return httpx.Response(200, json={"data": [{"symbol": "RELIANCE", "type": "dividend"}]})

    provider = TejHqEodProvider("free-key", transport=httpx.MockTransport(handler))
    delivery = asyncio.run(provider.get_delivery(
        "NSE", "RELIANCE", date(2025, 5, 28), date(2025, 5, 28)
    ))
    snapshot = asyncio.run(provider.get_snapshot("NSE", date(2025, 5, 28)))
    actions = asyncio.run(provider.get_actions("RELIANCE"))
    assert delivery[0]["delivery_pct"] == 42.0
    assert snapshot[0]["symbol"] == "RELIANCE"
    assert actions[0]["type"] == "dividend"
    assert seen[0][1] == "Bearer free-key"
    assert seen[1][1] is None
    assert seen[2][1] is None


@pytest.mark.parametrize("exchange,symbol", [
    ("BSE", "RELIANCE/FOO"), ("NYSE", "ABC"), ("NSE", "../secret"),
])
def test_rejects_invalid_exchange_or_symbol(exchange, symbol):
    provider = TejHqEodProvider()
    with pytest.raises(TejHqError):
        asyncio.run(provider.get_ohlcv(
            exchange, symbol, date(2025, 1, 1), date(2025, 1, 2)
        ))


def test_rejects_invalid_date_ranges_and_bad_market_rows():
    provider = TejHqEodProvider()
    with pytest.raises(TejHqError, match="on or before"):
        asyncio.run(provider.get_ohlcv(
            "NSE", "RELIANCE", date(2025, 1, 2), date(2025, 1, 1)
        ))

    def bad_handler(_request):
        payload = sample_payload()
        payload["data"][0]["high"] = 100
        return httpx.Response(200, json=payload)

    bad = TejHqEodProvider(transport=httpx.MockTransport(bad_handler))
    with pytest.raises(TejHqError, match="inconsistent OHLC"):
        asyncio.run(bad.get_ohlcv(
            "NSE", "RELIANCE", date(2025, 5, 28), date(2025, 5, 28)
        ))


def test_rate_limit_is_reported_as_retryable_without_secret_leak():
    def handler(_request):
        return httpx.Response(429, json={"message": "rate limited"})
    provider = TejHqEodProvider("private-key", transport=httpx.MockTransport(handler))
    with pytest.raises(TejHqError) as error:
        asyncio.run(provider.get_ohlcv(
            "NSE", "RELIANCE", date(2025, 1, 1), date(2025, 1, 2)
        ))
    assert error.value.retryable is True
    assert "private-key" not in str(error.value)


def test_safe_status_never_exposes_free_key():
    status = TejHqEodProvider("secret-key").safe_status()
    assert status["configured"] is True
    assert status["free_key_configured"] is True
    assert "secret-key" not in str(status)


def test_rejects_rows_outside_requested_history_window():
    provider = TejHqEodProvider(transport=httpx.MockTransport(
        lambda _request: httpx.Response(200, json=sample_payload())
    ))
    with pytest.raises(TejHqError, match="outside the requested date range"):
        asyncio.run(provider.get_ohlcv(
            "NSE", "RELIANCE", date(2025, 1, 1), date(2025, 1, 2)
        ))


def test_fetch_latest_snapshot_skips_non_trading_days(monkeypatch):
    from rovexquant.market_data import tejhq_service

    calls = []

    class FakeProvider:
        provider_id = "tejhq_eod"

        async def get_snapshot(self, exchange, trading_date):
            calls.append((exchange, trading_date))
            if trading_date == date(2026, 11, 20):
                return [{"symbol": "RELIANCE", "series": "EQ", "close": 1400, "prev_close": 1390, "volume": 100}]
            return []

    provider = FakeProvider()
    monkeypatch.setattr(
        tejhq_service.TejHqEodProvider,
        "from_environment",
        classmethod(lambda _cls: provider),
    )
    provider_id, trading_date, rows = asyncio.run(
        tejhq_service.fetch_latest_snapshot(
            "nse", as_of=date(2026, 11, 22), max_lookback_days=10
        )
    )

    assert provider_id == "tejhq_eod"
    assert trading_date == date(2026, 11, 20)
    assert rows[0]["symbol"] == "RELIANCE"
    assert [day for _exchange, day in calls] == [
        date(2026, 11, 22), date(2026, 11, 21), date(2026, 11, 20)
    ]


def test_fetch_latest_snapshot_stops_on_transient_provider_failure(monkeypatch):
    from rovexquant.market_data import tejhq_service

    calls = []

    class FakeProvider:
        provider_id = "tejhq_eod"

        async def get_snapshot(self, _exchange, _trading_date):
            calls.append(1)
            raise TejHqError("provider temporarily unavailable", 503, retryable=True)

    monkeypatch.setattr(
        tejhq_service.TejHqEodProvider,
        "from_environment",
        classmethod(lambda _cls: FakeProvider()),
    )
    with pytest.raises(TejHqError, match="temporarily unavailable") as failure:
        asyncio.run(
            tejhq_service.fetch_latest_snapshot(
                "nse", as_of=date(2026, 11, 22), max_lookback_days=10
            )
        )
    assert failure.value.retryable is True
    assert len(calls) == 3


def test_summarize_snapshot_rows_uses_only_verified_equity_close_fields():
    from rovexquant.market_data.tejhq_service import summarize_snapshot_rows

    rows = [
        {"symbol": "GAIN", "name": "Gainer Ltd", "series": "EQ", "close": 110, "prev_close": 100, "volume": 1000},
        {"symbol": "LOSS", "series": "EQ", "close": 90, "prev_close": 100, "volume": 2000},
        {"symbol": "FLAT", "series": "EQ", "close": 100, "prev_close": 100, "volume": 3000},
        {"symbol": "BEONLY", "series": "BE", "close": 120, "prev_close": 100, "volume": 9000},
        {"symbol": "INVALID", "series": "EQ", "close": 100, "prev_close": 0, "volume": 0},
    ]
    summary = summarize_snapshot_rows(rows, limit=5)

    assert summary["total_rows"] == 5
    assert summary["equity_series_rows"] == 4
    assert summary["priced_symbols"] == 3
    assert (summary["advances"], summary["declines"], summary["unchanged"]) == (1, 1, 1)
    assert summary["top_gainers"][0]["symbol"] == "GAIN"
    assert summary["top_losers"][0]["symbol"] == "LOSS"
    assert summary["most_active_by_volume"][0]["symbol"] == "FLAT"


def test_fetch_latest_snapshot_skips_documented_empty_date_http_400_mapping(monkeypatch):
    from rovexquant.market_data import tejhq_service

    calls = []

    class FakeProvider:
        provider_id = "tejhq_eod"

        async def get_snapshot(self, _exchange, trading_date):
            calls.append(trading_date)
            if trading_date == date(2026, 11, 22):
                # The provider adapter maps upstream HTTP 400 to a non-retryable 422.
                raise TejHqError("TejHQ rejected the request (HTTP 400)", 422)
            if trading_date == date(2026, 11, 21):
                return [{"symbol": "RELIANCE", "series": "EQ", "close": 100}]
            return []

    monkeypatch.setattr(
        tejhq_service.TejHqEodProvider,
        "from_environment",
        classmethod(lambda _cls: FakeProvider()),
    )
    provider_id, trading_date, rows = asyncio.run(
        tejhq_service.fetch_latest_snapshot(
            "nse", as_of=date(2026, 11, 22), max_lookback_days=2
        )
    )
    assert provider_id == "tejhq_eod"
    assert trading_date == date(2026, 11, 21)
    assert rows[0]["symbol"] == "RELIANCE"
    assert calls == [date(2026, 11, 22), date(2026, 11, 21)]
