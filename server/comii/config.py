"""Runtime configuration for the Comii server.

Every path can be overridden through the environment so the same code runs
from the repository checkout, from ``/root/dev`` on the LAN server, or from a
systemd unit without code changes.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]


def _env_path(name: str, default: Path) -> Path:
    raw = os.environ.get(name)
    return Path(raw).expanduser().resolve() if raw else default.resolve()


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        return default


class Settings:
    """Mutable settings object; tests monkeypatch the directory attributes."""

    app_name = "Comii"
    api_prefix = "/api/v1"
    version = "3.3.5-beta"

    def __init__(self) -> None:
        self.project_root = PROJECT_ROOT
        self.library_root = _env_path("COMII_LIBRARY", PROJECT_ROOT / "library")
        self.data_root = _env_path("COMII_DATA", PROJECT_ROOT / ".comii")
        self.thumb_root = self.data_root / "thumbs"
        self.favorites_root = self.library_root / "收藏夹"
        self.cache_root = self.data_root / "cache"
        self.tags_file = self.data_root / "tags.json"
        self.trash_root = self.data_root / "trash"
        self.trash_index = self.trash_root / "index.json"
        self.progress_file = self.data_root / "progress.json"
        self.log_root = self.data_root / "logs"
        self.update_root = self.data_root / "updates"
        self.host = os.environ.get("COMII_HOST", "0.0.0.0")
        self.port = _env_int("COMII_PORT", 8000)
        self.api_port = _env_int("COMII_API_PORT", 8001)
        self.log_level = os.environ.get("COMII_LOG_LEVEL", "info")
        # ---- 安全配置（令牌 / 是否开启登录验证）统一放在一个文件里 ----
        # 默认路径：<数据目录>/server.conf，可用 COMII_CONFIG 覆盖
        self.config_file = _env_path("COMII_CONFIG", self.data_root / "server.conf")
        file_cfg = self._load_security_config(self.config_file)
        # 令牌只存哈希；环境变量可用于首次注入（会自动转成哈希）
        env_token = os.environ.get("COMII_ACCESS_TOKEN", "").strip()
        self.token_hash = str(file_cfg.get("access_token_hash", "") or "").strip()
        if env_token:
            self.token_hash = hashlib.sha256(env_token.encode("utf-8")).hexdigest()
        self.access_token = env_token  # 仅启动时用于打印一次，不落盘
        env_auth = os.environ.get("COMII_AUTH_ENABLED")
        if env_auth is not None:
            self.auth_enabled = env_auth.strip().lower() not in {"0", "false", "no", "off"}
        else:
            self.auth_enabled = bool(file_cfg.get("auth_enabled", True))
        # 首次启动自动创建 Owner：用户名/密码可写在 server.conf 或环境变量
        self.owner_user = (
            os.environ.get("COMII_OWNER_USER", "").strip()
            or str(file_cfg.get("owner_user", "") or "admin").strip()
        )
        self.owner_password = (
            os.environ.get("COMII_OWNER_PASSWORD", "").strip()
            or str(file_cfg.get("owner_password", "") or "").strip()
        )
        self.trash_ttl_seconds = _env_int("COMII_TRASH_TTL_DAYS", 3) * 24 * 3600
        self.max_upload_mb = _env_int("COMII_MAX_UPLOAD_MB", 512)
        self.allowed_origins = [o.strip() for o in os.environ.get("COMII_CORS_ORIGINS", "").split(",") if o.strip()]

    @staticmethod
    def _load_security_config(path: Path) -> dict:
        try:
            raw = path.read_text(encoding="utf-8")
        except OSError:
            return {}
        try:
            data = json.loads(raw)
        except ValueError:
            return {}
        return data if isinstance(data, dict) else {}

    def set_token(self, token: str) -> None:
        self.token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        self.access_token = ""

    def check_token(self, supplied: str) -> bool:
        import hmac
        if not self.token_hash:
            return True
        digest = hashlib.sha256(supplied.encode("utf-8")).hexdigest()
        return hmac.compare_digest(self.token_hash, digest)

    def write_security_config(self) -> None:
        """把当前令牌/登录开关写回配置文件，方便命令行或后台修改。"""
        self.data_root.mkdir(parents=True, exist_ok=True)
        payload = {
            "access_token_hash": self.token_hash,
            "auth_enabled": self.auth_enabled,
            "owner_user": self.owner_user,
        }
        self.config_file.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    def ensure_directories(self) -> None:
        for directory in (
            self.library_root,
            self.data_root,
            self.thumb_root,
            self.cache_root,
            self.trash_root,
            self.log_root,
            self.update_root,
        ):
            directory.mkdir(parents=True, exist_ok=True)
        try:
            self.favorites_root.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            # 媒体库只读（compose 里 ./library:/library:ro）时收藏夹无法创建
            import logging
            logging.getLogger("comii").warning(
                "收藏夹创建失败（媒体库只读？请去掉挂载的 :ro）：%s", exc
            )


settings = Settings()





















