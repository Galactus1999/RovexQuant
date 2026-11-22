from __future__ import annotations

from pathlib import Path
import runpy

import pytest

from rovexquant.market_data import upstox_credentials as vault


SCRIPT = Path(__file__).parents[1] / "scripts" / "rotate_provider_token_key.py"


def test_rotation_operator_script_reads_keys_from_environment_and_never_prints_them(
    monkeypatch, capsys
):
    old_key = "old-operator-secret-sentinel"
    new_key = "new-operator-secret-sentinel"
    calls = []

    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "postgresql")
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", old_key)
    monkeypatch.setenv("QUANT_TOKEN_OLD_ENCRYPTION_KEY", old_key)
    monkeypatch.setenv("QUANT_TOKEN_NEW_ENCRYPTION_KEY", new_key)
    monkeypatch.setattr(vault, "validate_storage_configuration", lambda: None)
    monkeypatch.setattr(vault, "validate_postgres_schema", lambda: None)
    monkeypatch.setattr(
        vault,
        "rotate_provider_token_encryption_key",
        lambda old, new: calls.append((old, new)) or 3,
    )

    main = runpy.run_path(str(SCRIPT))["main"]
    assert main() == 0

    output = capsys.readouterr().out
    assert calls == [(old_key, new_key)]
    assert "3" in output
    assert old_key not in output
    assert new_key not in output


def test_rotation_operator_script_rejects_active_key_mismatch_without_rotation(
    monkeypatch,
):
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "postgresql")
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", "active-key")
    monkeypatch.setenv("QUANT_TOKEN_OLD_ENCRYPTION_KEY", "wrong-old-key")
    monkeypatch.setenv("QUANT_TOKEN_NEW_ENCRYPTION_KEY", "new-key")
    monkeypatch.setattr(
        vault,
        "rotate_provider_token_encryption_key",
        lambda *_: pytest.fail("rotation must not run when the active key mismatches"),
    )

    main = runpy.run_path(str(SCRIPT))["main"]
    with pytest.raises(SystemExit, match="active vault key does not match"):
        main()
