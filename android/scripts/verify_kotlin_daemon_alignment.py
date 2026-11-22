#!/usr/bin/env python3
"""Fail if Kotlin compiler/daemon artifacts resolve at versions other than the app KGP."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NAMESPACE = {"g": "https://schema.gradle.org/dependency-verification"}
CRITICAL_MODULES = {
    "kotlin-daemon-client",
    "kotlin-daemon-embeddable",
    "kotlin-compiler-runner",
    "kotlin-compiler-embeddable",
    "kotlin-gradle-plugin",
    "kotlin-gradle-plugin-api",
}


def expected_kotlin_version(build_file: Path) -> str:
    source = build_file.read_text(encoding="utf-8")
    match = re.search(
        r'id\("org\.jetbrains\.kotlin\.android"\)\s+version\s+"([^"]+)"',
        source,
    )
    if not match:
        raise ValueError(f"Cannot find pinned org.jetbrains.kotlin.android version in {build_file}")
    return match.group(1)


def verify(metadata_file: Path, build_file: Path) -> dict[str, set[str]]:
    expected = expected_kotlin_version(build_file)
    root = ET.parse(metadata_file).getroot()
    versions: dict[str, set[str]] = {}
    for component in root.findall(".//g:component", NAMESPACE):
        if component.attrib.get("group") != "org.jetbrains.kotlin":
            continue
        name = component.attrib.get("name", "")
        if name in CRITICAL_MODULES:
            versions.setdefault(name, set()).add(component.attrib.get("version", ""))

    daemon_versions = versions.get("kotlin-daemon-client", set())
    if daemon_versions != {expected}:
        raise ValueError(
            "Kotlin daemon client must match the app Kotlin Gradle Plugin exactly: "
            f"expected {expected}, found {sorted(daemon_versions)}"
        )

    mismatches = {
        name: sorted(found)
        for name, found in versions.items()
        if found != {expected}
    }
    if mismatches:
        raise ValueError(
            f"Kotlin compiler/daemon dependency versions do not match KGP {expected}: {mismatches}"
        )
    return versions


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
        versions = verify(args.metadata, args.build_file)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Kotlin compiler/daemon alignment check failed: {error}", file=sys.stderr)
        return 1
    print(f"Kotlin compiler/daemon artifacts match KGP {expected_kotlin_version(args.build_file)}:")
    for name in sorted(versions):
        print(f"  {name}: {', '.join(sorted(versions[name]))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
