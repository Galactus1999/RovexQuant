"""Verify exact pins cover every declared backend runtime and development requirement."""
from __future__ import annotations

from pathlib import Path
import tomllib

from packaging.requirements import Requirement
from packaging.utils import canonicalize_name


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    project = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["project"]
    locked: dict[str, str] = {}
    for raw in (ROOT / "requirements.lock").read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        requirement = Requirement(line)
        if requirement.url is not None or len(requirement.specifier) != 1:
            raise SystemExit("Lock entries must use one exact version pin: " + line)
        specifier = next(iter(requirement.specifier))
        if specifier.operator != "==":
            raise SystemExit("Lock entries must use == pins: " + line)
        name = canonicalize_name(requirement.name)
        if name in locked:
            raise SystemExit("Duplicate lock entry: " + name)
        locked[name] = specifier.version

    requirements = list(project.get("dependencies", []))
    requirements.extend(project.get("optional-dependencies", {}).get("dev", []))
    for raw in requirements:
        requirement = Requirement(raw)
        name = canonicalize_name(requirement.name)
        version = locked.get(name)
        if version is None:
            raise SystemExit("Missing dependency lock entry: " + name)
        if not requirement.specifier.contains(version, prereleases=True):
            raise SystemExit(
                "Locked " + name + "==" + version + " violates pyproject constraint " + raw
            )
    print("Backend exact dependency lock: PASS (" + str(len(locked)) + " pinned packages)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
