import pytest


@pytest.mark.parametrize(
    ("token_mode", "token_database_url", "market_database_url", "expected"),
    [
        # The market queue has its own PostgreSQL database; the token vault is SQLite.
        ("sqlite", "", "postgresql://market.example/quant?sslmode=require", True),
        # The queue and token vault share PostgreSQL through the default configuration.
        ("", "postgresql://shared.example/quant?sslmode=require", "", True),
        ("postgresql", "postgresql://tokens.example/quant?sslmode=require", "", True),
        # A local-only SQLite installation has no durable PostgreSQL queue to run.
        ("sqlite", "", "", False),
        ("", "", "", False),
    ],
)
def test_worker_enabled_tracks_market_database_not_only_token_storage(
    monkeypatch, token_mode, token_database_url, market_database_url, expected
):
    from rovexquant.market_data.eod_worker import worker_enabled

    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", token_mode)
    monkeypatch.setenv("QUANT_TOKEN_DATABASE_URL", token_database_url)
    monkeypatch.setenv("QUANT_MARKET_DATABASE_URL", market_database_url)

    assert worker_enabled() is expected
