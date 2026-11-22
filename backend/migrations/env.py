"""Alembic environment: migrations require the direct PostgreSQL URL."""
from __future__ import annotations

import os

from alembic import context
from sqlalchemy import engine_from_config, pool
from sqlalchemy.engine import make_url

config = context.config


def _migration_url() -> str:
    raw = os.getenv("QUANT_TOKEN_MIGRATION_DATABASE_URL", "").strip()
    if not raw:
        raise RuntimeError(
            "QUANT_TOKEN_MIGRATION_DATABASE_URL is required; use the direct, non-pooled Neon URL"
        )
    url = make_url(raw)
    if url.drivername in {"postgres", "postgresql"}:
        url = url.set(drivername="postgresql+psycopg")
    if url.drivername != "postgresql+psycopg":
        raise RuntimeError("The migration URL must use PostgreSQL with psycopg 3")
    if url.host and "-pooler." in url.host:
        raise RuntimeError("Migrations require Neon's direct endpoint, not the transaction pooler")

    sslmode = url.query.get("sslmode")
    if os.getenv("QUANT_ENV") == "test":
        if sslmode not in {"disable", "require", "verify-ca", "verify-full"}:
            raise RuntimeError("PostgreSQL integration tests must declare sslmode explicitly")
    elif sslmode not in {"require", "verify-ca", "verify-full"}:
        raise RuntimeError("Hosted database migrations require TLS (sslmode=require or stronger)")
    return url.render_as_string(hide_password=False)


def run_migrations_offline() -> None:
    url = _migration_url()
    context.configure(
        url=url,
        target_metadata=None,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    # Alembic's ConfigParser treats percent signs as interpolation markers.
    config.set_main_option("sqlalchemy.url", _migration_url().replace("%", "%%"))
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
        future=True,
    )
    try:
        with connectable.connect() as connection:
            with connection.begin():
                context.configure(
                    connection=connection,
                    target_metadata=None,
                    compare_type=True,
                    transaction_per_migration=True,
                )
                context.run_migrations()
    finally:
        connectable.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
