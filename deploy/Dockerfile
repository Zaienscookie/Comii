FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

# ffmpeg 用于视频缩略图；pypdfium2 / py7zr 由 requirements 安装
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY server/requirements.txt /app/server/requirements.txt
RUN pip install -r /app/server/requirements.txt

COPY server /app/server
COPY web /app/web

ENV COMII_LIBRARY=/library \
    COMII_DATA=/data \
    COMII_PORT=8000 \
    COMII_API_PORT=8001 \
    COMII_MAX_UPLOAD_MB=2048

EXPOSE 8000 8001

WORKDIR /app/server
CMD ["python", "-m", "comii.serve"]
