"""Rotate provider vault encryption keys without printing secret material.

Run from backend/ with QUANT_TOKEN_OLD_ENCRYPTION_KEY and
QUANT_TOKEN_NEW_ENCRYPTION_KEY set in a secure operator environment.
The application must be switched to the new key after successful rotation.
"""
from __future__ import annotations

import os
import sys

from rovexquant.market_data.upstox_credentials import (
    UpstoxCredentialError,
    rotate_provider_token_encryption_key,
)


def main() -> int:
    old_key = os.environ.get("QUANT_TOKEN_OLD_ENCRYPTION_KEY", "")
    new_key = os.environ.get("QUANT_TOKEN_NEW_ENCRYPTION_KEY", "")
    try:
        count = rotate_provider_token_encryption_key(old_key, new_key)
    except UpstoxCredentialError as error:
        print(f"Key rotation failed: {error}")
        return 2
    print(f"Successfully rotated {count} provider credential record(s).")
    print("Deploy QUANT_TOKEN_ENCRYPTION_KEY with the new key before restarting the application.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
