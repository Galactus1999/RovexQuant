"""Establish the encrypted provider-token vault schema.

Revision ID: 0001_provider_token_vault
Revises:
"""
from __future__ import annotations

from alembic import op

revision = "0001_provider_token_vault"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Existing installations may already have this table from the legacy
    # request-time bootstrap. IF NOT EXISTS makes the baseline adoptable
    # without dropping or rewriting encrypted credentials.
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS provider_tokens (
            provider TEXT PRIMARY KEY,
            encrypted_token BYTEA NOT NULL,
            updated_at BIGINT NOT NULL,
            expires_at BIGINT
        )
        """
    )
    op.execute(
        "ALTER TABLE provider_tokens "
        "ADD COLUMN IF NOT EXISTS expires_at BIGINT"
    )


def downgrade() -> None:
    # Deliberately preserve provider credentials. Schema rollback is forward-only;
    # ship a corrective migration rather than dropping the credential vault.
    pass
