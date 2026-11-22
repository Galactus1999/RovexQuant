"""Provider credential administration routes."""
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, SecretStr

from rovexquant.api.dependencies import require_quant_admin
from rovexquant.security.dependencies import require_app_session
from rovexquant.security.request_controls import provider_credential_rate_limit
from rovexquant.market_data.upstox_credentials import (
    UpstoxCredentialError,
    store_upstox_analytics_token,
    delete_upstox_analytics_token,
    upstox_analytics_status,
)

router = APIRouter()


class UpstoxAnalyticsTokenInput(BaseModel):
    token: SecretStr


@router.put(
    "/api/v1/app/integrations/upstox/analytics-token",
    dependencies=[Depends(require_app_session), Depends(provider_credential_rate_limit)],
)
def save_app_upstox_analytics_token(request: UpstoxAnalyticsTokenInput):
    """Store the owner's read-only Analytics Token using encrypted server-side storage."""
    try:
        store_upstox_analytics_token(request.token.get_secret_value())
        return {
            "provider": "upstox",
            "configured": True,
            "credential_type": "analytics_read_only",
            "credential_storage": "encrypted_server_side",
            "message": "Token stored. The live feed will verify authorization on connection.",
        }
    except UpstoxCredentialError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/api/v1/app/integrations/upstox/status",
    dependencies=[Depends(require_app_session)],
)
def app_upstox_status():
    try:
        status = upstox_analytics_status()
        return {
            "provider": "upstox",
            "configured": bool(status.get("connected")),
            "credential_type": status.get("credential_type", "analytics_read_only"),
            "credential_storage": status.get("credential_storage", "unavailable"),
            "expires_at": status.get("expires_at"),
            "connection_verified": False,
            "note": "This reports encrypted token storage only; actual upstream authorization and live ticks are shown by Market Pulse.",
        }
    except UpstoxCredentialError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.delete(
    "/api/v1/app/integrations/upstox/analytics-token",
    dependencies=[Depends(require_app_session), Depends(provider_credential_rate_limit)],
)
def delete_app_upstox_analytics_token():
    try:
        return {"provider": "upstox", "deleted": delete_upstox_analytics_token()}
    except UpstoxCredentialError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error



@router.post(
    "/api/v1/integrations/upstox/analytics-token",
    dependencies=[Depends(require_quant_admin)],
)
def save_upstox_analytics_token(request: UpstoxAnalyticsTokenInput):
    try:
        store_upstox_analytics_token(request.token)
        return {
            "status": "connected",
            "provider": "upstox",
            "credential_type": "analytics_read_only",
            "message": "Token encrypted and stored on the Quant backend; it is never returned",
        }
    except UpstoxCredentialError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/api/v1/integrations/upstox/status",
    dependencies=[Depends(require_quant_admin)],
)
def upstox_status():
    try:
        return upstox_analytics_status()
    except UpstoxCredentialError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
