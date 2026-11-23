"""Direct NSE/BSE UDiFF bhavcopy retrieval and deterministic EOD reconciliation."""
from __future__ import annotations

import csv
import hashlib
import io
import zipfile
from datetime import date, datetime, timezone
from typing import Any
from uuid import uuid4

import httpx

from rovexquant.market_data.eod_store import connect, normalize_snapshot_rows

PRICE_FIELDS = ("open", "high", "low", "close", "previous_close")
SERIES = {"NSE": {"EQ", "BE", "BZ"}, "BSE": {"A", "B", "T"}}
PROVIDERS = ("tejhq_eod", "tejhq_hf_parquet")


class OfficialBhavcopyError(RuntimeError):
    """An official exchange report could not be safely retrieved or parsed."""


def official_bhavcopy_url(exchange: str, trading_date: date) -> str:
    market = exchange.strip().upper()
    if market == "NSE":
        return (
            "https://nsearchives.nseindia.com/content/cm/"
            f"BhavCopy_NSE_CM_0_0_0_{trading_date:%Y%m%d}_F_0000.csv.zip"
        )
    if market == "BSE":
        return (
            "https://www.bseindia.com/download/BhavCopy/Equity/"
            f"BhavCopy_BSE_CM_0_0_0_{trading_date:%Y%m%d}_F_0000.CSV"
        )
    raise ValueError("Exchange must be NSE or BSE")


def parse_udiff_csv(payload: bytes, *, exchange: str, trading_date: date) -> list[dict[str, Any]]:
    market = exchange.strip().upper()
    if market not in SERIES:
        raise ValueError("Exchange must be NSE or BSE")
    raw = payload
    if market == "NSE":
        try:
            with zipfile.ZipFile(io.BytesIO(payload)) as archive:
                names = [name for name in archive.namelist() if name.lower().endswith(".csv")]
                if len(names) != 1:
                    raise OfficialBhavcopyError("NSE UDiFF archive must contain exactly one CSV")
                raw = archive.read(names[0])
        except (zipfile.BadZipFile, OSError) as error:
            raise OfficialBhavcopyError("NSE UDiFF archive is corrupt") from error
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise OfficialBhavcopyError("Official bhavcopy encoding is not UTF-8") from error

    reader = csv.DictReader(io.StringIO(text))
    if not reader.fieldnames:
        raise OfficialBhavcopyError("Official bhavcopy has no CSV header")
    aliases = {
        "date": ("TradDt", "TRADDT", "TRADE_DATE", "TIMESTAMP"),
        "symbol": ("TckrSymb", "TCKRSYMB", "SYMBOL"),
        "series": ("SctySrs", "SCTYSRS", "SERIES"),
        "isin": ("ISIN", "ISINCode"),
        "open": ("OpnPric", "OPNPRIC", "OPEN"),
        "high": ("HghPric", "HGHPRIC", "HIGH"),
        "low": ("LwPric", "LWPRIC", "LOW"),
        "close": ("ClsPric", "CLSPRIC", "CLOSE"),
        "last": ("LastPric", "LASTPRIC", "LAST"),
        "previous_close": ("PrvsClsgPric", "PRVSCLSGPRIC", "PREVCLOSE", "PREV_CLOSE"),
        "volume": ("TtlTradgVol", "TTLTRADGVOL", "TOTTRDQTY", "TOTTRDQTY"),
        "turnover": ("TtlTrfVal", "TTLTRFVAL", "TOTTRDVAL"),
        "trades": ("TtlNbOfTxsExctd", "TTLNBofTXSEXCTD", "TOTALTRADES", "NO_OF_TRADES"),
    }
    normalized_headers = {str(name).strip().upper(): name for name in reader.fieldnames}
    selected = {
        key: next((normalized_headers[candidate.upper()] for candidate in candidates
                   if candidate.upper() in normalized_headers), None)
        for key, candidates in aliases.items()
    }
    required = ("date", "symbol", "series", "open", "high", "low", "close", "volume")
    missing = [field for field in required if selected[field] is None]
    if missing:
        raise OfficialBhavcopyError("Official bhavcopy is missing required UDiFF columns: " + ", ".join(missing))

    source_rows: list[dict[str, Any]] = []
    for item in reader:
        series = str(item.get(selected["series"]) or "").strip().upper()
        if series not in SERIES[market]:
            continue
        date_value = str(item.get(selected["date"]) or "").strip()
        parsed_date = None
        for pattern in ("%Y-%m-%d", "%d-%b-%Y", "%d-%m-%Y", "%d/%m/%Y", "%d-%b-%y"):
            try:
                parsed_date = datetime.strptime(date_value, pattern).date()
                break
            except ValueError:
                continue
        if parsed_date != trading_date:
            raise OfficialBhavcopyError("Official bhavcopy contains a row for a different trading date")
        def value(field: str):
            column = selected[field]
            return item.get(column) if column else None
        source_rows.append({
            "symbol": value("symbol"), "series": series, "isin": value("isin"),
            "open": value("open"), "high": value("high"), "low": value("low"),
            "close": value("close"), "last": value("last"),
            "prev_close": value("previous_close"), "volume": value("volume"),
            "turnover": value("turnover"), "trades": value("trades"),
        })
    if not source_rows:
        raise OfficialBhavcopyError("Official bhavcopy contains no supported equity rows")
    return normalize_snapshot_rows(
        source_rows, exchange=market, trading_date=trading_date,
        provider=f"official_{market.lower()}_bhavcopy",
        retrieved_at=datetime.now(timezone.utc),
    )


