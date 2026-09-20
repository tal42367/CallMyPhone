FROM nvidia/cuda:12.4.1-cudnn-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    LTX_REPO=/opt/LTX-Video \
    APP_HOME=/app

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3.10 python3-pip python3.10-venv git ffmpeg ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

RUN python3.10 -m pip install --upgrade pip setuptools wheel

RUN git clone --depth 1 https://github.com/Lightricks/LTX-Video.git /opt/LTX-Video \
    && cd /opt/LTX-Video \
    && python3.10 -m pip install -e ".[inference]"

WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN python3.10 -m pip install -r /app/requirements.txt

COPY app.py /app/app.py
RUN mkdir -p /app/data/uploads /app/data/outputs /app/data/jobs

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8000/health || exit 1

CMD ["python3.10", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
