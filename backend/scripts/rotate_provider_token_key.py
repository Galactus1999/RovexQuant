"""Run an atomic PostgreSQL provider-vault encryption-key rotation.

Secrets are read only from the process environment. Never pass keys as CLI
arguments, print them, or include them in diagnostic output.
"""
from __future__ import annotations

import hmac
import os
import sys

from rovexquant.market_data import upstox_credentials as vault


def main() -> int:
    if os.getenv("QUANT_TOKEN_STORAGE_MODE", "").strip().lower() != "postgresql":
        raise SystemExit("Key rotation requires QUANT_TOKEN_STORAGE_MODE=postgresql.")

    active_key = os.getenv("QUANT_TOKEN_ENCRYPTION_KEY", "")
    old_key = os.getenv("QUANT_TOKEN_OLD_ENCRYPTION_KEY", "")
    new_key = os.getenv("QUANT_TOKEN_NEW_ENCRYPTION_KEY", "")
    if not active_key or not old_key or not new_key:
        raise SystemExit(
            "Set the active, old and new vault keys through the deployment secret manager."
        )
    if not hmac.compare_digest(active_key, old_key):
        raise SystemExit("The active vault key does not match the supplied old key.")
    if hmac.compare_digest(old_key, new_key):
        raise SystemExit("The old and new vault keys must be different.")

    try:
        vault.validate_storage_configuration()
        vault.validate_postgres_schema()
        rotated = vault.rotate_provider_token_encryption_key(old_key, new_key)
    except Exception as error:
        print(
            "Vault key rotation failed; inspect protected platform diagnostics. "
            f"Error type: {type(error).__name__}.",
            file=sys.stderr,
        )
        return 1

    print(
        "Vault key rotation committed atomically. "
        f"Credential rows re-encrypted: {rotated}. "
        "Update QUANT_TOKEN_ENCRYPTION_KEY to the new key and restart the API."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
