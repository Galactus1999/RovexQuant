"""Validated EOD snapshot normalization and PostgreSQL persistence."""
from __future__ import annotations

from datetime import date, datetime, timezone
import math
import os
import re
from typing import Any, Iterable

import psycopg

from rovexquant.db.schema import EXPECTED_SCHEMA_REVISION

_SYMBOL = re.compile(r"^[A-Z0-9][A-Z0-9&._-]{0,39}$")
_SERIES = {"NSE": frozenset({"EQ", "BE", "BZ"}), "BSE": frozenset({"A", "B", "T"})}
_INSERT_BAR = """
INSERT INTO market_eod_bars (
 exchange, trading_date, symbol, series, isin, company_name, open, high, low, close,
 previous_close, volume, trades, turnover, provider, data_mode, quality_status, source_retrieved_at
) VALUES (
 %(exchange)s, %(trading_date)s, %(symbol)s, %(series)s, %(isin)s, %(company_name)s, %(open)s,
 %(high)s, %(low)s, %(close)s, %(previous_close)s, %(volume)s, %(trades)s, %(turnover)s,
 %(provider)s, 'END_OF_DAY', %(quality_status)s, %(source_retrieved_at)s
)
ON CONFLICT (exchange, trading_date, symbol, series, provider) DO UPDATE SET
 isin=EXCLUDED.isin, company_name=EXCLUDED.company_name, open=EXCLUDED.open, high=EXCLUDED.high,
 low=EXCLUDED.low, close=EXCLUDED.close, previous_close=EXCLUDED.previous_close,
 volume=EXCLUDED.volume, trades=EXCLUDED.trades, turnover=EXCLUDED.turnover,
 quality_status=EXCLUDED.quality_status, source_retrieved_at=EXCLUDED.source_retrieved_at
"""


class EodStoreError(RuntimeError):
    """Safe storage/configuration error without connection details."""

    def __init__(self, message: str, status_code: int = 503):
        super().__init__(message)
        self.status_code = status_code


def database_url() -> str:
    value = os.getenv("QUANT_MARKET_DATABASE_URL", "").strip() or os.getenv(
        "QUANT_TOKEN_DATABASE_URL", ""
    ).strip()
    if not value:
        raise EodStoreError("Market database is not configured")
    if os.getenv("QUANT_ENV") != "test" and not re.search(
        r"(?:[?&])sslmode=(?:require|verify-ca|verify-full)(?:&|$)",
        value,
        re.IGNORECASE,
    ):
        raise EodStoreError("Market database URL must explicitly require TLS")
    return value


def connect():
    try:
        return psycopg.connect(database_url(), connect_timeout=8)
    except psycopg.Error as error:
        raise EodStoreError("Market database connection failed") from error


def validate_market_schema() -> None:
    """Fail closed when the separate market database has not reached Alembic head."""
    market_url = os.getenv("QUANT_MARKET_DATABASE_URL", "").strip()
    token_url = os.getenv("QUANT_TOKEN_DATABASE_URL", "").strip()
    if not market_url or market_url == token_url:
        return
    try:
        with connect() as connection:
            row = connection.execute("SELECT version_num FROM alembic_version").fetchone()
    except (EodStoreError, psycopg.Error) as error:
        raise EodStoreError(
            "Market PostgreSQL schema is unavailable; apply the market database migrations"
        ) from error
    if not row or row[0] != EXPECTED_SCHEMA_REVISION:
        raise EodStoreError(
            "Market PostgreSQL schema revision does not match this application; apply migrations"
        )


def _number(value: Any, field: str, *, optional: bool = False) -> float | None:
    if value is None and optional:
        return None
    if isinstance(value, bool):
        raise EodStoreError(f"Invalid {field} in EOD source row")
    try:
        result = float(str(value).replace(",", "").strip())
    except (TypeError, ValueError) as error:
        raise EodStoreError(f"Invalid {field} in EOD source row") from error
    if not math.isfinite(result):
        raise EodStoreError(f"Non-finite {field} in EOD source row")
    return result


