from __future__ import annotations

import asyncio
from datetime import date

import pytest

from rovexquant.market_data.official_bhavcopy import (
    OfficialBhavcopyError,
    download_official_bhavcopy,
)


@pytest.mark.parametrize("exchange", ["NSE", "BSE"])
def test_official_download_is_disabled_without_written_permission(exchange, monkeypatch):
    monkeypatch.delenv(f"{exchange}_OFFICIAL_AUTOMATED_DATA_PERMISSION", raising=False)
    monkeypatch.delenv(f"{exchange}_OFFICIAL_PERMISSION_REFERENCE", raising=False)

    with pytest.raises(OfficialBhavcopyError, match="express written permission"):
        asyncio.run(download_official_bhavcopy(exchange, date(2026, 11, 23)))


def test_permission_for_one_exchange_does_not_authorise_the_other(monkeypatch):
    monkeypatch.setenv("NSE_OFFICIAL_AUTOMATED_DATA_PERMISSION", "true")
    monkeypatch.setenv("NSE_OFFICIAL_PERMISSION_REFERENCE", "NSE-PERMISSION-REF")
    monkeypatch.delenv("BSE_OFFICIAL_AUTOMATED_DATA_PERMISSION", raising=False)
    monkeypatch.delenv("BSE_OFFICIAL_PERMISSION_REFERENCE", raising=False)

    with pytest.raises(OfficialBhavcopyError, match="Automated BSE bhavcopy retrieval is disabled"):
        asyncio.run(download_official_bhavcopy("BSE", date(2026, 11, 23)))
