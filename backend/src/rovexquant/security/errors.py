"""Safe structured HTTP error responses without request-input echoing."""
from __future__ import annotations

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.responses import JSONResponse

from rovexquant.security.request_context import current_request_id

def _code_for_status(status: int) -> str:
    return {400: "bad_request", 401: "unauthorized", 403: "forbidden", 404: "not_found", 413: "request_too_large", 422: "validation_error", 429: "rate_limited", 503: "service_unavailable"}.get(status, "request_rejected" if status < 500 else "internal_error")

async def http_exception_handler(_request: Request, error: StarletteHTTPException) -> JSONResponse:
    if isinstance(error.detail, str):
        detail = error.detail[:240]
    else:
        detail = "The request was rejected."
    return JSONResponse(status_code=error.status_code, headers=error.headers, content={"detail": detail, "error": {"code": _code_for_status(error.status_code), "message": detail}, "request_id": current_request_id()})

async def validation_exception_handler(_request: Request, error: RequestValidationError) -> JSONResponse:
    # Pydantic raw errors can include rejected input values; never echo them.
    safe_errors = [{"loc": [str(part) for part in item.get("loc", ())], "msg": str(item.get("msg", "Invalid value"))[:240], "type": str(item.get("type", "value_error"))[:80]} for item in error.errors()]
    return JSONResponse(status_code=422, content={"detail": safe_errors, "error": {"code": "validation_error", "message": "Request validation failed."}, "request_id": current_request_id()})
