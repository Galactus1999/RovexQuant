from datetime import date
import io
import zipfile

import pytest

from rovexquant.market_data.historical_backfill import (
    parquet_source_url, validate_exchange_year,
)
from rovexquant.market_data.official_bhavcopy import (
    OfficialBhavcopyError, compare_official_rows, official_bhavcopy_url, parse_udiff_csv,
)


def _udiff_csv(day="2026-11-20"):
    return (
        "TradDt,TckrSymb,SctySrs,ISIN,OpnPric,HghPric,LwPric,ClsPric,LastPric,"
        "PrvsClsgPric,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd\n"
        f"{day},RELIANCE,EQ,INE002A01018,100,110,95,105,106,102,1000,105000,100\n"
        f"{day},TCS,EQ,INE467B01029,200,210,195,205,206,202,2000,410000,200\n"
        f"{day},NIFTYBEES,BE,INF204KB14I2,50,52,49,51,51,50,500,25500,20\n"
        f"{day},TESTETF,MF,INE000000000,10,10,10,10,10,10,100,1000,1\n"
    ).encode()


def test_historical_archive_urls_and_exchange_year_bounds():
    assert parquet_source_url("nse", 2010).endswith("/nse/year=2010/nse_2010.parquet")
    assert parquet_source_url("bse", 2024).endswith("/bse/year=2024/bse_2024.parquet")
    assert validate_exchange_year("NSE", 2026, current_year=2026) == "NSE"
    with pytest.raises(ValueError):
        validate_exchange_year("BSE", 2023, current_year=2026)
    with pytest.raises(ValueError):
        validate_exchange_year("FOO", 2026, current_year=2026)


def test_official_download_urls_are_exchange_specific():
    day = date(2026, 11, 20)
    assert "BhavCopy_NSE_CM_0_0_0_20261120_F_0000.csv.zip" in official_bhavcopy_url("NSE", day)
    assert "BhavCopy_BSE_CM_0_0_0_20261120_F_0000.CSV" in official_bhavcopy_url("BSE", day)


def test_parse_nse_udiff_zip_filters_non_equity_and_normalizes():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("bhavcopy.csv", _udiff_csv())
    rows = parse_udiff_csv(buffer.getvalue(), exchange="NSE", trading_date=date(2026, 11, 20))
    assert [row["symbol"] for row in rows] == ["RELIANCE", "TCS", "NIFTYBEES"]
    assert rows[0]["close"] == 105.0
    assert rows[0]["volume"] == 1000
    assert rows[0]["isin"] == "INE002A01018"
    assert rows[0]["provider"] == "official_nse_bhavcopy"


def test_parse_bse_udiff_csv():
    csv_data = (
        "TradDt,TckrSymb,SctySrs,ISIN,OpnPric,HghPric,LwPric,ClsPric,PrvsClsgPric,TtlTradgVol\n"
        "2026-11-20,RELIANCE,A,INE002A01018,100,110,95,105,102,1000\n"
    ).encode()
    rows = parse_udiff_csv(csv_data, exchange="BSE", trading_date=date(2026, 11, 20))
    assert len(rows) == 1
    assert rows[0]["series"] == "A"


def test_parse_official_bhavcopy_rejects_wrong_trade_date_and_corrupt_zip():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("bhavcopy.csv", _udiff_csv("2026-11-19"))
    with pytest.raises(OfficialBhavcopyError, match="different trading date"):
        parse_udiff_csv(buffer.getvalue(), exchange="NSE", trading_date=date(2026, 11, 20))
    with pytest.raises(OfficialBhavcopyError, match="corrupt"):
        parse_udiff_csv(b"not-a-zip", exchange="NSE", trading_date=date(2026, 11, 20))


def test_compare_official_rows_reports_mismatches_and_missing_symbols():
    official = [
        {"symbol": "RELIANCE", "series": "EQ", "open": 100, "high": 110, "low": 95,
         "close": 105, "previous_close": 102, "volume": 1000, "trades": 100,
         "isin": "INE002A01018"},
        {"symbol": "TCS", "series": "EQ", "open": 200, "high": 210, "low": 195,
         "close": 205, "previous_close": 202, "volume": 2000, "trades": 200,
         "isin": "INE467B01029"},
    ]
    stored = [
        {"symbol": "RELIANCE", "series": "EQ", "open": 100, "high": 110, "low": 95,
         "close": 104, "previous_close": 102, "volume": 1000, "trades": 100,
         "isin": "INE002A01018"},
        {"symbol": "INFY", "series": "EQ", "open": 150, "high": 160, "low": 145,
         "close": 155, "previous_close": 152, "volume": 500, "trades": 50,
         "isin": "INE009A01021"},
    ]
    report = compare_official_rows(official, stored)
    assert report["official_rows"] == 2
    assert report["reference_rows"] == 2
    assert report["matched_rows"] == 0
    assert report["mismatch_rows"] == 1
    assert report["missing_reference_rows"] == 1
    assert report["missing_official_rows"] == 1
    assert {item["kind"] for item in report["diffs"]} == {
        "VALUE_MISMATCH", "MISSING_REFERENCE", "MISSING_OFFICIAL"
    }


def test_compare_exact_official_reference_rows_as_match():
    row = {"symbol": "RELIANCE", "series": "EQ", "open": 100, "high": 110, "low": 95,
           "close": 105, "previous_close": 102, "volume": 1000, "trades": 100,
           "isin": "INE002A01018"}
    report = compare_official_rows([row], [row])
    assert report["matched_rows"] == 1
    assert report["diffs"] == []
