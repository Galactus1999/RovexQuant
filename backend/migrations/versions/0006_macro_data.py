"""Persist macroeconomic observations and provider refresh provenance.

Revision ID: 0006_macro_data
Revises: 0005_corp_adj
"""
from __future__ import annotations

from alembic import op

revision = "0006_macro_data"
down_revision = "0005_corp_adj"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS macro_ingestion_runs (
            run_id UUID PRIMARY KEY,
            provider TEXT NOT NULL CHECK (provider IN ('WORLD_BANK', 'FRED', 'AMFI', 'RBIH_DBIE_MIRROR')),
            status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
            rows_seen BIGINT NOT NULL DEFAULT 0 CHECK (rows_seen >= 0),
            rows_upserted BIGINT NOT NULL DEFAULT 0 CHECK (rows_upserted >= 0),
            source_sha256 CHAR(64),
            started_at TIMESTAMPTZ NOT NULL,
            completed_at TIMESTAMPTZ,
            error_message TEXT
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_macro_ingestion_runs_provider_started
        ON macro_ingestion_runs (provider, started_at DESC)
    """)
    op.execute("""
        CREATE TABLE IF NOT EXISTS macro_observations (
            provider TEXT NOT NULL CHECK (provider IN ('WORLD_BANK', 'FRED', 'AMFI', 'RBIH_DBIE_MIRROR')),
            series_key TEXT NOT NULL,
            period TEXT NOT NULL,
            value NUMERIC(28, 10) NOT NULL,
            unit TEXT NOT NULL DEFAULT '',
            frequency TEXT NOT NULL DEFAULT '',
            label TEXT NOT NULL DEFAULT '',
            source_url TEXT NOT NULL,
            source_sha256 CHAR(64) NOT NULL,
            retrieved_at TIMESTAMPTZ NOT NULL,
            metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
            PRIMARY KEY (provider, series_key, period, source_sha256)
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_macro_observations_latest
        ON macro_observations (provider, series_key, period, retrieved_at DESC)
    """)


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS macro_observations")
    op.execute("DROP TABLE IF EXISTS macro_ingestion_runs")
