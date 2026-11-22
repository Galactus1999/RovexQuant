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


def test_api_composition_root_only_wires_cross_cutting_and_feature_routers():
    imports = _imports(SOURCE / "api" / "main.py")
    required = {
        "rovexquant.api.routers.auth",
        "rovexquant.api.routers.market",
        "rovexquant.api.routers.integrations",
        "rovexquant.api.routers.health",
        "rovexquant.api.routers.ai",
        "rovexquant.api.routers.intelligence",
        "rovexquant.api.routers.strategies",
        "rovexquant.research.router",
    }
    assert required.issubset(imports)
    forbidden = (
        "rovexquant.ai.gateway",
        "rovexquant.auth",
        "rovexquant.market_data.providers",
        "rovexquant.market_data.service",
        "rovexquant.market_data.streaming",
        "rovexquant.market_data.upstox_credentials",
        "rovexquant.market_data.instruments",
        "rovexquant.strategies.registry",
        "rovexquant.indicators",
        "rovexquant.backtesting.engine",
    )
    assert not any(
        name == prefix or name.startswith(prefix + ".")
        for name in imports
        for prefix in forbidden
    )


def test_api_composition_root_has_no_inline_feature_handlers():
    main = ast.parse((SOURCE / "api" / "main.py").read_text(encoding="utf-8"))
    handlers = [
        node for node in ast.walk(main)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    ]
    assert not handlers
    assert not any(
        isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr in {"get", "post", "put", "patch", "delete", "websocket"}
        for node in ast.walk(main)
    )


def test_api_routers_do_not_import_network_or_database_drivers():
    forbidden = ("httpx", "websockets", "psycopg", "sqlalchemy")
    for path in (SOURCE / "api" / "routers").rglob("*.py"):
        imports = _imports(path)
        assert not any(
            name == prefix or name.startswith(prefix + ".")
            for name in imports
            for prefix in forbidden
        ), f"{path.relative_to(SOURCE)} imports a network/database driver"


def test_domain_engines_do_not_depend_on_http_or_api_layers():
    domain_roots = ("indicators", "backtesting", "strategies", "risk", "domain", "intelligence")
    forbidden = ("fastapi", "starlette", "rovexquant.api", "httpx", "websockets", "psycopg", "sqlalchemy")
    for package in domain_roots:
        package_path = SOURCE / package
        if not package_path.exists():
            continue
        for path in package_path.rglob("*.py"):
            imports = _imports(path)
            assert not any(
                name == prefix or name.startswith(prefix + ".")
                for name in imports
                for prefix in forbidden
            ), f"{path.relative_to(SOURCE)} imports an HTTP/API, network or storage layer"


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
