"""Durable global event archive, source provenance and issuer candidates.

Revision ID: 0007_intelligence_archive
Revises: 0006_macro_data
"""
from __future__ import annotations

from alembic import op

revision = "0007_intelligence_archive"
down_revision = "0006_macro_data"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS intelligence_ingestion_runs (
            run_id UUID PRIMARY KEY, provider TEXT NOT NULL,
            status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
            query TEXT NOT NULL, article_count INTEGER NOT NULL DEFAULT 0 CHECK (article_count >= 0),
            event_count INTEGER NOT NULL DEFAULT 0 CHECK (event_count >= 0),
            source_count INTEGER NOT NULL DEFAULT 0 CHECK (source_count >= 0),
            mapping_count INTEGER NOT NULL DEFAULT 0 CHECK (mapping_count >= 0),
            started_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ,
            error_message TEXT, provider_status JSONB NOT NULL DEFAULT '{}'::jsonb
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS ix_intelligence_ingestion_runs_started ON intelligence_ingestion_runs (started_at DESC)")
    op.execute("""
        CREATE TABLE IF NOT EXISTS intelligence_events (
            event_id TEXT PRIMARY KEY CHECK (length(event_id) = 64),
            headline TEXT NOT NULL, event_category TEXT NOT NULL,
            event_tags JSONB NOT NULL DEFAULT '[]'::jsonb,
            classification_method TEXT NOT NULL,
            classification_confidence NUMERIC(5,4) NOT NULL CHECK (classification_confidence BETWEEN 0 AND 1),
            confidence_semantics TEXT NOT NULL,
            sector_impacts JSONB NOT NULL DEFAULT '[]'::jsonb,
            stock_mapping_status TEXT NOT NULL, stock_mapping_note TEXT NOT NULL,
            impact_status TEXT NOT NULL,
            source_article_count INTEGER NOT NULL DEFAULT 0 CHECK (source_article_count >= 0),
            independent_source_count INTEGER NOT NULL DEFAULT 0 CHECK (independent_source_count >= 0),
            first_seen_at TIMESTAMPTZ NOT NULL, last_seen_at TIMESTAMPTZ NOT NULL,
            last_ingested_at TIMESTAMPTZ NOT NULL
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS ix_intelligence_events_recent ON intelligence_events (last_seen_at DESC, event_category)")
    op.execute("""
        CREATE TABLE IF NOT EXISTS intelligence_event_sources (
            event_id TEXT NOT NULL REFERENCES intelligence_events(event_id) ON DELETE CASCADE,
            url_sha256 CHAR(64) NOT NULL, canonical_url TEXT NOT NULL,
            title TEXT NOT NULL, domain TEXT NOT NULL, seen_at TIMESTAMPTZ NOT NULL,
            language TEXT NOT NULL DEFAULT '', source_country TEXT NOT NULL DEFAULT '',
            summary TEXT NOT NULL DEFAULT '', provider TEXT NOT NULL,
            evidence_class TEXT NOT NULL, retrieved_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (event_id, url_sha256)
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS ix_intelligence_event_sources_domain ON intelligence_event_sources (domain, seen_at DESC)")
    op.execute("""
        CREATE TABLE IF NOT EXISTS intelligence_issuer_aliases (
            exchange TEXT NOT NULL CHECK (exchange = 'NSE'), symbol TEXT NOT NULL,
            isin TEXT NOT NULL, company_name TEXT NOT NULL, instrument_key TEXT NOT NULL,
            source TEXT NOT NULL, last_seen_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (exchange, symbol)
        )
    """)
    op.execute("""
        CREATE TABLE IF NOT EXISTS intelligence_event_issuers (
            event_id TEXT NOT NULL REFERENCES intelligence_events(event_id) ON DELETE CASCADE,
            exchange TEXT NOT NULL CHECK (exchange = 'NSE'), symbol TEXT NOT NULL,
            isin TEXT NOT NULL, company_name TEXT NOT NULL, mapping_method TEXT NOT NULL,
            confidence NUMERIC(5,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
            evidence TEXT NOT NULL, mapping_scope TEXT NOT NULL, mapping_source TEXT NOT NULL,
            mapped_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (event_id, exchange, symbol)
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS ix_intelligence_event_issuers_symbol ON intelligence_event_issuers (exchange, symbol, mapped_at DESC)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS intelligence_event_issuers")
    op.execute("DROP TABLE IF EXISTS intelligence_issuer_aliases")
    op.execute("DROP TABLE IF EXISTS intelligence_event_sources")
    op.execute("DROP TABLE IF EXISTS intelligence_events")
    op.execute("DROP TABLE IF EXISTS intelligence_ingestion_runs")
