"""Add resumable historical imports and official exchange reconciliation evidence.

Revision ID: 0004_historical_backfill_reconciliation
Revises: 0003_market_ingestion_queue
"""
from __future__ import annotations

from alembic import op

revision = "0004_hist_recon"
down_revision = "0003_market_ingestion_queue"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_historical_backfill_runs (
            run_id UUID PRIMARY KEY,
            exchange TEXT NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
            year INTEGER NOT NULL CHECK (year BETWEEN 2010 AND 2100),
            source_url TEXT NOT NULL,
            source_sha256 CHAR(64) NOT NULL,
            status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
            rows_seen BIGINT NOT NULL DEFAULT 0 CHECK (rows_seen >= 0),
            rows_upserted BIGINT NOT NULL DEFAULT 0 CHECK (rows_upserted >= 0),
            started_at TIMESTAMPTZ NOT NULL,
            completed_at TIMESTAMPTZ,
            error_message TEXT,
            UNIQUE (exchange, year, source_sha256)
        )
    """)
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_historical_backfill_days (
            run_id UUID NOT NULL REFERENCES market_historical_backfill_runs(run_id) ON DELETE CASCADE,
            exchange TEXT NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
            year INTEGER NOT NULL,
            trading_date DATE NOT NULL,
            source_sha256 CHAR(64) NOT NULL,
            status TEXT NOT NULL CHECK (status IN ('SUCCEEDED')),
            rows_upserted INTEGER NOT NULL CHECK (rows_upserted > 0),
            completed_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (exchange, year, trading_date, source_sha256)
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_backfill_runs_status
        ON market_historical_backfill_runs (status, exchange, year)
    """)
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_reconciliation_reports (
            report_id UUID PRIMARY KEY,
            exchange TEXT NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
            trading_date DATE NOT NULL,
            provider TEXT NOT NULL,
            source_url TEXT NOT NULL,
            source_sha256 CHAR(64) NOT NULL,
            status TEXT NOT NULL CHECK (status IN ('MATCHED', 'MISMATCHED', 'NO_REFERENCE_DATA')),
            official_rows INTEGER NOT NULL CHECK (official_rows >= 0),
            reference_rows INTEGER NOT NULL CHECK (reference_rows >= 0),
            matched_rows INTEGER NOT NULL CHECK (matched_rows >= 0),
            mismatch_rows INTEGER NOT NULL CHECK (mismatch_rows >= 0),
            missing_reference_rows INTEGER NOT NULL CHECK (missing_reference_rows >= 0),
            missing_official_rows INTEGER NOT NULL CHECK (missing_official_rows >= 0),
            created_at TIMESTAMPTZ NOT NULL,
            UNIQUE (exchange, trading_date, source_sha256)
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_reconciliation_date
        ON market_reconciliation_reports (exchange, trading_date DESC, created_at DESC)
    """)
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_reconciliation_diffs (
            diff_id BIGSERIAL PRIMARY KEY,
            report_id UUID NOT NULL REFERENCES market_reconciliation_reports(report_id) ON DELETE CASCADE,
            kind TEXT NOT NULL CHECK (kind IN ('VALUE_MISMATCH', 'IDENTIFIER_MISMATCH', 'MISSING_REFERENCE', 'MISSING_OFFICIAL')),
            symbol TEXT NOT NULL,
            series TEXT NOT NULL,
            field TEXT,
            official_value TEXT,
            stored_value TEXT,
            absolute_difference NUMERIC(24, 8)
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_reconciliation_diffs_report
        ON market_reconciliation_diffs (report_id, symbol, series)
    """)


def downgrade() -> None:
    # Forward-only: retain imported history and audit evidence.
    pass
