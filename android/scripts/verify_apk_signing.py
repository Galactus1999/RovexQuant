#!/usr/bin/env python3
"""Verify an APK signature and its exact signer certificate without parsing human text."""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


PEM_CERTIFICATE = re.compile(
    rb"-----BEGIN CERTIFICATE-----\s+.*?-----END CERTIFICATE-----",
    re.DOTALL,
)


def normalize_sha256(value: str) -> str:
    normalized = re.sub(r"[^0-9a-fA-F]", "", value).upper()
    if not re.fullmatch(r"[0-9A-F]{64}", normalized):
        raise ValueError("Expected signer SHA-256 must contain exactly 64 hexadecimal digits")
    return normalized


def certificate_sha256(pem: bytes, openssl: str) -> str:
    result = subprocess.run(
        [openssl, "x509", "-noout", "-fingerprint", "-sha256"],
        input=pem,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"OpenSSL could not parse signer certificate: {detail}")
    output = result.stdout.decode("ascii", errors="strict").strip()
    match = re.search(r"=([0-9A-Fa-f:]{64,})$", output)
    if not match:
        raise RuntimeError(f"Unexpected OpenSSL fingerprint output: {output!r}")
    return normalize_sha256(match.group(1))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apksigner", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--openssl", default="openssl")
    args = parser.parse_args()

    expected = normalize_sha256(args.expected_sha256)
    if not args.apksigner.is_file() or not args.apk.is_file():
        raise RuntimeError("apksigner or APK path does not exist")

    result = subprocess.run(
        [str(args.apksigner), "verify", "--verbose", "--print-certs-pem", str(args.apk)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(
            f"apksigner rejected the APK (exit {result.returncode}). "
            f"Diagnostics: {detail or 'no stderr output'}"
        )

    certificates = PEM_CERTIFICATE.findall(result.stdout)
    if len(certificates) != 1:
        raise RuntimeError(
            "Expected exactly one APK signer certificate from apksigner "
            f"--print-certs-pem; received {len(certificates)}. "
            "Refusing ambiguous or unsigned APK."
        )

    actual = certificate_sha256(certificates[0], args.openssl)
    print("APK_SIGNATURE_VERIFIED=true")
    print("APK_SIGNER_CERT_SHA256=" + actual)
    if actual != expected:
        raise RuntimeError(
            "APK signer certificate does not match the pinned development identity. "
            f"Expected {expected}; received {actual}."
        )
    print("APK_SIGNER_IDENTITY_MATCH=true")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"::error::{exc}", file=sys.stderr)
        raise SystemExit(1)
