from __future__ import annotations

import ast
from pathlib import Path

SOURCE = Path(__file__).parents[1] / "src" / "rovexquant"


def _imports(path: Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    found: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            found.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            found.add(node.module)
    return found


def test_api_composition_does_not_import_deterministic_engines():
    imports = _imports(SOURCE / "api" / "main.py")
    assert not any(
        name.startswith("rovexquant.indicators")
        or name == "rovexquant.backtesting.engine"
        for name in imports
    )


def test_research_transport_does_not_own_engine_implementation():
    imports = _imports(SOURCE / "research" / "router.py")
    assert "rovexquant.research.service" in imports
    assert not any(
        name.startswith("rovexquant.indicators")
        or name == "rovexquant.backtesting.engine"
        for name in imports
    )


def test_research_application_service_has_no_transport_dependency():
    imports = _imports(SOURCE / "research" / "service.py")
    assert not any(
        name == "fastapi"
        or name.startswith("fastapi.")
        or name.startswith("starlette.")
        or name.startswith("rovexquant.api")
        for name in imports
    )


def test_domain_engines_do_not_depend_on_http_or_api_layers():
    domain_roots = ("indicators", "backtesting", "strategies")
    forbidden = ("fastapi", "starlette", "rovexquant.api")
    for package in domain_roots:
        for path in (SOURCE / package).rglob("*.py"):
            imports = _imports(path)
            assert not any(
                name == prefix or name.startswith(prefix + ".")
                for name in imports
                for prefix in forbidden
            ), f"{path.relative_to(SOURCE)} imports an HTTP/API layer"


def test_api_composition_uses_feature_routers_instead_of_inline_research_handlers():
    main = ast.parse((SOURCE / "api" / "main.py").read_text(encoding="utf-8"))
    imports = _imports(SOURCE / "api" / "main.py")
    assert "rovexquant.research.router" in imports
    assert "rovexquant.api.research_data" in imports
    inline_handlers = {
        node.name for node in ast.walk(main)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    }
    assert not any(
        name.startswith("app_research_") or name.startswith("app_nse_mcp_")
        for name in inline_handlers
    )


def test_research_data_transport_delegates_to_provider_services():
    imports = _imports(SOURCE / "api" / "research_data.py")
    assert "rovexquant.market_data.nse_mcp_service" in imports
    assert "rovexquant.market_data.tejhq_service" in imports
    assert "rovexquant.market_data.nse_mcp" not in imports
    assert "rovexquant.market_data.tejhq" not in imports
