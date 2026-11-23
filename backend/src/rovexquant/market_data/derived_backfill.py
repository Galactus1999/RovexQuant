"""Import adjusted prices, corporate actions and symbol identity history from TejHQ."""
from __future__ import annotations

import math
from datetime import date, datetime, timezone
from typing import Any
from uuid import uuid4

from rovexquant.market_data.eod_store import connect
from rovexquant.market_data.historical_backfill import (
    BATCH_ROWS, PROVIDER, _download_parquet, validate_exchange_year, HF_DATASET,
)

_SERIES = {"NSE": {"EQ", "BE", "BZ"}, "BSE": {"A", "B", "T"}}


def derived_source_url(kind: str, exchange: str, year: int) -> str:
    market = validate_exchange_year(exchange, year).lower()
    if kind == "ADJUSTED_PRICES":
        return f"{HF_DATASET}/prices_adjusted/{market}_{year}.parquet"
    if kind == "CORPORATE_ACTIONS":
        return f"{HF_DATASET}/actions/{market}_{year}.parquet"
    if kind == "SYMBOL_HISTORY":
        return f"{HF_DATASET}/symbol_history/{market}.parquet"
    raise ValueError("Unsupported derived dataset kind")


def _optional_float(value: Any) -> float | None:
    if value is None:
        return None
    result = float(value)
    return result if math.isfinite(result) else None


def _optional_int(value: Any) -> int | None:
    return int(value) if value is not None else None


def _date(value: Any) -> date:
    if isinstance(value, date):
        return value
    return date.fromisoformat(str(value))


def _start_run(connection, exchange: str, year: int, kind: str, url: str, digest: str) -> str:
    row = connection.execute(
        """
        INSERT INTO market_derived_backfill_runs
            (run_id, exchange, year, dataset_kind, source_url, source_sha256, status, started_at)
        VALUES (%s,%s,%s,%s,%s,%s,'RUNNING',%s)
        ON CONFLICT (exchange, year, dataset_kind, source_sha256) DO UPDATE SET
            status='RUNNING', started_at=EXCLUDED.started_at, completed_at=NULL, error_message=NULL
        RETURNING run_id
        """,
        (uuid4(), exchange, year, kind, url, digest, datetime.now(timezone.utc)),
    ).fetchone()
    return str(row[0])


def _import_rows(connection, *, market: str, kind: str, year: int, digest: str, path) -> tuple[int, int]:
    try:
        import duckdb
    except ImportError as error:
        raise RuntimeError("Install the pinned DuckDB operator dependency to import derived Parquet files") from error
    db = duckdb.connect(":memory:")
    try:
        if kind == "ADJUSTED_PRICES":
            cursor = db.execute(
                """
                SELECT CAST(date AS VARCHAR) AS trading_date, symbol, series, isin,
                       adj_close, adj_factor_cumulative
                FROM read_parquet(?) ORDER BY date, symbol, series
                """,
                [str(path)],
            )
            rows_seen = rows_upserted = 0
            now = datetime.now(timezone.utc)
            with connection.transaction():
                while batch := cursor.fetchmany(BATCH_ROWS):
                    payload = []
                    for values in batch:
                        trading_date, symbol, series, isin, adj_close, factor = values
                        rows_seen += 1
                        series = str(series or "").strip().upper()
                        if series not in _SERIES[market]:
                            continue
                        adjusted = _optional_float(adj_close)
                        adjustment = _optional_float(factor)
                        if not adjusted or not adjustment or adjusted <= 0 or adjustment <= 0:
                            raise ValueError("Adjusted-price archive contains a non-positive or invalid factor")
                        payload.append((
                            market, _date(trading_date), str(symbol).strip().upper(), series,
                            isin, adjusted, adjustment, PROVIDER, digest, now,
                        ))
                    if payload:
                        connection.cursor().executemany(
                            """
                            INSERT INTO market_eod_adjusted_bars
                                (exchange, trading_date, symbol, series, isin, adjusted_close,
                                 adjustment_factor, provider, source_sha256, source_retrieved_at)
                            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                            ON CONFLICT (exchange, trading_date, symbol, series, provider) DO UPDATE SET
                                isin=EXCLUDED.isin, adjusted_close=EXCLUDED.adjusted_close,
                                adjustment_factor=EXCLUDED.adjustment_factor,
                                source_sha256=EXCLUDED.source_sha256,
                                source_retrieved_at=EXCLUDED.source_retrieved_at
                            """,
                            payload,
                        )
                        rows_upserted += len(payload)
            return rows_seen, rows_upserted

        if kind == "CORPORATE_ACTIONS":
            cursor = db.execute(
                """
                SELECT exchange, symbol, isin, company, CAST(ex_date AS VARCHAR),
                       CAST(record_date AS VARCHAR), type, ratio_num, ratio_den,
                       cash_amount, face_value_from, face_value_to, raw_subject
                FROM read_parquet(?) ORDER BY ex_date, symbol, type
                """,
                [str(path)],
            )
            rows_seen = rows_upserted = 0
            now = datetime.now(timezone.utc)
            with connection.transaction():
                while batch := cursor.fetchmany(BATCH_ROWS):
                    payload = []
                    for values in batch:
                        ex, symbol, isin, company, ex_date, record_date, action_type, ratio_num, ratio_den, cash, face_from, face_to, raw = values
                        rows_seen += 1
                        if str(ex).strip().upper() != market:
                            continue
                        ticker = str(symbol or "").strip().upper()
                        if not ticker:
                            continue
                        payload.append((
                            uuid4(), market, ticker, isin, str(company or "")[:160],
                            _date(ex_date), _date(record_date) if record_date else None,
                            str(action_type or "other").strip().lower()[:40],
                            _optional_int(ratio_num), _optional_int(ratio_den),
                            _optional_float(cash), _optional_float(face_from), _optional_float(face_to),
                            str(raw or ""), PROVIDER, digest, now,
                        ))
                    if payload:
                        connection.cursor().executemany(
                            """
                            INSERT INTO market_corporate_actions
                                (action_id, exchange, symbol, isin, company_name, ex_date, record_date,
                                 action_type, ratio_num, ratio_den, cash_amount, face_value_from,
                                 face_value_to, raw_subject, provider, source_sha256, source_retrieved_at)
                            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                            ON CONFLICT (exchange, symbol, ex_date, action_type, raw_subject, provider)
                            DO UPDATE SET isin=EXCLUDED.isin, company_name=EXCLUDED.company_name,
                                record_date=EXCLUDED.record_date, ratio_num=EXCLUDED.ratio_num,
                                ratio_den=EXCLUDED.ratio_den, cash_amount=EXCLUDED.cash_amount,
                                face_value_from=EXCLUDED.face_value_from,
                                face_value_to=EXCLUDED.face_value_to,
                                source_sha256=EXCLUDED.source_sha256,
                                source_retrieved_at=EXCLUDED.source_retrieved_at
                            """,
                            payload,
                        )
                        rows_upserted += len(payload)
            return rows_seen, rows_upserted

        if kind == "SYMBOL_HISTORY":
            cursor = db.execute(
                """
                SELECT exchange, isin, symbol, CAST(valid_from AS VARCHAR),
                       CAST(valid_to AS VARCHAR), trading_days
                FROM read_parquet(?) ORDER BY valid_from, isin, symbol
                """,
                [str(path)],
            )
            rows_seen = rows_upserted = 0
            now = datetime.now(timezone.utc)
            with connection.transaction():
                while batch := cursor.fetchmany(BATCH_ROWS):
                    payload = []
                    for ex, isin, symbol, valid_from, valid_to, trading_days in batch:
                        rows_seen += 1
                        if str(ex).strip().upper() != market or not isin or not symbol:
                            continue
                        payload.append((
                            market, str(isin).strip().upper(), str(symbol).strip().upper(),
                            _date(valid_from), _date(valid_to) if valid_to else None,
                            max(0, int(trading_days or 0)), PROVIDER, digest, now,
                        ))
                    if payload:
                        connection.cursor().executemany(
                            """
                            INSERT INTO market_symbol_history
                                (exchange, isin, symbol, valid_from, valid_to, trading_days,
                                 provider, source_sha256, source_retrieved_at)
                            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
                            ON CONFLICT (exchange, isin, symbol, valid_from, provider) DO UPDATE SET
                                valid_to=EXCLUDED.valid_to, trading_days=EXCLUDED.trading_days,
                                source_sha256=EXCLUDED.source_sha256,
                                source_retrieved_at=EXCLUDED.source_retrieved_at
                            """,
                            payload,
                        )
                        rows_upserted += len(payload)
            return rows_seen, rows_upserted
    finally:
        db.close()
    raise ValueError("Unsupported derived dataset kind")


