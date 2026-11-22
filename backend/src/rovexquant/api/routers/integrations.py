"""Provider credential administration routes."""
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from rovexquant.api.dependencies import require_quant_admin
from rovexquant.market_data.upstox_credentials import (
    UpstoxCredentialError,
    store_upstox_analytics_token,
    upstox_analytics_status,
)

router = APIRouter()


class UpstoxAnalyticsTokenInput(BaseModel):
    token: str = Field(min_length=20, max_length=4096)


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
