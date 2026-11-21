"""Provider selection and safe failover for market quote snapshots."""
from __future__ import annotations

import hmac
import os
from typing import Sequence

from rovexquant.market_data.providers import (
    DhanQuoteProvider, MarketQuote, ProviderError, QuoteInstrument,
    QuoteProvider, UpstoxV3QuoteProvider,
)


class MarketGatewayError(Exception):
    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message)
        self.status_code = status_code


def authorize_market_gateway(supplied_token: str | None) -> None:
    expected = os.getenv("QUANT_MARKET_GATEWAY_TOKEN", "").strip()
    if not expected:
        raise MarketGatewayError(
            "Market data gateway is disabled until QUANT_MARKET_GATEWAY_TOKEN is configured", 503
        )
    if not supplied_token or not hmac.compare_digest(expected, supplied_token):
        raise MarketGatewayError("Invalid market data gateway credential", 401)


def build_providers() -> dict[str, QuoteProvider]:
    return {
        "upstox": UpstoxV3QuoteProvider(os.getenv("UPSTOX_ANALYTICS_TOKEN", "")),
        "dhan": DhanQuoteProvider(
            os.getenv("DHAN_CLIENT_ID", ""), os.getenv("DHAN_ACCESS_TOKEN", "")
        ),
    }


def provider_status(providers: dict[str, QuoteProvider] | None = None) -> list[dict[str, object]]:
    selected = providers or build_providers()
    return [{"provider_id": name, "configured": provider.configured}
            for name, provider in selected.items()]


async def fetch_quotes(
    instruments: Sequence[QuoteInstrument],
    provider_order: Sequence[str],
    providers: dict[str, QuoteProvider] | None = None,
) -> tuple[str, list[MarketQuote], bool]:
    available = providers or build_providers()
    if not provider_order or len(provider_order) > 2 or len(set(provider_order)) != len(provider_order):
        raise MarketGatewayError("Select one or two distinct providers", 422)
    if any(name not in available for name in provider_order):
        raise MarketGatewayError("Unsupported market data provider", 422)

    last_error: ProviderError | None = None
    for index, name in enumerate(provider_order):
        provider = available[name]
        if not provider.configured:
            last_error = ProviderError(name, f"{name} is not configured", retryable=True, status_code=503)
            continue
        try:
            return name, await provider.get_quotes(instruments), index > 0
        except ProviderError as error:
            last_error = error
            # Do not silently switch accounts/providers after invalid credentials or bad requests.
            if not error.retryable:
                raise MarketGatewayError(str(error), error.status_code) from error
    raise MarketGatewayError(
        str(last_error) if last_error else "No configured market data provider is available",
        last_error.status_code if last_error else 503,
    )
