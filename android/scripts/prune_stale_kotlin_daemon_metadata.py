#!/usr/bin/env python3
"""Remove obsolete Kotlin daemon-client checksum records after a strict compile."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def expected_kotlin_version(build_file: Path) -> str:
    source = build_file.read_text(encoding="utf-8")
    match = re.search(
        r'id\("org\.jetbrains\.kotlin\.android"\)\s+version\s+"([^"]+)"',
        source,
    )
    if not match:
        raise ValueError(f"Cannot find pinned Kotlin Android plugin version in {build_file}")
    return match.group(1)


def prune_stale_versions(xml_text: str, expected: str) -> tuple[str, list[str]]:
    pattern = re.compile(
        r'(?ms)^[ \t]*<component group="org\.jetbrains\.kotlin" '
        r'name="kotlin-daemon-client" version="([^"]+)">.*?^[ \t]*</component>[ \t]*\n?'
    )
    found: set[str] = set()
    removed: list[str] = []

    def replace(match: re.Match[str]) -> str:
        version = match.group(1)
        found.add(version)
        if version == expected:
            return match.group(0)
        removed.append(version)
        return ""

    updated = pattern.sub(replace, xml_text)
    if expected not in found:
        raise ValueError(
            f"Expected kotlin-daemon-client:{expected} record is missing; found {sorted(found)}"
        )
    ET.fromstring(updated)
    return updated, sorted(set(removed))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--metadata",
        type=Path,
        default=Path("android/gradle/verification-metadata.xml"),
    )
    parser.add_argument(
        "--build-file",
        type=Path,
        default=Path("android/build.gradle.kts"),
    )
    args = parser.parse_args()

    try:
        expected = expected_kotlin_version(args.build_file)
        original = args.metadata.read_text(encoding="utf-8")
        updated, removed = prune_stale_versions(original, expected)
        args.metadata.write_text(updated, encoding="utf-8")
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Kotlin daemon metadata pruning failed: {error}", file=sys.stderr)
        return 1

    if removed:
        print(
            f"Removed stale kotlin-daemon-client versions {removed}; "
            f"retained the app KGP version {expected}."
        )
    else:
        print(f"No stale kotlin-daemon-client records; retained {expected}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
