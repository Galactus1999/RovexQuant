"""FastAPI composition root; feature behavior lives in dedicated routers/services."""
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException

from rovexquant.api.lifecycle import lifespan
from rovexquant.api.openapi import configure_openapi
from rovexquant.api.routers.ai import router as ai_router
from rovexquant.api.routers.auth import router as auth_router
from rovexquant.api.routers.health import router as health_router
from rovexquant.api.routers.intelligence import router as intelligence_router
from rovexquant.api.routers.integrations import router as integrations_router
from rovexquant.api.routers.market import router as market_router
from rovexquant.api.routers.macro import router as macro_router
from rovexquant.api.routers.strategies import router as strategies_router
from rovexquant.api.research_data import router as research_data_router
from rovexquant.api.market_ingestion import router as market_ingestion_router
from rovexquant.research.router import router as research_router
from rovexquant.security.errors import (
    http_exception_handler,
    unhandled_exception_handler,
    validation_exception_handler,
)
from rovexquant.security.request_context import RequestContextMiddleware
from rovexquant.security.request_controls import RequestBodyLimitMiddleware


app = FastAPI(
    title="Quant API",
    version="0.5.0",
    description="Evidence-based market research. No order execution.",
    lifespan=lifespan,
)
app.add_middleware(RequestBodyLimitMiddleware, max_body_size=2 * 1024 * 1024)
app.add_middleware(RequestContextMiddleware)
app.add_exception_handler(StarletteHTTPException, http_exception_handler)
app.add_exception_handler(RequestValidationError, validation_exception_handler)
app.add_exception_handler(Exception, unhandled_exception_handler)

for feature_router in (
    auth_router,
    market_router,
    macro_router,
    integrations_router,
    health_router,
    intelligence_router,
    strategies_router,
    ai_router,
    research_router,
    research_data_router,
    market_ingestion_router,
):
    app.include_router(feature_router)

configure_openapi(app)