async def download_official_bhavcopy(exchange: str, trading_date: date) -> tuple[str, bytes]:
    url = official_bhavcopy_url(exchange, trading_date)
    headers = {
        "User-Agent": "Mozilla/5.0 (compatible; RovexQuant-Research/1.0)",
        "Accept": "text/csv,application/zip,application/octet-stream,*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Referer": "https://www.nseindia.com/" if exchange.upper() == "NSE" else "https://www.bseindia.com/",
    }
    try:
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(30.0, connect=8.0), follow_redirects=True, headers=headers
        ) as client:
            response = await client.get(url)
    except httpx.HTTPError as error:
        raise OfficialBhavcopyError("Official exchange report is temporarily unreachable") from error
    if response.status_code == 404:
        raise OfficialBhavcopyError("Official exchange report is not published for this date (possibly a holiday)")
    if response.status_code >= 400:
        raise OfficialBhavcopyError(f"Official exchange report returned HTTP {response.status_code}")
    if not response.content or len(response.content) > 25 * 1024 * 1024:
        raise OfficialBhavcopyError("Official exchange report is empty or exceeds the 25 MB safety limit")
    if market == "BSE" and response.content.lstrip()[:64].lower().startswith((b"<!doctype", b"<html", b"<meta")):
        raise OfficialBhavcopyError("BSE bhavcopy is not published yet; the exchange returned its HTML page")
    return url, response.content


def compare_official_rows(official: list[dict[str, Any]], reference: list[dict[str, Any]]) -> dict[str, Any]:
    """Compare rows on exchange/symbol/series; missing fields are not silently treated as matches."""
    official_by_key = {(r["symbol"], r["series"]): r for r in official}
    reference_by_key = {(r["symbol"], r["series"]): r for r in reference}
    diffs: list[dict[str, Any]] = []
    matched = 0
    for key in sorted(official_by_key.keys() & reference_by_key.keys()):
        source, stored = official_by_key[key], reference_by_key[key]
        row_diffs = []
        for field in PRICE_FIELDS:
            expected, actual = source.get(field), stored.get(field)
            if expected is None or actual is None:
                continue
            difference = abs(float(expected) - float(actual))
            if difference > 0.01:
                row_diffs.append({
                    "kind": "VALUE_MISMATCH", "symbol": key[0], "series": key[1],
                    "field": field, "official_value": str(expected), "stored_value": str(actual),
                    "absolute_difference": difference,
                })
        if int(source["volume"]) != int(stored["volume"]):
            row_diffs.append({
                "kind": "VALUE_MISMATCH", "symbol": key[0], "series": key[1],
                "field": "volume", "official_value": str(source["volume"]),
                "stored_value": str(stored["volume"]), "absolute_difference":
                    abs(int(source["volume"]) - int(stored["volume"])),
            })
        for field in ("trades",):
            if source.get(field) is not None and stored.get(field) is not None and int(source[field]) != int(stored[field]):
                row_diffs.append({
                    "kind": "VALUE_MISMATCH", "symbol": key[0], "series": key[1],
                    "field": field, "official_value": str(source[field]),
                    "stored_value": str(stored[field]), "absolute_difference":
                        abs(int(source[field]) - int(stored[field])),
                })
        if source.get("isin") and stored.get("isin") and source["isin"] != stored["isin"]:
            row_diffs.append({
                "kind": "IDENTIFIER_MISMATCH", "symbol": key[0], "series": key[1],
                "field": "isin", "official_value": source["isin"], "stored_value": stored["isin"],
                "absolute_difference": None,
            })
        if row_diffs:
            diffs.extend(row_diffs)
        else:
            matched += 1

    missing_reference = sorted(official_by_key.keys() - reference_by_key.keys())
    missing_official = sorted(reference_by_key.keys() - official_by_key.keys())
    for symbol, series in missing_reference:
        diffs.append({"kind": "MISSING_REFERENCE", "symbol": symbol, "series": series,
                      "field": None, "official_value": "present", "stored_value": "missing",
                      "absolute_difference": None})
    for symbol, series in missing_official:
        diffs.append({"kind": "MISSING_OFFICIAL", "symbol": symbol, "series": series,
                      "field": None, "official_value": "missing", "stored_value": "present",
                      "absolute_difference": None})
    mismatch_keys = {(d["symbol"], d["series"]) for d in diffs if d["kind"] in {"VALUE_MISMATCH", "IDENTIFIER_MISMATCH"}}
    return {
        "official_rows": len(official_by_key), "reference_rows": len(reference_by_key),
        "matched_rows": matched, "mismatch_rows": len(mismatch_keys),
        "missing_reference_rows": len(missing_reference), "missing_official_rows": len(missing_official),
        "diffs": diffs,
    }


