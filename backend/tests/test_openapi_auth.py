from rovexquant.api.main import app


def test_mobile_app_routes_use_http_bearer_security_in_openapi():
    schema = app.openapi()

    bearer = schema["components"]["securitySchemes"]["QuantAppBearer"]
    assert bearer["type"] == "http"
    assert bearer["scheme"] == "bearer"
    assert bearer["bearerFormat"] == "JWT"

    app_operations = 0
    for path, path_item in schema["paths"].items():
        if not path.startswith("/api/v1/app/"):
            continue
        for operation in path_item.values():
            if not isinstance(operation, dict) or "responses" not in operation:
                continue
            app_operations += 1
            assert operation["security"] == [{"QuantAppBearer": []}]
            assert not any(
                parameter.get("in") == "header"
                and parameter.get("name", "").lower() == "authorization"
                for parameter in operation.get("parameters", [])
            )

    assert app_operations > 0


def test_app_login_remains_public_in_openapi():
    schema = app.openapi()
    login = schema["paths"]["/api/v1/auth/session"]["post"]

    assert "security" not in login
