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
                       bar.trading_date,
                       COALESCE(adjusted.adjustment_factor * bar.open, bar.open) AS open,
                       COALESCE(adjusted.adjustment_factor * bar.high, bar.high) AS high,
                       COALESCE(adjusted.adjustment_factor * bar.low, bar.low) AS low,
                       COALESCE(adjusted.adjusted_close, bar.close) AS close,
                       COALESCE(adjusted.adjustment_factor * bar.previous_close, bar.previous_close) AS previous_close,
                       bar.volume, bar.turnover, bar.provider,
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
                 AND adjusted.isin=bar.isin
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


def corporate_action_history(
    exchange: str, symbol: str, *, from_date: date, to_date: date, limit: int = 100
) -> list[dict[str, Any]]:
    """Return source-attributed corporate actions around a symbol's research window."""
    market, ticker = exchange.strip().upper(), symbol.strip().upper()
    if market not in _SERIES or not _SYMBOL.fullmatch(ticker):
        raise EodStoreError("Invalid exchange or symbol", 422)
    if from_date > to_date or (to_date - from_date).days > 6200:
        raise EodStoreError("Corporate-action date range must be ordered and at most 17 years", 422)
    if not 1 <= limit <= 500:
        raise EodStoreError("Corporate-action limit must be between 1 and 500", 422)
    try:
        with connect() as connection:
            rows = connection.execute(
                """
                SELECT ex_date, record_date, action_type, ratio_num, ratio_den,
                       cash_amount, face_value_from, face_value_to, raw_subject,
                       company_name, isin, provider, source_sha256
                FROM market_corporate_actions
                WHERE exchange=%s AND symbol=%s AND ex_date BETWEEN %s AND %s
                ORDER BY ex_date, action_type, raw_subject
                LIMIT %s
                """,
                (market, ticker, from_date, to_date, limit),
            ).fetchall()
    except psycopg.Error as error:
        raise EodStoreError("Corporate-action history is unavailable; import derived market history") from error
    return [{
        "ex_date": row[0].isoformat(),
        "record_date": row[1].isoformat() if row[1] else None,
        "action_type": row[2],
        "ratio_num": int(row[3]) if row[3] is not None else None,
        "ratio_den": int(row[4]) if row[4] is not None else None,
        "cash_amount": float(row[5]) if row[5] is not None else None,
        "face_value_from": float(row[6]) if row[6] is not None else None,
        "face_value_to": float(row[7]) if row[7] is not None else None,
        "raw_subject": row[8],
        "company_name": row[9],
        "isin": row[10],
        "provider": row[11],
        "source_sha256": row[12],
    } for row in rows]


def _resolve_point_in_time_isin(connection, exchange: str, symbol: str, from_date: date, to_date: date) -> str:
    rows = connection.execute(
        """
        SELECT DISTINCT isin
        FROM market_symbol_history
        WHERE exchange=%s AND symbol=%s
          AND valid_from <= %s AND (valid_to IS NULL OR valid_to >= %s)
        """,
        (exchange, symbol, to_date, from_date),
    ).fetchall()
    isins = {str(row[0]) for row in rows if row[0]}
    if not isins:
        raise EodStoreError(
            "Point-in-time symbol identity is unavailable; import the TejHQ symbol-history archive first", 409
        )
    if len(isins) != 1:
        raise EodStoreError(
            "Ticker maps to multiple ISINs in the requested interval; use an unambiguous symbol or ISIN", 409
        )
    return next(iter(isins))


