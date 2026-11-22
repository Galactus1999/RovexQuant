FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8000 \
    QUANT_TOKEN_DB_PATH=/data/quant-secrets.sqlite3 \
    QUANT_TOKEN_STORAGE_MODE=postgresql

WORKDIR /app
COPY pyproject.toml ./
COPY src ./src

RUN pip install --no-cache-dir . \
    && useradd --system --uid 10001 --create-home quant \
    && mkdir -p /data \
    && chown -R quant:quant /app /data

USER quant
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:'+os.getenv('PORT','8000')+'/api/v1/health', timeout=2)"

CMD ["sh", "-c", "uvicorn rovexquant.api.main:app --host 0.0.0.0 --port ${PORT}"]
