"""Add provenance-aware persistent EOD market snapshots.

Revision ID: 0002_market_eod_store
Revises: 0001_provider_token_vault
"""
from __future__ import annotations
from alembic import op

revision = "0002_market_eod_store"
down_revision = "0001_provider_token_vault"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_eod_bars (
            exchange TEXT NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
            trading_date DATE NOT NULL,
            symbol TEXT NOT NULL,
            series TEXT NOT NULL,
            isin TEXT,
            company_name TEXT NOT NULL DEFAULT '',
            open NUMERIC(20, 6) NOT NULL CHECK (open > 0),
            high NUMERIC(20, 6) NOT NULL CHECK (high > 0),
            low NUMERIC(20, 6) NOT NULL CHECK (low > 0),
            close NUMERIC(20, 6) NOT NULL CHECK (close > 0),
            previous_close NUMERIC(20, 6),
            volume BIGINT NOT NULL CHECK (volume >= 0),
            trades BIGINT CHECK (trades IS NULL OR trades >= 0),
            turnover NUMERIC(24, 4),
            provider TEXT NOT NULL,
            data_mode TEXT NOT NULL CHECK (data_mode = 'END_OF_DAY'),
            quality_status TEXT NOT NULL CHECK (
                quality_status IN ('VERIFIED_SOURCE', 'PROVISIONAL')
            ),
            source_retrieved_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (exchange, trading_date, symbol, series, provider),
            CHECK (low <= LEAST(open, close)),
            CHECK (high >= GREATEST(open, close)),
            CHECK (low <= high)
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_eod_bars_symbol_date
        ON market_eod_bars (exchange, symbol, trading_date DESC)
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_eod_bars_date
        ON market_eod_bars (exchange, trading_date DESC)
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_eod_bars_isin_date
        ON market_eod_bars (exchange, isin, trading_date DESC)
    """)
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_ingestion_runs (
            run_id UUID PRIMARY KEY,
            provider TEXT NOT NULL,
            exchange TEXT NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
            trading_date DATE,
            status TEXT NOT NULL CHECK (
                status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'EMPTY')
            ),
            rows_received INTEGER NOT NULL DEFAULT 0 CHECK (rows_received >= 0),
            rows_skipped INTEGER NOT NULL DEFAULT 0 CHECK (rows_skipped >= 0),
            rows_upserted INTEGER NOT NULL DEFAULT 0 CHECK (rows_upserted >= 0),
            started_at TIMESTAMPTZ NOT NULL,
            completed_at TIMESTAMPTZ,
            error_code TEXT,
            error_message TEXT
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_ingestion_runs_exchange_started
        ON market_ingestion_runs (exchange, started_at DESC)
    """)


def downgrade() -> None:
    # Preserve market history; corrective schema changes are forward-only.
    pass