def point_in_time_symbol_history(
    exchange: str, symbol: str, *, from_date: date, to_date: date, limit: int = 2000
) -> list[dict[str, Any]]:
    """Resolve a ticker to one ISIN, then follow its valid historical symbols across the requested dates."""
    market, ticker = exchange.strip().upper(), symbol.strip().upper()
    if market not in _SERIES or not _SYMBOL.fullmatch(ticker):
        raise EodStoreError("Invalid exchange or symbol", 422)
    if from_date > to_date or (to_date - from_date).days > 6200:
        raise EodStoreError("Historical date range must be ordered and at most 17 years", 422)
    if not 1 <= limit <= 2000:
        raise EodStoreError("History limit must be between 1 and 2000", 422)
    try:
        with connect() as connection:
            isin = _resolve_point_in_time_isin(connection, market, ticker, from_date, to_date)
            rows = connection.execute(
                """
                SELECT DISTINCT ON (bar.trading_date)
                       bar.trading_date, bar.symbol, bar.isin,
                       COALESCE(adjusted.adjustment_factor * bar.open, bar.open) AS open,
                       COALESCE(adjusted.adjustment_factor * bar.high, bar.high) AS high,
                       COALESCE(adjusted.adjustment_factor * bar.low, bar.low) AS low,
                       COALESCE(adjusted.adjusted_close, bar.close) AS close,
                       COALESCE(adjusted.adjustment_factor * bar.previous_close, bar.previous_close) AS previous_close,
                       bar.volume, bar.turnover, bar.provider, bar.quality_status,
                       bar.source_retrieved_at, bar.trades, sh.valid_from, sh.valid_to,
                       sh.provider AS symbol_history_provider,
                       CASE WHEN adjusted.adjusted_close IS NULL
                            THEN 'RAW_CLOSE' ELSE 'CORPORATE_ACTION_ADJUSTED' END AS price_basis,
                       adjusted.adjustment_factor
                FROM market_eod_bars AS bar
                JOIN market_symbol_history AS sh
                  ON sh.exchange=bar.exchange AND sh.isin=bar.isin AND sh.symbol=bar.symbol
                 AND sh.valid_from <= bar.trading_date
                 AND (sh.valid_to IS NULL OR sh.valid_to >= bar.trading_date)
                LEFT JOIN market_eod_adjusted_bars AS adjusted
                  ON adjusted.exchange=bar.exchange AND adjusted.trading_date=bar.trading_date
                 AND adjusted.symbol=bar.symbol AND adjusted.series=bar.series
                 AND adjusted.isin=bar.isin AND adjusted.provider='tejhq_hf_parquet'
                WHERE bar.exchange=%s AND sh.isin=%s
                  AND bar.trading_date BETWEEN %s AND %s
                ORDER BY bar.trading_date, sh.valid_from DESC,
                         adjusted.source_retrieved_at DESC NULLS LAST,
                         bar.source_retrieved_at DESC, bar.provider
                LIMIT %s
                """,
                (market, isin, from_date, to_date, limit),
            ).fetchall()
    except (EodStoreError, psycopg.Error) as error:
        if isinstance(error, EodStoreError):
            raise
        raise EodStoreError("Point-in-time symbol history is unavailable; apply migrations and import identity history") from error
    if not rows:
        raise EodStoreError("No point-in-time EOD bars were found for this ISIN and date range", 404)
    return [{
        "date": row[0].isoformat(), "symbol": row[1], "isin": row[2],
        "open": float(row[3]), "high": float(row[4]), "low": float(row[5]), "close": float(row[6]),
        "previous_close": float(row[7]) if row[7] is not None else None,
        "volume": int(row[8]), "turnover": float(row[9]) if row[9] is not None else None,
        "provider": row[10], "quality_status": row[11], "retrieved_at": row[12].isoformat(),
        "trades": int(row[13]) if row[13] is not None else None,
        "symbol_valid_from": row[14].isoformat(), "symbol_valid_to": row[15].isoformat() if row[15] else None,
        "symbol_history_provider": row[16], "price_basis": row[17],
        "adjustment_factor": float(row[18]) if row[18] is not None else None,
        "data_mode": "END_OF_DAY_POINT_IN_TIME",
    } for row in rows]


def corporate_action_history_by_isin(
    exchange: str, isin: str, *, from_date: date, to_date: date, limit: int = 100
) -> list[dict[str, Any]]:
    market, security_id = exchange.strip().upper(), isin.strip().upper()
    if market not in _SERIES or not re.fullmatch(r"[A-Z0-9]{12}", security_id):
        raise EodStoreError("Invalid exchange or ISIN", 422)
    if from_date > to_date or (to_date - from_date).days > 6200:
        raise EodStoreError("Corporate-action date range must be ordered and at most 17 years", 422)
    if not 1 <= limit <= 500:
        raise EodStoreError("Corporate-action limit must be between 1 and 500", 422)
    try:
        with connect() as connection:
            rows = connection.execute(
                """
                SELECT ex_date, record_date, action_type, ratio_num, ratio_den,
                       cash_amount, face_value_from, face_value_to, raw_subject,
                       company_name, symbol, provider, source_sha256
                FROM market_corporate_actions
                WHERE exchange=%s AND isin=%s AND ex_date BETWEEN %s AND %s
                ORDER BY ex_date, action_type, raw_subject LIMIT %s
                """,
                (market, security_id, from_date, to_date, limit),
            ).fetchall()
    except psycopg.Error as error:
        raise EodStoreError("Corporate-action history is unavailable; apply migrations") from error
    return [{
        "ex_date": row[0].isoformat(), "record_date": row[1].isoformat() if row[1] else None,
        "action_type": row[2], "ratio_num": int(row[3]) if row[3] is not None else None,
        "ratio_den": int(row[4]) if row[4] is not None else None,
        "cash_amount": float(row[5]) if row[5] is not None else None,
        "face_value_from": float(row[6]) if row[6] is not None else None,
        "face_value_to": float(row[7]) if row[7] is not None else None,
        "raw_subject": row[8], "company_name": row[9], "symbol": row[10],
        "isin": security_id, "provider": row[11], "source_sha256": row[12],
    } for row in rows]


