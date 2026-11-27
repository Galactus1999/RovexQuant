"""Remove the retired DalalOS credential from the shared provider vault.

Revision ID: 0009_remove_dalalos_credential
Revises: 0008_ai_research_runs
"""
from __future__ import annotations

from alembic import op

revision = "0009_remove_dalalos_credential"
down_revision = "0008_ai_research_runs"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # The old integration stored its API key under this exact provider key.
    # Deleting only this row preserves Upstox and AI-provider credentials.
    op.execute("DELETE FROM provider_tokens WHERE provider = 'mcp:dalalos'")


def downgrade() -> None:
    # Credential deletion is intentionally irreversible; never recreate secrets.
    pass
