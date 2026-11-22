import gzip
import json

import pytest

from rovexquant.market_data.instruments import (
    InstrumentMasterError,
    normalize_nse_equity_master,
    normalize_nse_index_master,
)


def test_instrument_master_selects_only_valid_nse_index_records():
    records = [
        {"segment": "NSE_INDEX", "instrument_type": "INDEX", "name": "Nifty 50",
         "trading_symbol": "Nifty 50", "instrument_key": "NSE_INDEX|Nifty 50"},
        {"segment": "NSE_EQ", "instrument_type": "EQ", "name": "RELIANCE",
         "trading_symbol": "RELIANCE", "instrument_key": "NSE_EQ|INE002A01018"},
        {"segment": "NSE_INDEX", "instrument_type": "INDEX", "name": "Nifty Bank",
         "trading_symbol": "Nifty Bank", "instrument_key": "NSE_INDEX|Nifty Bank"},
        {"segment": "NSE_INDEX", "instrument_type": "INDEX", "name": "Invalid",
         "trading_symbol": "Invalid", "instrument_key": "https://bad.example"},
    ]
    result = normalize_nse_index_master(json.dumps(records).encode())
    assert [item["instrument_key"] for item in result] == [
        "NSE_INDEX|Nifty 50", "NSE_INDEX|Nifty Bank"
    ]


def test_instrument_master_supports_gzip_and_rejects_invalid_payload():
    records = [{"segment": "NSE_INDEX", "instrument_type": "INDEX", "name": "Nifty 50",
                "trading_symbol": "Nifty 50", "instrument_key": "NSE_INDEX|Nifty 50"}]
    assert normalize_nse_index_master(gzip.compress(json.dumps(records).encode()))[0]["name"] == "Nifty 50"
    with pytest.raises(InstrumentMasterError):
        normalize_nse_index_master(b"not-json")


def test_instrument_master_rejects_empty_index_set():
    with pytest.raises(InstrumentMasterError, match="no valid index"):
        normalize_nse_index_master(json.dumps([
            {"segment": "NSE_EQ", "instrument_type": "EQ", "instrument_key": "NSE_EQ|A",
             "name": "A", "trading_symbol": "A"}
        ]).encode())



def test_instrument_master_selects_verified_nse_cash_equities_only():
    records = [
        {"segment": "NSE_EQ", "instrument_type": "EQ", "name": "RELIANCE INDUSTRIES LTD",
         "trading_symbol": "RELIANCE", "isin": "INE002A01018",
         "instrument_key": "NSE_EQ|INE002A01018"},
        {"segment": "NSE_EQ", "instrument_type": "BE", "name": "JOCIL LIMITED",
         "trading_symbol": "JOCIL", "isin": "INE839G01010",
         "instrument_key": "NSE_EQ|INE839G01010"},
        {"segment": "NSE_FO", "instrument_type": "FUT", "name": "RELIANCE",
         "trading_symbol": "RELIANCE FUT", "isin": "INE002A01018",
         "instrument_key": "NSE_FO|12345"},
        {"segment": "NSE_EQ", "instrument_type": "EQ", "name": "BAD",
         "trading_symbol": "BAD", "isin": "INE002A01018",
         "instrument_key": "https://bad.example"},
    ]
    result = normalize_nse_equity_master(json.dumps(records).encode())
    assert [item["trading_symbol"] for item in result] == ["JOCIL", "RELIANCE"]
    assert result[1]["instrument_key"] == "NSE_EQ|INE002A01018"


def test_equity_master_supports_gzip_and_rejects_empty_universe():
    records = [{"segment": "NSE_EQ", "instrument_type": "EQ", "name": "RELIANCE",
                "trading_symbol": "RELIANCE", "isin": "INE002A01018",
                "instrument_key": "NSE_EQ|INE002A01018"}]
    assert normalize_nse_equity_master(gzip.compress(json.dumps(records).encode()))[0]["isin"] == "INE002A01018"
    with pytest.raises(InstrumentMasterError, match="no valid cash-equity"):
        normalize_nse_equity_master(json.dumps([
            {"segment": "NSE_INDEX", "instrument_type": "INDEX", "name": "NIFTY",
             "trading_symbol": "NIFTY", "instrument_key": "NSE_INDEX|Nifty 50"}
        ]).encode())


def test_instrument_master_selects_verified_nse_cash_equities_only():
    records = [
        {"segment": "NSE_EQ", "instrument_type": "EQ", "name": "RELIANCE INDUSTRIES LTD",
         "trading_symbol": "RELIANCE", "isin": "INE002A01018",
         "instrument_key": "NSE_EQ|INE002A01018"},
        {"segment": "NSE_EQ", "instrument_type": "BE", "name": "JOCIL LIMITED",
         "trading_symbol": "JOCIL", "isin": "INE839G01010",
         "instrument_key": "NSE_EQ|INE839G01010"},
        {"segment": "NSE_FO", "instrument_type": "FUT", "name": "RELIANCE",
         "trading_symbol": "RELIANCE FUT", "isin": "INE002A01018",
         "instrument_key": "NSE_FO|12345"},
        {"segment": "NSE_EQ", "instrument_type": "EQ", "name": "BAD",
         "trading_symbol": "BAD", "isin": "INE002A01018",
         "instrument_key": "https://bad.example"},
    ]
    result = normalize_nse_equity_master(json.dumps(records).encode())
    assert [item["trading_symbol"] for item in result] == ["JOCIL", "RELIANCE"]
    assert result[1]["instrument_key"] == "NSE_EQ|INE002A01018"


def test_equity_master_supports_gzip_and_rejects_empty_universe():
    records = [{"segment": "NSE_EQ", "instrument_type": "EQ", "name": "RELIANCE",
                "trading_symbol": "RELIANCE", "isin": "INE002A01018",
                "instrument_key": "NSE_EQ|INE002A01018"}]
    assert normalize_nse_equity_master(gzip.compress(json.dumps(records).encode()))[0]["isin"] == "INE002A01018"
    with pytest.raises(InstrumentMasterError, match="no valid cash-equity"):
        normalize_nse_equity_master(json.dumps([
            {"segment": "NSE_INDEX", "instrument_type": "INDEX", "name": "NIFTY",
             "trading_symbol": "NIFTY", "instrument_key": "NSE_INDEX|Nifty 50"}
        ]).encode())