def point_in_time_universe(exchange: str, *, as_of: date, limit: int = 5000) -> dict[str, Any]:
    """Return instruments valid on the requested date plus observed EOD bar coverage."""
    market = exchange.strip().upper()
    if market not in _SERIES or not 1 <= limit <= 5000:
        raise EodStoreError("Invalid exchange or point-in-time universe limit", 422)
    try:
        with connect() as connection:
            rows = connection.execute(
                """
                WITH active AS (
                    SELECT DISTINCT ON (isin) exchange, isin, symbol, valid_from, valid_to, provider
                    FROM market_symbol_history
                    WHERE exchange=%s AND valid_from <= %s
                      AND (valid_to IS NULL OR valid_to >= %s)
                    ORDER BY isin, valid_from DESC, source_retrieved_at DESC
                )
                SELECT active.isin, active.symbol, active.valid_from, active.valid_to,
                       active.provider, bar.series, bar.close, bar.volume, bar.quality_status,
                       COUNT(*) OVER() AS total_members
                FROM active
                LEFT JOIN LATERAL (
                    SELECT b.series, COALESCE(a.adjusted_close, b.close) AS close,
                           b.volume, b.quality_status
                    FROM market_eod_bars b
                    LEFT JOIN market_eod_adjusted_bars a
                      ON a.exchange=b.exchange AND a.trading_date=b.trading_date
                     AND a.symbol=b.symbol AND a.series=b.series AND a.isin=b.isin
                     AND a.provider='tejhq_hf_parquet'
                    WHERE b.exchange=active.exchange AND b.isin=active.isin
                      AND b.symbol=active.symbol AND b.trading_date=%s
                    ORDER BY CASE WHEN b.series IN ('EQ', 'A') THEN 0 ELSE 1 END,
                             a.source_retrieved_at DESC NULLS LAST, b.source_retrieved_at DESC
                    LIMIT 1
                ) AS bar ON TRUE
                ORDER BY active.symbol LIMIT %s
                """,
                (market, as_of, as_of, as_of, limit),
            ).fetchall()
    except psycopg.Error as error:
        raise EodStoreError("Point-in-time universe history is unavailable; import symbol-history Parquet") from error
    total = int(rows[0][9]) if rows else 0
    members = [{
        "isin": row[0], "symbol": row[1], "valid_from": row[2].isoformat(),
        "valid_to": row[3].isoformat() if row[3] else None, "symbol_history_provider": row[4],
        "series": row[5], "close": float(row[6]) if row[6] is not None else None,
        "volume": int(row[7]) if row[7] is not None else None,
        "quality_status": row[8], "bar_available": row[6] is not None,
    } for row in rows]
    with_bars = sum(item["bar_available"] for item in members)
    return {
        "exchange": market, "as_of": as_of.isoformat(), "status": "AVAILABLE" if total else "NO_PIT_SYMBOL_HISTORY",
        "total_members": total, "returned_members": len(members), "members_with_bars": with_bars,
        "coverage_fraction": with_bars / total if total else 0.0,
        "point_in_time_coverage": bool(total and with_bars == total), "members": members,
        "source": "TEJHQ_SYMBOL_HISTORY_AND_PERSISTED_EOD",
    }


