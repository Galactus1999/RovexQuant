"""Persistent AI research runs, source references and usage accounting.

Revision ID: 0008_ai_research_runs
Revises: 0007_intelligence_archive
"""
from alembic import op

revision = "0008_ai_research_runs"
down_revision = "0007_intelligence_archive"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS ai_research_runs (
            run_id UUID PRIMARY KEY,
            task_type TEXT NOT NULL CHECK (length(task_type) BETWEEN 1 AND 48),
            provider_id TEXT NOT NULL CHECK (length(provider_id) BETWEEN 1 AND 48),
            model TEXT NOT NULL CHECK (length(model) BETWEEN 1 AND 160),
            credential_id TEXT,
            status TEXT NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'VERIFICATION_PENDING', 'VERIFIED', 'PARTIALLY_VERIFIED', 'REJECTED')),
            request_hash CHAR(64) NOT NULL,
            request_payload JSONB NOT NULL,
            response_payload JSONB NOT NULL,
            token_usage JSONB NOT NULL DEFAULT '{}'::jsonb,
            cost_usd NUMERIC(18, 8),
            cost_status TEXT NOT NULL DEFAULT 'NOT_TRACKED',
            fallback_used BOOLEAN NOT NULL DEFAULT FALSE,
            source_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
            verification JSONB NOT NULL DEFAULT '{}'::jsonb,
            created_at TIMESTAMPTZ NOT NULL,
            completed_at TIMESTAMPTZ
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS ix_ai_research_runs_created ON ai_research_runs (created_at DESC)")
    op.execute("CREATE INDEX IF NOT EXISTS ix_ai_research_runs_task_created ON ai_research_runs (task_type, created_at DESC)")
    op.execute("CREATE INDEX IF NOT EXISTS ix_ai_research_runs_provider_created ON ai_research_runs (provider_id, created_at DESC)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS ai_research_runs")