def normalize_snapshot_rows(
    rows: Iterable[dict[str, Any]], *, exchange: str, trading_date: date,
    provider: str, retrieved_at: datetime | None = None,
) -> list[dict[str, Any]]:
    """Normalize allowed equity series and reject malformed or duplicate priced rows."""
    market = exchange.strip().upper()
    if market not in _SERIES:
        raise EodStoreError("Exchange must be NSE or BSE", 422)
    if not provider or len(provider) > 64:
        raise EodStoreError("Invalid provider identifier")
    timestamp = retrieved_at or datetime.now(timezone.utc)
    if timestamp.tzinfo is None:
        raise EodStoreError("Retrieval timestamp must be timezone-aware")

    output: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for row in rows:
        if not isinstance(row, dict):
            raise EodStoreError("EOD source returned a non-object row")
        series = str(row.get("series") or "").strip().upper()
        if series not in _SERIES[market]:
            continue
        symbol = str(row.get("symbol") or row.get("symbol_code") or "").strip().upper()
        if not _SYMBOL.fullmatch(symbol):
            raise EodStoreError("EOD source returned an invalid equity symbol")
        identity = (symbol, series)
        if identity in seen:
            raise EodStoreError("EOD source returned duplicate symbol/series rows")
        seen.add(identity)

        opening = _number(row.get("open"), "open")
        high = _number(row.get("high"), "high")
        low = _number(row.get("low"), "low")
        close = _number(row.get("close"), "close")
        volume_value = _number(row.get("volume"), "volume")
        previous = _number(row.get("prev_close", row.get("previous_close")),
                           "previous_close", optional=True)
        turnover = _number(row.get("turnover"), "turnover", optional=True)
        if min(opening, high, low, close) <= 0:
            raise EodStoreError("EOD source returned non-positive OHLC values")
        if low > min(opening, close) or high < max(opening, close) or low > high:
            raise EodStoreError("EOD source returned inconsistent OHLC values")
        if volume_value < 0 or not volume_value.is_integer():
            raise EodStoreError("EOD source returned invalid volume")
        if previous is not None and previous <= 0:
            previous = None
        if turnover is not None and turnover < 0:
            turnover = None
        output.append({
            "exchange": market, "trading_date": trading_date, "symbol": symbol,
            "series": series,
            "isin": (
                str(row["isin"]).strip().upper()
                if isinstance(row.get("isin"), str)
                and re.fullmatch(r"[A-Z]{2}[A-Z0-9]{9}[0-9]", row["isin"].strip().upper())
                else None
            ),
            "trades": (
                int(row["trades"])
                if row.get("trades") is not None and str(row["trades"]).strip().isdigit()
                else None
            ),
            "company_name": str(row.get("name") or row.get("company_name") or symbol)[:160],
            "open": opening, "high": high, "low": low, "close": close,
            "previous_close": previous, "volume": int(volume_value), "turnover": turnover,
            "provider": provider, "quality_status": "PROVISIONAL",
            "source_retrieved_at": timestamp,
        })
    if not output:
        raise EodStoreError("EOD source returned no supported equity rows")
    return output


def start_run(*, run_id: str, provider: str, exchange: str, started_at: datetime) -> None:
    with connect() as connection:
        connection.execute("""
            INSERT INTO market_ingestion_runs
                (run_id, provider, exchange, status, started_at)
            VALUES (%s, %s, %s, 'RUNNING', %s)
        """, (run_id, provider, exchange.upper(), started_at))


def persist_snapshot(
    rows: list[dict[str, Any]], *, run_id: str, trading_date: date,
    rows_received: int | None = None,
) -> int:
    """Atomically upsert a snapshot and transition its run to SUCCEEDED."""
    if not rows:
        raise EodStoreError("Refusing to persist an empty EOD snapshot")
    with connect() as connection:
        with connection.cursor() as cursor:
            cursor.executemany(_INSERT_BAR, rows)
            cursor.execute("""
                UPDATE market_ingestion_runs
                SET status='SUCCEEDED', trading_date=%s, rows_received=%s,
                    rows_skipped=%s, rows_upserted=%s, completed_at=%s,
                    error_code=NULL, error_message=NULL
                WHERE run_id=%s AND status='RUNNING'
            """, (trading_date, rows_received if rows_received is not None else len(rows),
             max(0, (rows_received if rows_received is not None else len(rows)) - len(rows)),
             len(rows), datetime.now(timezone.utc), run_id))
            if cursor.rowcount != 1:
                raise EodStoreError("Ingestion run state changed before commit")
    return len(rows)


