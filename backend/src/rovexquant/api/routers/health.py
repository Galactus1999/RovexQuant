"""Liveness, readiness and protected process-local metrics routes."""
from fastapi import APIRouter, Depends, HTTPException

from rovexquant.api.dependencies import require_quant_admin
from rovexquant.market_data.upstox_credentials import (
    UpstoxCredentialError,
    validate_storage_readiness,
)
from rovexquant.security.request_context import request_metrics

router = APIRouter()


@router.get("/api/v1/health")
@router.get("/api/v1/health/live")
def health():
    """Liveness only; optional providers are not required for process health."""
    return {"status": "ok", "application": "Quant", "mode": "research-only"}


@router.get(
    "/api/v1/health/metrics",
    dependencies=[Depends(require_quant_admin)],
)
def health_metrics():
    """Expose real process-local HTTP metrics only to the configured admin."""
    return request_metrics.snapshot()


@router.get("/api/v1/health/ready")
def readiness():
    """Readiness for mandatory credential storage; never returns secret/config values."""
    try:
        validate_storage_readiness()
        return {"status": "ready", "application": "Quant"}
    except UpstoxCredentialError as error:
        raise HTTPException(
            status_code=503,
            detail="Required credential storage is unavailable or its schema is not migrated",
        ) from error
