"""OpenAPI security metadata for the mobile app API."""
from fastapi import FastAPI
from fastapi.openapi.utils import get_openapi


def configure_openapi(app: FastAPI) -> None:
    """Publish mobile app authentication as a Swagger HTTP Bearer security scheme."""
    def quant_openapi():
        if app.openapi_schema:
            return app.openapi_schema

        schema = get_openapi(
            title=app.title,
            version=app.version,
            description=app.description,
            routes=app.routes,
        )
        components = schema.setdefault("components", {})
        schemes = components.setdefault("securitySchemes", {})
        schemes["QuantAppBearer"] = {
            "type": "http",
            "scheme": "bearer",
            "bearerFormat": "JWT",
            "description": "Use the short-lived token returned by POST /api/v1/auth/session.",
        }

        for path, path_item in schema.get("paths", {}).items():
            if not path.startswith("/api/v1/app/") and path not in {
                "/api/v1/research/indicators",
                "/api/v1/research/backtest",
            }:
                continue
            for operation in path_item.values():
                if not isinstance(operation, dict) or "responses" not in operation:
                    continue
                operation["security"] = [{"QuantAppBearer": []}]
                operation["parameters"] = [
                    parameter
                    for parameter in operation.get("parameters", [])
                    if not (
                        parameter.get("in") == "header"
                        and parameter.get("name", "").lower() == "authorization"
                    )
                ]

        app.openapi_schema = schema
        return app.openapi_schema

    app.openapi = quant_openapi
