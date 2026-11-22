"""HTTP authorization dependencies; policy remains in the owning auth services."""
from __future__ import annotations

import hmac
import os

from fastapi import Header, HTTPException

from rovexquant.market_data.service import MarketGatewayError, authorize_market_gateway


def require_quant_admin(x_quant_admin_key: str | None = Header(default=None)) -> None:
    expected = os.getenv("QUANT_ADMIN_GATEWAY_TOKEN", "").strip()
    if not expected:
        raise HTTPException(status_code=503, detail="Quant admin endpoints are disabled until configured")
    if not x_quant_admin_key or not hmac.compare_digest(expected, x_quant_admin_key):
        raise HTTPException(status_code=401, detail="Invalid Quant admin credential")


def require_market_gateway(
    x_quant_market_gateway_key: str | None = Header(default=None),
) -> None:
    try:
        authorize_market_gateway(x_quant_market_gateway_key)
    except MarketGatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