def fail_run(*, run_id: str, code: str, message: str) -> None:
    safe_message = re.sub(
        r"(?i)(token|password|secret|authorization)\s*[:=]\s*\S+",
        r"\1=[REDACTED]", message,
    )[:240]
    with connect() as connection:
        connection.execute("""
            UPDATE market_ingestion_runs
            SET status='FAILED', completed_at=%s, error_code=%s, error_message=%s
            WHERE run_id=%s AND status='RUNNING'
        """, (datetime.now(timezone.utc), code[:64], safe_message, run_id))


def latest_ingestion_status(exchange: str = "NSE") -> dict[str, Any]:
    market = exchange.strip().upper()
    if market not in _SERIES:
        raise EodStoreError("Exchange must be NSE or BSE")
    try:
        with connect() as connection:
            row = connection.execute("""
                SELECT latest.provider, latest.trading_date, latest.status,
                       latest.rows_received, latest.rows_skipped, latest.rows_upserted,
                       latest.started_at, latest.completed_at, latest.error_code,
                       latest.error_message, success.trading_date, success.provider,
                       success.rows_upserted, success.completed_at
                FROM (
                    SELECT provider, trading_date, status, rows_received, rows_skipped,
                           rows_upserted, started_at, completed_at, error_code, error_message
                    FROM market_ingestion_runs
                    WHERE exchange=%s
                    ORDER BY started_at DESC LIMIT 1
                ) AS latest
                LEFT JOIN LATERAL (
                    SELECT trading_date, provider, rows_upserted, completed_at
                    FROM market_ingestion_runs
                    WHERE exchange=%s AND status='SUCCEEDED'
                    ORDER BY completed_at DESC LIMIT 1
                ) AS success ON TRUE
            """, (market, market)).fetchone()
    except psycopg.Error as error:
        raise EodStoreError("Market ingestion schema is unavailable; apply database migrations") from error
    if row is None:
        return {
            "exchange": market, "status": "NOT_RUN", "trading_date": None,
            "last_successful_trading_date": None, "last_successful_provider": None,
            "last_successful_rows": 0, "last_successful_at": None,
        }
    keys = ("provider", "trading_date", "status", "rows_received", "rows_skipped",
            "rows_upserted", "started_at", "completed_at", "error_code", "error_message",
            "last_successful_trading_date", "last_successful_provider",
            "last_successful_rows", "last_successful_at")
    return {"exchange": market, **dict(zip(keys, row, strict=True))}


def latest_persisted_snapshot(exchange: str = "NSE", *, limit: int = 20) -> dict[str, Any]:
    """Build the Market Pulse snapshot only from the latest successful PostgreSQL batch."""
    market = exchange.strip().upper()
    if market not in _SERIES:
        raise EodStoreError("Exchange must be NSE or BSE", 422)
    if not 1 <= limit <= 50:
        raise EodStoreError("Snapshot limit must be between 1 and 50", 422)
    try:
        with connect() as connection:
            run = connection.execute("""
                SELECT trading_date, provider, completed_at
                FROM market_ingestion_runs
                WHERE exchange=%s AND status='SUCCEEDED' AND trading_date IS NOT NULL
                ORDER BY completed_at DESC LIMIT 1
            """, (market,)).fetchone()
            if run is None:
                raise EodStoreError("No successful PostgreSQL EOD batch is available yet", 404)
            trading_date, provider, completed_at = run
            stored_rows = connection.execute("""
                SELECT symbol, company_name, series, close, previous_close, volume,
                       turnover, quality_status, source_retrieved_at
                FROM market_eod_bars
                WHERE exchange=%s AND trading_date=%s AND provider=%s
                ORDER BY symbol
            """, (market, trading_date, provider)).fetchall()
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError(
            "Persisted market snapshot is unavailable; verify PostgreSQL migrations and connectivity"
        ) from error
    if not stored_rows:
        raise EodStoreError(
            "The latest successful ingestion run has no matching persisted market rows", 503
        )

    rows = [{
        "symbol": row[0],
        "name": row[1],
        "series": row[2],
        "close": float(row[3]),
        "prev_close": float(row[4]) if row[4] is not None else None,
        "volume": int(row[5]),
        "turnover": float(row[6]) if row[6] is not None else None,
    } for row in stored_rows]
    from rovexquant.market_data.tejhq_service import summarize_snapshot_rows

    breadth_series = frozenset({"EQ"}) if market == "NSE" else _SERIES[market]
    summary = summarize_snapshot_rows(
        rows, limit=limit, allowed_series=breadth_series
    )
    quality = {row[7] for row in stored_rows}
    return {
        "provider": provider,
        "data_mode": "END_OF_DAY",
        "data_origin": "POSTGRESQL",
        "exchange": market,
        "market_scope": "NSE_EQ" if market == "NSE" else "BSE_EQ",
        "trading_date": trading_date.isoformat(),
        "completed_at": completed_at.isoformat() if completed_at else None,
        "quality_status": "PROVISIONAL" if "PROVISIONAL" in quality else "VERIFIED_SOURCE",
        "source_retrieved_at": max(row[8] for row in stored_rows).isoformat(),
        "summary": summary,
    }


