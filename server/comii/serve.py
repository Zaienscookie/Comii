"""Run the WebUI and the data API on two ports in one process.

    python -m comii.serve

* COMII_PORT     (default 8000) – WebUI + legacy /api/*
* COMII_API_PORT (default 8001) – /api/v1 data API for the native app
"""

from __future__ import annotations

import asyncio
import logging
import threading

import uvicorn

from .config import settings
from .main import create_api_app, create_web_app

logger = logging.getLogger("comii")


def _serve(app, port: int) -> None:
    config = uvicorn.Config(
        app,
        host=settings.host,
        port=port,
        log_level=settings.log_level,
        access_log=False,
    )
    server = uvicorn.Server(config)
    try:
        asyncio.run(server.serve())
    except Exception:  # pragma: no cover - startup failure
        logger.exception("server on port %s stopped", port)


def main() -> None:
    settings.ensure_directories()
    api_app = create_api_app()
    web_app = create_web_app()

    api_thread = threading.Thread(
        target=_serve, args=(api_app, settings.api_port), name="comii-api", daemon=True
    )
    api_thread.start()

    logger.info(
        "Comii WebUI on http://%s:%s  |  Data API on http://%s:%s/api/v1",
        settings.host,
        settings.port,
        settings.host,
        settings.api_port,
    )
    _serve(web_app, settings.port)


if __name__ == "__main__":
    main()
