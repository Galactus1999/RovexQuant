"""Verify PostgreSQL custom-format backup and restore without exposing URLs."""
from __future__ import annotations

import base64
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.parse import urlsplit, urlunsplit
from uuid import uuid4

import psycopg
from cryptography.fernet import Fernet, InvalidToken
from psycopg import sql


_TEST_VAULT_KEY = base64.urlsafe_b64encode(
    hashlib.sha256(b"RovexQuant Phase 1 PostgreSQL integration test key only").digest()
)


def _run_pg_client(image: str, directory: str, *arguments: str) -> None:
    """Run version-matched PostgreSQL tools in a disposable official client image."""
    command = [
        "docker", "run", "--rm", "--network", "host",
        "--user", str(os.getuid()) + ":" + str(os.getgid()),
        "-v", directory + ":/backup", image, *arguments,
    ]
    try:
        subprocess.run(command, check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(
            f"PostgreSQL client command failed (exit {error.returncode}); connection details redacted"
        ) from None


def main() -> int:
    source_url = os.environ.get("QUANT_TOKEN_TEST_DATABASE_URL", "").strip()
    if not source_url.startswith(("postgresql://", "postgres://")):
        raise SystemExit("QUANT_TOKEN_TEST_DATABASE_URL must be a disposable PostgreSQL URL")

    parsed = urlsplit(source_url)
    restore_name = "quant_restore_" + uuid4().hex[:12]
    restore_url = urlunsplit((parsed.scheme, parsed.netloc, "/" + restore_name, parsed.query, ""))
    admin_url = urlunsplit((parsed.scheme, parsed.netloc, "/postgres", parsed.query, ""))

    with tempfile.TemporaryDirectory(prefix="quant-pg-restore-") as directory:
        backup_path = Path(directory) / "quant-vault.dump"
        client_image = os.environ.get("QUANT_PG_CLIENT_IMAGE", "").strip()
        created = False
        try:
            try:
                if client_image:
                    _run_pg_client(
                        client_image, directory,
                        "pg_dump", "--format=custom", "--no-owner", "--no-privileges",
                        "--file", "/backup/quant-vault.dump", source_url,
                    )
                else:
                    subprocess.run(
                        ["pg_dump", "--format=custom", "--no-owner", "--no-privileges",
                         "--file", str(backup_path), source_url],
                        check=True, capture_output=True, text=True,
                    )
            except subprocess.CalledProcessError as error:
                raise RuntimeError(
                    f"PostgreSQL backup command failed (exit {error.returncode}); connection details redacted"
                ) from None
            if not backup_path.is_file() or backup_path.stat().st_size == 0:
                raise RuntimeError("PostgreSQL backup artifact was empty")
            with psycopg.connect(admin_url, autocommit=True) as connection:
                connection.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(restore_name)))
                created = True
            try:
                if client_image:
                    _run_pg_client(
                        client_image, directory,
                        "pg_restore", "--exit-on-error", "--no-owner", "--no-privileges",
                        "--dbname", restore_url, "/backup/quant-vault.dump",
                    )
                else:
                    subprocess.run(
                        ["pg_restore", "--exit-on-error", "--no-owner", "--no-privileges",
                         "--dbname", restore_url, str(backup_path)],
                        check=True, capture_output=True, text=True,
                    )
            except subprocess.CalledProcessError as error:
                raise RuntimeError(
                    f"PostgreSQL restore command failed (exit {error.returncode}); connection details redacted"
                ) from None
            with psycopg.connect(restore_url) as connection:
                revision = connection.execute(
                    "SELECT version_num FROM alembic_version"
                ).fetchone()
                columns = {
                    row[0] for row in connection.execute(
                        "SELECT column_name FROM information_schema.columns "
                        "WHERE table_schema=current_schema() AND table_name='provider_tokens'"
                    ).fetchall()
                }
                legacy = connection.execute(
                    "SELECT encrypted_token FROM provider_tokens WHERE provider=%s",
                    ("phase1-legacy-migration-probe",),
                ).fetchone()
            if revision is None or not columns.issuperset(
                {"provider", "encrypted_token", "updated_at", "expires_at"}
            ):
                raise RuntimeError("Restored PostgreSQL schema did not pass verification")
            if legacy is None or not bytes(legacy[0]):
                raise RuntimeError("Restored PostgreSQL backup did not preserve legacy vault data")
            try:
                restored_plaintext = Fernet(_TEST_VAULT_KEY).decrypt(bytes(legacy[0]))
            except InvalidToken as error:
                raise RuntimeError(
                    "Restored PostgreSQL backup did not preserve decryptable vault ciphertext"
                ) from error
            if restored_plaintext != b"legacy-ciphertext-preservation-probe":
                raise RuntimeError("Restored PostgreSQL vault probe plaintext did not match")
        finally:
            if created:
                with psycopg.connect(admin_url, autocommit=True) as connection:
                    connection.execute(
                        sql.SQL("DROP DATABASE IF EXISTS {}").format(sql.Identifier(restore_name))
                    )

    print("PostgreSQL custom-format backup and isolated restore verification: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
