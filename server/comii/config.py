"""Runtime configuration for the Comii server.

Every path can be overridden through the environment so the same code runs
from the repository checkout, from ``/root/dev`` on the LAN server, or from a
systemd unit without code changes.
"""

from __future__ import annotations

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
        self.access_token = os.environ.get("COMII_ACCESS_TOKEN", "").strip()
        self.trash_ttl_seconds = _env_int("COMII_TRASH_TTL_DAYS", 3) * 24 * 3600
        self.max_upload_mb = _env_int("COMII_MAX_UPLOAD_MB", 512)
        self.allowed_origins = os.environ.get("COMII_CORS_ORIGINS", "*").split(",")

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


settings = Settings()





















