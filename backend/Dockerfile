FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8000 \
    QUANT_TOKEN_DB_PATH=/data/quant-secrets.sqlite3

WORKDIR /app
COPY pyproject.toml ./
COPY src ./src

RUN pip install --no-cache-dir . \
    && useradd --system --uid 10001 --create-home quant \
    && mkdir -p /data \
    && chown -R quant:quant /app /data

USER quant
EXPOSE 8000

CMD ["sh", "-c", "uvicorn rovexquant.api.main:app --host 0.0.0.0 --port ${PORT}"]
