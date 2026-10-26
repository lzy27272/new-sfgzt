FROM python:3.12.15-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    FINANCE_OCR_HOST=0.0.0.0 FINANCE_OCR_PORT=8095 \
    OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=1
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.lock ./
RUN python -m pip install --no-cache-dir --require-hashes -r requirements.lock
COPY finance_ocr ./finance_ocr
# Model weights are already in the pinned wheel, not downloaded by a production request.
RUN python -c "from finance_ocr.models import verified_models; verified_models()"
USER 10001:10001
EXPOSE 8095
HEALTHCHECK --interval=30s --timeout=3s --start-period=150s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8095/health', timeout=2).close()"
CMD ["python", "-m", "finance_ocr"]
