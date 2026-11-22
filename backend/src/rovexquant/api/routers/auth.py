"""Mobile login and short-lived app-session routes."""
from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, Field

from rovexquant.auth import AppAuthError, bearer_claims, issue_app_session
from rovexquant.security.request_controls import login_rate_limit

router = APIRouter()


class AppLoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=120)
    password: str = Field(min_length=1, max_length=256)


@router.post("/api/v1/auth/session", dependencies=[Depends(login_rate_limit)])
def create_app_session(request: AppLoginRequest):
    try:
        token, expires_in = issue_app_session(request.username, request.password)
        return {"access_token": token, "token_type": "Bearer", "expires_in": expires_in}
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get("/api/v1/app/auth/session")
def validate_app_session(authorization: str | None = Header(default=None)):
    """Validate a previously issued mobile session without exposing its bearer token."""
    try:
        claims = bearer_claims(authorization)
        return {"authenticated": True, "expires_at": claims["exp"]}
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
