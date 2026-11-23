"""Resumable, provenance-preserving imports from the public TejHQ Parquet archive.

DuckDB is an optional operator dependency, installed only by the dedicated workflow.
The backend runtime does not gain a heavyweight Parquet dependency.
"""
from __future__ import annotations

import hashlib
import tempfile
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

import httpx

from rovexquant.market_data.eod_store import connect, normalize_snapshot_rows

HF_DATASET = "https://huggingface.co/datasets/tejhq/indian-markets/resolve/main"
MAX_PARQUET_BYTES = 512 * 1024 * 1024
BATCH_ROWS = 2_000
PROVIDER = "tejhq_hf_parquet"

_INSERT_HISTORY = """
INSERT INTO market_eod_bars (
 exchange, trading_date, symbol, series, isin, company_name, open, high, low, close,
 previous_close, volume, trades, turnover, provider, data_mode, quality_status, source_retrieved_at
) VALUES (
 %(exchange)s, %(trading_date)s, %(symbol)s, %(series)s, %(isin)s, %(company_name)s, %(open)s,
 %(high)s, %(low)s, %(close)s, %(previous_close)s, %(volume)s, %(trades)s, %(turnover)s,
 %(provider)s, 'END_OF_DAY', 'PROVISIONAL', %(source_retrieved_at)s
)
ON CONFLICT (exchange, trading_date, symbol, series, provider) DO UPDATE SET
 isin=EXCLUDED.isin, company_name=EXCLUDED.company_name, open=EXCLUDED.open, high=EXCLUDED.high,
 low=EXCLUDED.low, close=EXCLUDED.close, previous_close=EXCLUDED.previous_close,
 volume=EXCLUDED.volume, trades=EXCLUDED.trades, turnover=EXCLUDED.turnover,
 quality_status='PROVISIONAL', source_retrieved_at=EXCLUDED.source_retrieved_at
"""


class HistoricalBackfillError(RuntimeError):
    """Safe historical import failure."""


def validate_exchange_year(exchange: str, year: int, *, current_year: int | None = None) -> str:
    market = exchange.strip().upper()
    if market not in {"NSE", "BSE"}:
        raise ValueError("Exchange must be NSE or BSE")
    latest = current_year or datetime.now(timezone.utc).year
    earliest = 2010 if market == "NSE" else 2024
    if isinstance(year, bool) or not earliest <= year <= latest:
        raise ValueError(f"{market} Parquet history supports years {earliest} through {latest}")
    return market


def parquet_source_url(exchange: str, year: int) -> str:
    market = validate_exchange_year(exchange, year).lower()
    return f"{HF_DATASET}/{market}/year%3D{year}/{market}_{year}.parquet"


def _download_parquet(url: str) -> tuple[Path, str]:
    digest = hashlib.sha256()
    size = 0
    handle = tempfile.NamedTemporaryFile(prefix="rovexquant-history-", suffix=".parquet", delete=False)
    path = Path(handle.name)
    try:
        with handle, httpx.Client(
            timeout=httpx.Timeout(120.0, connect=15.0),
            follow_redirects=True,
            headers={"User-Agent": "RovexQuant-Personal-Research/1.0", "Accept": "application/octet-stream"},
        ) as client:
            with client.stream("GET", url) as response:
                if response.status_code == 404:
                    raise HistoricalBackfillError("The requested exchange/year Parquet file is not published")
                response.raise_for_status()
                declared = response.headers.get("content-length")
                if declared and int(declared) > MAX_PARQUET_BYTES:
                    raise HistoricalBackfillError("Parquet file exceeds the 512 MB safety limit")
                for chunk in response.iter_bytes(1024 * 1024):
                    size += len(chunk)
                    if size > MAX_PARQUET_BYTES:
                        raise HistoricalBackfillError("Parquet download exceeded the 512 MB safety limit")
                    digest.update(chunk)
                    handle.write(chunk)
        if size < 8:
            raise HistoricalBackfillError("Downloaded Parquet file is empty or truncated")
        return path, digest.hexdigest()
    except Exception:
        path.unlink(missing_ok=True)
        raise


def _record_run(connection, exchange: str, year: int, source_url: str, source_sha256: str) -> str:
    row = connection.execute(
        """
        INSERT INTO market_historical_backfill_runs
            (run_id, exchange, year, source_url, source_sha256, status, started_at)
        VALUES (%s, %s, %s, %s, %s, 'RUNNING', %s)
        ON CONFLICT (exchange, year, source_sha256) DO UPDATE SET
            status='RUNNING', started_at=EXCLUDED.started_at, completed_at=NULL,
            error_message=NULL
        RETURNING run_id
        """,
        (uuid4(), exchange, year, source_url, source_sha256, datetime.now(timezone.utc)),
    ).fetchone()
    return str(row[0])


