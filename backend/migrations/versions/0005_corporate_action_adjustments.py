"""Add adjusted EOD bars, corporate actions and instrument identity history.

Revision ID: 0005_corp_adj
Revises: 0004_hist_recon
"""
from __future__ import annotations

from alembic import op

revision = "0005_corp_adj"
down_revision = "0004_hist_recon"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_eod_adjusted_bars (
            exchange TEXT NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
            trading_date DATE NOT NULL,
            symbol TEXT NOT NULL,
            series TEXT NOT NULL,
            isin TEXT,
            adjusted_close NUMERIC(20, 6) NOT NULL CHECK (adjusted_close > 0),
            adjustment_factor NUMERIC(24, 12) NOT NULL CHECK (adjustment_factor > 0),
            provider TEXT NOT NULL,
            source_sha256 CHAR(64) NOT NULL,
            source_retrieved_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (exchange, trading_date, symbol, series, provider)
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_adjusted_symbol_date
        ON market_eod_adjusted_bars (exchange, symbol, trading_date DESC)
    """)
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_corporate_actions (
            action_id UUID PRIMARY KEY,
            exchange TEXT NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
            symbol TEXT NOT NULL,
            isin TEXT,
            company_name TEXT NOT NULL DEFAULT '',
            ex_date DATE NOT NULL,
            record_date DATE,
            action_type TEXT NOT NULL,
            ratio_num BIGINT,
            ratio_den BIGINT,
            cash_amount NUMERIC(20, 6),
            face_value_from NUMERIC(20, 6),
            face_value_to NUMERIC(20, 6),
            raw_subject TEXT NOT NULL DEFAULT '',
            provider TEXT NOT NULL,
            source_sha256 CHAR(64) NOT NULL,
            source_retrieved_at TIMESTAMPTZ NOT NULL,
            UNIQUE (exchange, symbol, ex_date, action_type, raw_subject, provider)
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_corporate_actions_symbol_date
        ON market_corporate_actions (exchange, symbol, ex_date DESC)
    """)
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_symbol_history (
            exchange TEXT NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
            isin TEXT NOT NULL,
            symbol TEXT NOT NULL,
            valid_from DATE NOT NULL,
            valid_to DATE,
            trading_days INTEGER NOT NULL CHECK (trading_days >= 0),
            provider TEXT NOT NULL,
            source_sha256 CHAR(64) NOT NULL,
            source_retrieved_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (exchange, isin, symbol, valid_from, provider)
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_symbol_history_lookup
        ON market_symbol_history (exchange, isin, valid_from, valid_to)
    """)
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_derived_backfill_runs (
            run_id UUID PRIMARY KEY,
            exchange TEXT NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
            year INTEGER NOT NULL CHECK (year BETWEEN 0 AND 2100),
            dataset_kind TEXT NOT NULL CHECK (dataset_kind IN ('ADJUSTED_PRICES', 'CORPORATE_ACTIONS', 'SYMBOL_HISTORY')),
            source_url TEXT NOT NULL,
            source_sha256 CHAR(64) NOT NULL,
            status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
            rows_seen BIGINT NOT NULL DEFAULT 0 CHECK (rows_seen >= 0),
            rows_upserted BIGINT NOT NULL DEFAULT 0 CHECK (rows_upserted >= 0),
            started_at TIMESTAMPTZ NOT NULL,
            completed_at TIMESTAMPTZ,
            error_message TEXT,
            UNIQUE (exchange, year, dataset_kind, source_sha256)
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_derived_backfill_status
        ON market_derived_backfill_runs (exchange, year, dataset_kind, status)
    """)


def downgrade() -> None:
    # Forward-only: retain adjusted research history and corporate-action evidence.
    pass