def symbol_history(exchange: str, symbol: str, *, from_date: date, to_date: date,
                   limit: int = 1000) -> list[dict[str, Any]]:
    market, ticker = exchange.strip().upper(), symbol.strip().upper()
    if market not in _SERIES or not _SYMBOL.fullmatch(ticker):
        raise EodStoreError("Invalid exchange or symbol", 422)
    if from_date > to_date or (to_date - from_date).days > 6200:
        raise EodStoreError("Historical date range must be ordered and at most 17 years", 422)
    if not 1 <= limit <= 2000:
        raise EodStoreError("History limit must be between 1 and 2000", 422)
    try:
        with connect() as connection:
            rows = connection.execute("""
                SELECT DISTINCT ON (bar.trading_date)
                       bar.trading_date, bar.open, bar.high, bar.low,
                       COALESCE(adjusted.adjusted_close, bar.close) AS close,
                       bar.previous_close, bar.volume, bar.turnover, bar.provider,
                       bar.quality_status, bar.source_retrieved_at, bar.isin, bar.trades,
                       CASE WHEN adjusted.adjusted_close IS NULL
                            THEN 'RAW_CLOSE' ELSE 'CORPORATE_ACTION_ADJUSTED' END AS price_basis,
                       adjusted.adjustment_factor
                FROM market_eod_bars AS bar
                LEFT JOIN market_eod_adjusted_bars AS adjusted
                  ON adjusted.exchange=bar.exchange
                 AND adjusted.trading_date=bar.trading_date
                 AND adjusted.symbol=bar.symbol
                 AND adjusted.series=bar.series
                 AND adjusted.provider='tejhq_hf_parquet'
                WHERE bar.exchange=%s AND bar.symbol=%s
                  AND bar.trading_date BETWEEN %s AND %s
                ORDER BY bar.trading_date, adjusted.source_retrieved_at DESC NULLS LAST,
                         bar.source_retrieved_at DESC, bar.provider
                LIMIT %s
            """, (market, ticker, from_date, to_date, limit)).fetchall()
    except psycopg.Error as error:
        raise EodStoreError("Market history schema is unavailable; apply database migrations") from error
    return [{
        "date": row[0].isoformat(), "open": float(row[1]), "high": float(row[2]),
        "low": float(row[3]), "close": float(row[4]),
        "previous_close": float(row[5]) if row[5] is not None else None,
        "volume": int(row[6]), "turnover": float(row[7]) if row[7] is not None else None,
        "provider": row[8], "quality_status": row[9],
        "retrieved_at": row[10].isoformat(), "data_mode": "END_OF_DAY",
        "isin": row[11], "trades": int(row[12]) if row[12] is not None else None,
        "price_basis": row[13], "adjustment_factor": float(row[14]) if row[14] is not None else None,
    } for row in rows]
