"""Comii FastAPI application."""

from __future__ import annotations

import asyncio
import logging
import time
from contextlib import asynccontextmanager
from logging.handlers import RotatingFileHandler

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .api.legacy import router as legacy_router
from .api.v1 import router
from .config import settings
from .models import ServiceInfo
from .services import trash

logger = logging.getLogger("comii")

WEB_ROOT = settings.project_root / "web"
STATIC_ROOT = WEB_ROOT / "static"


def _configure_logging() -> None:
    level = getattr(logging, settings.log_level.upper(), logging.INFO)
    logger.setLevel(level)
    if logger.handlers:
        return
    formatter = logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")
    stream = logging.StreamHandler()
    stream.setFormatter(formatter)
    logger.addHandler(stream)
    try:
        settings.log_root.mkdir(parents=True, exist_ok=True)
        file_handler = RotatingFileHandler(
            settings.log_root / "comii.log",
            maxBytes=2 * 1024 * 1024,
            backupCount=3,
            encoding="utf-8",
        )
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)
    except OSError:
        pass


async def _sweep_loop() -> None:
    while True:
        await asyncio.sleep(600)
        try:
            removed = trash.sweep()
            if removed:
                logger.info("trash sweep removed %s entries", removed)
        except Exception:  # pragma: no cover - background safety
            logger.exception("trash sweep failed")


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.ensure_directories()
    _configure_logging()
    try:
        trash.sweep()
    except Exception:
        logger.exception("initial trash sweep failed")
    task = asyncio.create_task(_sweep_loop())
    try:
        yield
    finally:
        task.cancel()


app = FastAPI(
    title="Comii API",
    version=settings.version,
    lifespan=lifespan,
    docs_url=f"{settings.api_prefix}/docs",
    redoc_url=None,
    openapi_url=f"{settings.api_prefix}/openapi.json",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.allowed_origins,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["Content-Range", "Accept-Ranges", "Content-Disposition"],
)


@app.middleware("http")
async def access_log(request: Request, call_next):
    started_at = time.perf_counter()
    response = await call_next(request)
    if request.url.path.startswith("/api/"):
        elapsed_ms = (time.perf_counter() - started_at) * 1000
        client = request.client.host if request.client else "-"
        logger.info(
            "%s %s -> %s (%.0fms) from %s",
            request.method,
            request.url.path,
            response.status_code,
            elapsed_ms,
            client,
        )
    return response


app.include_router(router)
app.include_router(legacy_router)

if STATIC_ROOT.is_dir():
    app.mount("/static", StaticFiles(directory=str(STATIC_ROOT)), name="static")


def service_info() -> ServiceInfo:
    return ServiceInfo(
        name=settings.app_name,
        version=settings.version,
        api=settings.api_prefix,
        library=str(settings.library_root),
        features=[],
    )


@app.get("/", include_in_schema=False, response_model=None)
def service_info_or_web():
    index = STATIC_ROOT / "index.html"
    if index.is_file():
        return HTMLResponse(index.read_text(encoding="utf-8"))
    payload = service_info().model_dump()
    return JSONResponse(payload)


@app.get("/reader.html", include_in_schema=False, response_model=None)
def reader_page():
    page = STATIC_ROOT / "reader.html"
    if not page.is_file():
        return HTMLResponse("<h3>reader.html missing</h3>", status_code=404)
    return HTMLResponse(page.read_text(encoding="utf-8"))


@app.get("/video.html", include_in_schema=False, response_model=None)
def video_page():
    page = STATIC_ROOT / "video.html"
    if not page.is_file():
        return HTMLResponse("<h3>video.html missing</h3>", status_code=404)
    return HTMLResponse(page.read_text(encoding="utf-8"))


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.exception_handler(Exception)
async def unhandled_error(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("unhandled error on %s", request.url.path)
    return JSONResponse(status_code=500, content={"detail": "服务器内部错误"})
