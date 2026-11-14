"""Comii FastAPI applications.

Two apps are exposed:

* ``create_web_app()``  – WebUI pages, static files and the legacy ``/api/*``
  routes used by the browser UI (port 8000).
* ``create_api_app()``  – the ``/api/v1`` data API used by the native app
  (port 8001).
* ``create_app()``      – both mounted together (tests, single-port dev).

``python -m comii.serve`` runs both ports in one process.
"""

from __future__ import annotations

import asyncio
import logging
import secrets
import time
from contextlib import asynccontextmanager
from logging.handlers import RotatingFileHandler

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .api.ai import router as ai_router
from .api.auth import router as auth_router
from .api.legacy import router as legacy_router
from .api.v1 import router
from . import auth
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
    auth.ensure_schema()
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




async def access_gate(request: Request, call_next):
    """Shared server whitelist token for the 8001 data API."""
    token = settings.access_token
    path = request.url.path
    if token and path.startswith("/api/") and not (
        path == "/api/v1/ping"
        or path.startswith("/api/v1/app/update")
        or path == "/api/v1/auth/status"
    ):
        supplied = request.headers.get("x-comii-token", "") or request.cookies.get("comii_access", "")
        if not supplied or not secrets.compare_digest(token, supplied):
            return JSONResponse(status_code=401, content={"detail": "无效的服务器访问令牌"})
    return await call_next(request)


async def auth_gate(request: Request, call_next):
    path = request.url.path
    if not auth.enabled():
        request.state.user = auth.LOCAL_OWNER
        return await call_next(request)
    if auth.public_path(path):
        return await call_next(request)
    if path.startswith("/api/"):
        auth.ensure_schema()
        user = auth.user_from_request(request)
        if user is None:
            return JSONResponse(
                status_code=401,
                content={"detail": "请先登录"},
                headers={"WWW-Authenticate": "Bearer"},
            )
        request.state.user = user
    return await call_next(request)


async def unhandled_error(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("unhandled error on %s", request.url.path)
    return JSONResponse(status_code=500, content={"detail": "服务器内部错误"})


def _add_middleware(app: FastAPI) -> None:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.allowed_origins,
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=["Content-Range", "Accept-Ranges", "Content-Disposition"],
    )
    app.middleware("http")(access_log)
    app.middleware("http")(auth_gate)
    app.add_exception_handler(Exception, unhandled_error)


def service_info() -> ServiceInfo:
    return ServiceInfo(
        name=settings.app_name,
        version=settings.version,
        api=settings.api_prefix,
        library=str(settings.library_root),
        features=[],
    )


# --------------------------------------------------------------------------
# Data API (native app)
# --------------------------------------------------------------------------
def create_api_app() -> FastAPI:
    api = FastAPI(
        title="Comii Data API",
        version=settings.version,
        lifespan=lifespan,
        docs_url=f"{settings.api_prefix}/docs",
        redoc_url=None,
        openapi_url=f"{settings.api_prefix}/openapi.json",
    )
    _add_middleware(api)
    api.middleware("http")(access_gate)
    api.include_router(auth_router)
    api.include_router(router)
    api.include_router(ai_router)
    if STATIC_ROOT.is_dir():
        api.mount("/static", StaticFiles(directory=str(STATIC_ROOT)), name="static")

    @api.get("/ai.html", include_in_schema=False, response_model=None)
    def api_ai_page():
        page = STATIC_ROOT / "ai.html"
        if not page.is_file():
            return HTMLResponse("<h3>ai.html missing</h3>", status_code=404)
        return HTMLResponse(page.read_text(encoding="utf-8"))

    @api.get("/health", include_in_schema=False)
    def health() -> dict[str, str]:
        return {"status": "ok", "service": "api"}

    @api.get("/", include_in_schema=False, response_model=None)
    def root() -> JSONResponse:
        return JSONResponse(service_info().model_dump())

    return api


# --------------------------------------------------------------------------
# WebUI (browser)
# --------------------------------------------------------------------------
def _add_web_pages(app: FastAPI) -> None:
    if STATIC_ROOT.is_dir():
        app.mount("/static", StaticFiles(directory=str(STATIC_ROOT)), name="static")

    @app.get("/", include_in_schema=False, response_model=None)
    def index_page():
        index = STATIC_ROOT / "index.html"
        if index.is_file():
            return HTMLResponse(index.read_text(encoding="utf-8"))
        return HTMLResponse("<h3>WebUI 未安装</h3>", status_code=404)

    @app.get("/login.html", include_in_schema=False, response_model=None)
    def login_page():
        page = STATIC_ROOT / "login.html"
        if page.is_file():
            return HTMLResponse(page.read_text(encoding="utf-8"))
        return HTMLResponse("<h3>login.html missing</h3>", status_code=404)

    @app.get("/admin.html", include_in_schema=False, response_model=None)
    def admin_page():
        page = STATIC_ROOT / "admin.html"
        if page.is_file():
            return HTMLResponse(page.read_text(encoding="utf-8"))
        return HTMLResponse("<h3>admin.html missing</h3>", status_code=404)

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

    @app.get("/ai.html", include_in_schema=False, response_model=None)
    def ai_page():
        page = STATIC_ROOT / "ai.html"
        if not page.is_file():
            return HTMLResponse("<h3>ai.html missing</h3>", status_code=404)
        return HTMLResponse(page.read_text(encoding="utf-8"))


def create_web_app() -> FastAPI:
    web = FastAPI(
        title="Comii WebUI",
        version=settings.version,
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    _add_middleware(web)
    web.middleware("http")(access_gate)
    web.include_router(auth_router)
    web.include_router(legacy_router)
    web.include_router(ai_router)
    _add_web_pages(web)

    @web.get("/health", include_in_schema=False)
    def health() -> dict[str, str]:
        return {"status": "ok", "service": "web"}

    return web


# --------------------------------------------------------------------------
# Combined app: tests and single-port development
# --------------------------------------------------------------------------
def create_app() -> FastAPI:
    combined = FastAPI(
        title="Comii",
        version=settings.version,
        lifespan=lifespan,
        docs_url=f"{settings.api_prefix}/docs",
        redoc_url=None,
        openapi_url=f"{settings.api_prefix}/openapi.json",
    )
    _add_middleware(combined)
    combined.include_router(auth_router)
    combined.include_router(router)
    combined.include_router(ai_router)
    combined.include_router(legacy_router)
    _add_web_pages(combined)

    @combined.get("/health", include_in_schema=False)
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @combined.get("/", include_in_schema=False, response_model=None)
    def combined_root():
        index = STATIC_ROOT / "index.html"
        if index.is_file():
            return HTMLResponse(index.read_text(encoding="utf-8"))
        return JSONResponse(service_info().model_dump())

    return combined


app = create_app()