async def reconcile_exchange_date(exchange: str, trading_date: date) -> dict[str, Any]:
    market = exchange.strip().upper()
    source_url, payload = await download_official_bhavcopy(market, trading_date)
    source_sha256 = hashlib.sha256(payload).hexdigest()
    official = parse_udiff_csv(payload, exchange=market, trading_date=trading_date)
    with connect() as connection:
        rows = connection.execute(
            """
            SELECT DISTINCT ON (symbol, series)
                symbol, series, open, high, low, close, previous_close, volume, trades, isin, provider
            FROM market_eod_bars
            WHERE exchange=%s AND trading_date=%s AND provider = ANY(%s)
            ORDER BY symbol, series,
                CASE WHEN provider='tejhq_hf_parquet' THEN 0 ELSE 1 END,
                source_retrieved_at DESC
            """,
            (market, trading_date, list(PROVIDERS)),
        ).fetchall()
        reference = [{
            "symbol": row[0], "series": row[1], "open": float(row[2]), "high": float(row[3]),
            "low": float(row[4]), "close": float(row[5]),
            "previous_close": float(row[6]) if row[6] is not None else None,
            "volume": int(row[7]), "trades": int(row[8]) if row[8] is not None else None,
            "isin": row[9], "provider": row[10],
        } for row in rows]
        official_by_key = {(row["symbol"], row["series"]): row for row in official}
        reference_by_key = {(row["symbol"], row["series"]): row for row in reference}
        summary = compare_official_rows(official, reference)
        status = "NO_REFERENCE_DATA" if not reference else (
            "MATCHED" if not summary["diffs"] else "MISMATCHED"
        )
        with connection.transaction():
            saved = connection.execute(
                """
                INSERT INTO market_reconciliation_reports
                    (report_id, exchange, trading_date, provider, source_url, source_sha256,
                     status, official_rows, reference_rows, matched_rows, mismatch_rows,
                     missing_reference_rows, missing_official_rows, created_at)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT (exchange, trading_date, source_sha256) DO UPDATE SET
                    provider=EXCLUDED.provider, source_url=EXCLUDED.source_url,
                    status=EXCLUDED.status, official_rows=EXCLUDED.official_rows,
                    reference_rows=EXCLUDED.reference_rows, matched_rows=EXCLUDED.matched_rows,
                    mismatch_rows=EXCLUDED.mismatch_rows,
                    missing_reference_rows=EXCLUDED.missing_reference_rows,
                    missing_official_rows=EXCLUDED.missing_official_rows,
                    created_at=EXCLUDED.created_at
                RETURNING report_id
                """,
                (uuid4(), market, trading_date, f"official_{market.lower()}_bhavcopy",
                 source_url, source_sha256, status, summary["official_rows"],
                 summary["reference_rows"], summary["matched_rows"], summary["mismatch_rows"],
                 summary["missing_reference_rows"], summary["missing_official_rows"],
                 datetime.now(timezone.utc)),
            ).fetchone()
            report_id = saved[0]
            connection.execute(
                "DELETE FROM market_reconciliation_diffs WHERE report_id=%s", (report_id,)
            )
            if summary["diffs"]:
                connection.cursor().executemany(
                    """
                    INSERT INTO market_reconciliation_diffs
                        (report_id, kind, symbol, series, field, official_value, stored_value, absolute_difference)
                    VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
                    """,
                    [(report_id, diff["kind"], diff["symbol"], diff["series"], diff["field"],
                      diff["official_value"], diff["stored_value"], diff["absolute_difference"])
                     for diff in summary["diffs"]],
                )
            diff_keys = {(item["symbol"], item["series"]) for item in summary["diffs"]}
            verified = [
                (market, trading_date, key[0], key[1], reference_by_key[key]["provider"])
                for key in official_by_key.keys() & reference_by_key.keys()
                if key not in diff_keys
            ]
            if verified:
                connection.cursor().executemany(
                    """
                    UPDATE market_eod_bars
                    SET quality_status='VERIFIED_SOURCE'
                    WHERE exchange=%s AND trading_date=%s AND symbol=%s AND series=%s AND provider=%s
                    """,
                    verified,
                )
    return {
        "report_id": str(report_id), "exchange": market, "trading_date": trading_date.isoformat(),
        "source_url": source_url, "source_sha256": source_sha256, "status": status, **summary,
    }