def point_in_time_universe_performance(
    exchange: str, *, from_date: date, to_date: date, minimum_coverage: float = 0.80
) -> dict[str, Any]:
    """Build a daily equal-weight return index from constituents valid on each historical date."""
    market = exchange.strip().upper()
    if market not in _SERIES or from_date > to_date or (to_date - from_date).days > 370:
        raise EodStoreError("Universe performance range must be ordered and at most 370 calendar days", 422)
    if not 0.50 <= minimum_coverage <= 1.0:
        raise EodStoreError("Minimum adjusted-price coverage must be between 0.50 and 1.00", 422)
    try:
        with connect() as connection:
            rows = connection.execute(
                """
                WITH trading_days AS (
                    SELECT DISTINCT trading_date
                    FROM market_eod_bars
                    WHERE exchange=%s AND trading_date BETWEEN %s AND %s
                ),
                active_members AS (
                    SELECT DISTINCT ON (td.trading_date, sh.isin)
                           td.trading_date, sh.isin, sh.symbol
                    FROM trading_days td
                    JOIN market_symbol_history sh
                      ON sh.exchange=%s AND sh.valid_from <= td.trading_date
                     AND (sh.valid_to IS NULL OR sh.valid_to >= td.trading_date)
                    ORDER BY td.trading_date, sh.isin, sh.valid_from DESC, sh.source_retrieved_at DESC
                ),
                adjusted_source AS (
                    SELECT DISTINCT ON (a.trading_date, a.isin)
                           a.trading_date, a.isin, a.symbol, a.adjusted_close
                    FROM market_eod_adjusted_bars a
                    JOIN market_symbol_history sh
                      ON sh.exchange=a.exchange AND sh.isin=a.isin AND sh.symbol=a.symbol
                     AND sh.valid_from <= a.trading_date
                     AND (sh.valid_to IS NULL OR sh.valid_to >= a.trading_date)
                    WHERE a.exchange=%s AND a.provider='tejhq_hf_parquet'
                      AND a.trading_date BETWEEN (%s::date - INTERVAL '30 days') AND %s
                    ORDER BY a.trading_date, a.isin,
                             CASE WHEN a.series IN ('EQ', 'A') THEN 0 ELSE 1 END,
                             a.source_retrieved_at DESC
                ),
                adjusted_prices AS (
                    SELECT trading_date, isin, symbol, adjusted_close,
                           LAG(adjusted_close) OVER (PARTITION BY isin ORDER BY trading_date) AS previous_adjusted_close
                    FROM adjusted_source
                ),
                daily AS (
                    SELECT m.trading_date,
                           COUNT(DISTINCT m.isin) AS active_members,
                           COUNT(DISTINCT p.isin) FILTER (WHERE p.previous_adjusted_close > 0) AS members_with_returns,
                           AVG(p.adjusted_close / p.previous_adjusted_close - 1)
                               FILTER (WHERE p.previous_adjusted_close > 0) AS equal_weight_return
                    FROM active_members m
                    LEFT JOIN adjusted_prices p
                      ON p.trading_date=m.trading_date AND p.isin=m.isin AND p.symbol=m.symbol
                    GROUP BY m.trading_date
                )
                SELECT trading_date, active_members, members_with_returns, equal_weight_return
                FROM daily ORDER BY trading_date
                """,
                (market, from_date, to_date, market, market, from_date, to_date),
            ).fetchall()
    except psycopg.Error as error:
        raise EodStoreError("Point-in-time adjusted universe history is unavailable; import symbol history and adjusted prices") from error
    if not rows:
        raise EodStoreError("No point-in-time universe trading dates were found", 404)
    index_value = 100.0
    index_valid = True
    days = []
    for trading_date, active, adjusted_count, raw_return in rows:
        coverage = adjusted_count / active if active else 0.0
        valid = bool(active and coverage >= minimum_coverage and raw_return is not None and index_valid)
        if valid:
            index_value *= 1.0 + float(raw_return)
        else:
            index_valid = False
        days.append({
            "date": trading_date.isoformat(), "active_members": active,
            "members_with_adjusted_returns": adjusted_count, "coverage_fraction": coverage,
            "equal_weight_return": float(raw_return) if valid else None,
            "cumulative_index": index_value if index_valid else None,
            "index_valid": index_valid,
        })
    coverage_values = [item["coverage_fraction"] for item in days]
    return {
        "exchange": market, "from_date": from_date.isoformat(), "to_date": to_date.isoformat(),
        "status": "AVAILABLE" if all(item["index_valid"] for item in days) else "LOW_COVERAGE",
        "method": "POINT_IN_TIME_EQUAL_WEIGHTED_UNIVERSE_INDEX",
        "price_basis": "CORPORATE_ACTION_ADJUSTED_ONLY",
        "minimum_coverage": minimum_coverage, "trading_days": len(days),
        "average_universe_coverage": sum(coverage_values) / len(coverage_values),
        "days": days,
        "limitations": [
            "Membership is reconstructed from the imported historical symbol/ISIN validity archive, not today's constituents.",
            "Only adjusted-price observations are included; raw-close fallback is prohibited for this index.",
            "The index is equal-weighted using members with adjusted returns on each day and is not a tradable portfolio.",
            "If adjusted-return coverage falls below the configured threshold, the cumulative index is invalidated from that date onward.",
            "Corporate-action and symbol-history archive completeness remains a prerequisite for unbiased results.",
            "Transaction costs, liquidity, market impact, taxes and execution constraints are not modelled.",
        ],
    }
