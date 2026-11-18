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
    try:
        info = auth.bootstrap()
        if info.get("created") == "1":
            logger.warning(
                "已自动创建 Owner：用户名=%s 密码=%s（请尽快修改；配置见 %s）",
                info.get("username"), info.get("password"), settings.config_file,
            )
        if info.get("token_generated") == "1":
            logger.warning(
                "已自动生成服务器访问令牌：%s（已写入 %s）",
                info.get("token"), settings.config_file,
            )
    except Exception as exc:  # 启动阶段不能因为初始化失败而无法起来
        logger.error("初始化账号/令牌失败：%s", exc)
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
        # Cloudflare 隧道：用 CF-Connecting-IP 记录真实客户端
        client = (
            request.headers.get("cf-connecting-ip")
            or (request.client.host if request.client else "-")
        )
        logger.info(
            "%s %s -> %s (%.0fms) from %s",
            request.method,
            request.url.path,
            response.status_code,
            elapsed_ms,
            client,
        )
    return response




def _is_local_client(request: Request) -> bool:
    host = request.client.host if request.client else ""
    return host in ("127.0.0.1", "::1", "localhost")


async def access_gate(request: Request, call_next):
    # 反代/隧道场景：把真实来源写进 scope，审计与限流都能用
    forwarded = request.headers.get("cf-connecting-ip") or request.headers.get("x-forwarded-for", "").split(",")[0].strip()
    if forwarded:
        request.scope["client"] = (forwarded, 0)
    """Shared server whitelist token for the 8001 data API."""
    token_hash = settings.token_hash
    if not token_hash and not _is_local_client(request):
        return JSONResponse(status_code=403, content={"detail": "服务器未配置访问令牌，已拒绝外部请求"})
    path = request.url.path
    if token_hash and path.startswith("/api/") and not (
        path == "/api/v1/ping"
        or False
        or path == "/api/v1/auth/status"
    ):
        supplied = request.headers.get("x-comii-token", "") or request.cookies.get("comii_access", "")
        if not supplied or not settings.check_token(supplied):
            return JSONResponse(status_code=401, content={"detail": "无效的服务器访问令牌"})
    response = await call_next(request)
    # 敏感媒体：只允许私有缓存（浏览器/App 本地），绝不允许 CDN/代理共享缓存
    path_now = request.url.path
    if request.method == "GET" and (
        path_now.startswith(("/api/v1/media/", "/api/media/", "/api/img/", "/api/thumb/", "/api/video_thumb/"))
    ):
        response.headers["Cache-Control"] = "private, max-age=604800, immutable"
        response.headers.setdefault("Vary", "X-Comii-Token, Cookie")
    return response


async def auth_gate(request: Request, call_next):
    path = request.url.path
    if not auth.enabled():
        if not _is_local_client(request):
            return JSONResponse(status_code=403, content={"detail": "服务器未开启登录验证，已拒绝外部请求"})
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
        # Viewer 只能看：任何写操作（含 WebUI 用的 /api/* 旧接口）统一拒绝
        if user.get("role") == "viewer" and request.method not in ("GET", "HEAD", "OPTIONS"):
            if not path.startswith("/api/v1/auth/"):
                return JSONResponse(status_code=403, content={"detail": "没有权限（Viewer 只能查看）"})
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
SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Permissions-Policy": "geolocation=(), camera=(), microphone=()",
    "Content-Security-Policy": "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'self'",
}


def _install_security(app: FastAPI) -> None:
    @app.middleware("http")
    async def _sec_headers(request, call_next):
        response = await call_next(request)
        for key, value in SECURITY_HEADERS.items():
            response.headers.setdefault(key, value)
        if request.url.scheme == "https":
            response.headers.setdefault("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        return response


def create_api_app() -> FastAPI:
    api = FastAPI(
        title="Comii Data API",
        version=settings.version,
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
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

    _install_security(api)
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

    @app.get("/setup.html", include_in_schema=False, response_model=None)
    def setup_page():
        page = STATIC_ROOT / "setup.html"
        if page.is_file():
            return HTMLResponse(page.read_text(encoding="utf-8"))
        return HTMLResponse("<h3>setup.html missing</h3>", status_code=404)

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

    _install_security(web)
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