def backfill_derived_year(exchange: str, year: int) -> dict[str, Any]:
    market = validate_exchange_year(exchange, year)
    kinds = (
        ("ADJUSTED_PRICES", year),
        ("CORPORATE_ACTIONS", year),
        ("SYMBOL_HISTORY", 0),
    )
    results = []
    for kind, run_year in kinds:
        url = derived_source_url(kind, market, run_year if kind != "SYMBOL_HISTORY" else year)
        path, digest = _download_parquet(url)
        run_id = ""
        try:
            with connect() as connection:
                connection.autocommit = True
                prior = connection.execute(
                    """
                    SELECT run_id, status, rows_seen, rows_upserted
                    FROM market_derived_backfill_runs
                    WHERE exchange=%s AND year=%s AND dataset_kind=%s AND source_sha256=%s
                    """,
                    (market, run_year, kind, digest),
                ).fetchone()
                if prior and prior[1] == "SUCCEEDED":
                    results.append({
                        "dataset_kind": kind, "status": "SKIPPED_ALREADY_IMPORTED",
                        "rows_seen": prior[2], "rows_upserted": prior[3], "source_sha256": digest,
                    })
                    continue
                run_id = _start_run(connection, market, run_year, kind, url, digest)
                try:
                    seen, upserted = _import_rows(
                        connection, market=market, kind=kind, year=run_year, digest=digest, path=path
                    )
                    if seen <= 0:
                        raise ValueError(f"{kind} archive contains no rows")
                    connection.execute(
                        """
                        UPDATE market_derived_backfill_runs
                        SET status='SUCCEEDED', rows_seen=%s, rows_upserted=%s,
                            completed_at=NOW(), error_message=NULL
                        WHERE run_id=%s
                        """,
                        (seen, upserted, run_id),
                    )
                    results.append({
                        "dataset_kind": kind, "status": "SUCCEEDED",
                        "rows_seen": seen, "rows_upserted": upserted, "source_sha256": digest,
                    })
                except Exception as error:
                    connection.execute(
                        """
                        UPDATE market_derived_backfill_runs
                        SET status='FAILED', completed_at=NOW(), error_message=%s
                        WHERE run_id=%s
                        """,
                        (str(error)[:240], run_id),
                    )
                    raise
        finally:
            path.unlink(missing_ok=True)
    return {"exchange": market, "year": year, "provider": PROVIDER, "datasets": results}