def _persist_day(connection, *, run_id: str, exchange: str, year: int, source_sha256: str,
                 trading_date: date, source_rows: list[dict[str, Any]]) -> int:
    existing = connection.execute(
        """
        SELECT status FROM market_historical_backfill_days
        WHERE exchange=%s AND year=%s AND trading_date=%s AND source_sha256=%s
        """,
        (exchange, year, trading_date, source_sha256),
    ).fetchone()
    if existing and existing[0] == "SUCCEEDED":
        return 0

    normalized = normalize_snapshot_rows(
        source_rows, exchange=exchange, trading_date=trading_date,
        provider=PROVIDER, retrieved_at=datetime.now(timezone.utc),
    )
    with connection.transaction():
        connection.execute(
            "DELETE FROM market_eod_bars WHERE exchange=%s AND trading_date=%s AND provider=%s",
            (exchange, trading_date, PROVIDER),
        )
        with connection.cursor() as cursor:
            cursor.executemany(_INSERT_HISTORY, normalized)
            cursor.execute(
                """
                INSERT INTO market_historical_backfill_days
                    (run_id, exchange, year, trading_date, source_sha256, status, rows_upserted, completed_at)
                VALUES (%s, %s, %s, %s, %s, 'SUCCEEDED', %s, NOW())
                ON CONFLICT (exchange, year, trading_date, source_sha256) DO UPDATE SET
                    run_id=EXCLUDED.run_id, status='SUCCEEDED',
                    rows_upserted=EXCLUDED.rows_upserted, completed_at=NOW()
                """,
                (run_id, exchange, year, trading_date, source_sha256, len(normalized)),
            )
    return len(normalized)


def backfill_parquet_year(exchange: str, year: int) -> dict[str, Any]:
    """Import one yearly Parquet file; each trading date commits with its checkpoint."""
    market = validate_exchange_year(exchange, year)
    try:
        import duckdb
    except ImportError as error:
        raise HistoricalBackfillError(
            "DuckDB is required only for this operator workflow; install the pinned historical extra"
        ) from error

    source_url = parquet_source_url(market, year)
    path, source_sha256 = _download_parquet(source_url)
    rows_seen = rows_upserted = days_seen = days_skipped = 0
    run_id = ""
    try:
        with connect() as connection:
            connection.autocommit = True
            run_id = _record_run(connection, market, year, source_url, source_sha256)
            db = duckdb.connect(":memory:")
            try:
                cursor = db.execute(
                    """
                    SELECT CAST(date AS VARCHAR) AS trading_date, symbol, series, isin, name,
                           open, high, low, close, prev_close, volume, turnover, trades
                    FROM read_parquet(?)
                    ORDER BY date, symbol, series
                    """,
                    [str(path)],
                )
                columns = [item[0] for item in cursor.description]
                pending_date: date | None = None
                pending: list[dict[str, Any]] = []

                def flush_day() -> None:
                    nonlocal rows_upserted, days_seen, days_skipped, pending, pending_date
                    if pending_date is None or not pending:
                        return
                    days_seen += 1
                    inserted = _persist_day(
                        connection, run_id=run_id, exchange=market, year=year,
                        source_sha256=source_sha256, trading_date=pending_date,
                        source_rows=pending,
                    )
                    if inserted:
                        rows_upserted += inserted
                    else:
                        days_skipped += 1
                    pending = []

                while True:
                    batch = cursor.fetchmany(BATCH_ROWS)
                    if not batch:
                        break
                    for values in batch:
                        row = dict(zip(columns, values, strict=True))
                        trading_date = date.fromisoformat(str(row.pop("trading_date")))
                        rows_seen += 1
                        if pending_date is not None and trading_date != pending_date:
                            flush_day()
                        pending_date = trading_date
                        pending.append({
                            "symbol": row["symbol"], "series": row["series"], "isin": row["isin"],
                            "name": row["name"], "open": row["open"], "high": row["high"],
                            "low": row["low"], "close": row["close"],
                            "prev_close": row["prev_close"], "volume": row["volume"],
                            "turnover": row["turnover"], "trades": row["trades"],
                        })
                flush_day()
                if rows_seen == 0:
                    raise HistoricalBackfillError("Parquet archive contained no market rows")
            finally:
                db.close()
            connection.execute(
                """
                UPDATE market_historical_backfill_runs
                SET status='SUCCEEDED', rows_seen=%s, rows_upserted=%s,
                    completed_at=NOW(), error_message=NULL
                WHERE run_id=%s
                """,
                (rows_seen, rows_upserted, run_id),
            )
    except Exception as error:
        if run_id:
            try:
                with connect() as connection:
                    connection.execute(
                        """
                        UPDATE market_historical_backfill_runs
                        SET status='FAILED', rows_seen=%s, rows_upserted=%s,
                            completed_at=NOW(), error_message=%s
                        WHERE run_id=%s
                        """,
                        (rows_seen, rows_upserted, str(error)[:240], run_id),
                    )
            except Exception:
                pass
        raise
    finally:
        path.unlink(missing_ok=True)

    return {
        "run_id": run_id, "exchange": market, "year": year,
        "provider": PROVIDER, "source_url": source_url, "source_sha256": source_sha256,
        "rows_seen": rows_seen, "rows_upserted": rows_upserted,
        "days_seen": days_seen, "days_skipped": days_skipped, "status": "SUCCEEDED",
        "data_mode": "END_OF_DAY", "quality_status": "PROVISIONAL",
    }
