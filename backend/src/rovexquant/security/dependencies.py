"""Shared FastAPI authorization dependencies."""
from fastapi import Header, HTTPException

from rovexquant.auth import AppAuthError, bearer_claims


def require_app_session(authorization: str | None = Header(default=None)) -> None:
    try:
        bearer_claims(authorization)
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
