FROM python:3.11-slim

WORKDIR /app

COPY server /app/server
COPY web /app/web

RUN pip install --no-cache-dir -r /app/server/requirements.txt

ENV PYTHONPATH=/app/server \
    COMII_LIBRARY=/library \
    COMII_DATA=/data \
    PYTHONUNBUFFERED=1

EXPOSE 8000 8001

CMD ["python", "-m", "comii.serve"]
