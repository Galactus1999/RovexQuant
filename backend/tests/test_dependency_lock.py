from __future__ import annotations

from pathlib import Path
import tomllib

from packaging.requirements import Requirement
from packaging.utils import canonicalize_name


ROOT = Path(__file__).parents[1]


def test_backend_lock_pins_all_direct_runtime_and_development_dependencies():
    project = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["project"]
    locked: dict[str, str] = {}
    for raw in (ROOT / "requirements.lock").read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        requirement = Requirement(line)
        assert requirement.url is None
        assert len(requirement.specifier) == 1
        specifier = next(iter(requirement.specifier))
        assert specifier.operator == "==", f"{line} is not exactly pinned"
        name = canonicalize_name(requirement.name)
        assert name not in locked, f"duplicate lock entry: {name}"
        locked[name] = specifier.version

    requirements = list(project.get("dependencies", []))
    requirements.extend(project.get("optional-dependencies", {}).get("dev", []))
    for raw in requirements:
        requirement = Requirement(raw)
        name = canonicalize_name(requirement.name)
        assert name in locked, f"{name} is missing from requirements.lock"
        assert requirement.specifier.contains(locked[name], prereleases=True), (
            f"locked {name}=={locked[name]} violates pyproject constraint {raw}"
        )
