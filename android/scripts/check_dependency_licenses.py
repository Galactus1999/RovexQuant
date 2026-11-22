"""Fail closed when Android runtime dependencies have unknown or unapproved licenses."""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REPORT_ROOT = ROOT / "app" / "build" / "reports" / "licenses"
ALLOWLIST = ROOT / "app" / "config" / "allowed-licenses.json"


def main() -> int:
    if not REPORT_ROOT.is_dir():
        raise SystemExit("Android dependency license report directory is missing")
    allowlist = json.loads(ALLOWLIST.read_text(encoding="utf-8"))
    allowed = [
        item["moduleLicense"]
        for item in allowlist.get("allowedLicenses", [])
        if isinstance(item, dict) and isinstance(item.get("moduleLicense"), str)
    ]
    reports = []
    for path in REPORT_ROOT.rglob("*.json"):
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(document, dict) and isinstance(document.get("dependencies"), list):
            reports.append((path, document["dependencies"]))
    if not reports:
        raise SystemExit("No machine-readable Android dependency license inventory was generated")

    checked: set[tuple[str, str]] = set()
    failures: list[str] = []
    for path, dependencies in reports:
        for dependency in dependencies:
            if not isinstance(dependency, dict):
                failures.append("Malformed dependency record in " + path.name)
                continue
            coordinate = str(dependency.get("dependency") or dependency.get("project") or "unknown")
            version = str(dependency.get("version") or "")
            licenses = dependency.get("licenses")
            if not isinstance(licenses, list) or not licenses:
                failures.append(coordinate + ("@" + version if version else "") + ": license not declared")
                continue
            for item in licenses:
                name = item.get("license") if isinstance(item, dict) else None
                if not isinstance(name, str) or not name.strip():
                    failures.append(coordinate + ": malformed license declaration")
                    continue
                checked.add((coordinate, name))
                if not any(re.fullmatch(pattern, name, flags=re.IGNORECASE) for pattern in allowed):
                    failures.append(coordinate + ": unapproved license " + name)
    if failures:
        print("Android dependency license review required:")
        for failure in sorted(set(failures)):
            print(" - " + failure)
        return 1
    print("Android dependency license audit: PASS (" + str(len(checked)) + " dependency/license pairs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
