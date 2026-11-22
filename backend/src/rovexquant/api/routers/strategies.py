"""Strategy catalogue route; strategy definitions remain owned by the registry."""
from fastapi import APIRouter

from rovexquant.strategies.registry import default_registry

router = APIRouter()


@router.get("/api/v1/strategies")
def strategies():
    return [
        {
            "strategy_id": item.metadata.strategy_id,
            "name": item.metadata.name,
            "version": item.metadata.version,
            "horizon": item.metadata.horizon,
            "description": item.metadata.description,
        }
        for item in default_registry().all()
    ]
