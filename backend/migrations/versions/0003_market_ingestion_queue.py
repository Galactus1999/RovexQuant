"""Add a leased PostgreSQL queue for recoverable market ingestion.

Revision ID: 0003_market_ingestion_queue
Revises: 0002_market_eod_store
"""
from __future__ import annotations

from alembic import op

revision = "0003_market_ingestion_queue"
down_revision = "0002_market_eod_store"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS market_ingestion_jobs (
            run_id UUID PRIMARY KEY REFERENCES market_ingestion_runs(run_id) ON DELETE CASCADE,
            exchange TEXT NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
            status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
            attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
            available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            lease_owner TEXT,
            lease_expires_at TIMESTAMPTZ,
            heartbeat_at TIMESTAMPTZ,
            last_error TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_ingestion_jobs_claim
        ON market_ingestion_jobs (status, available_at, created_at)
    """)
    op.execute("""
        CREATE INDEX IF NOT EXISTS ix_market_ingestion_jobs_lease
        ON market_ingestion_jobs (status, lease_expires_at)
    """)


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS market_ingestion_jobs")
