"""App update channel: metadata + APK stored under data/updates."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

from ..config import settings
from ..models import AppUpdateInfo


def _metadata() -> dict:
    meta_file = settings.update_root / "update.json"
    if not meta_file.is_file():
        return {}
    try:
        data = json.loads(meta_file.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def apk_path() -> Optional[Path]:
    root = settings.update_root
    if not root.is_dir():
        return None
    named = str(_metadata().get("apk", "") or "").strip()
    if named:
        candidate = root / named
        if candidate.is_file():
            return candidate
    candidates = sorted(root.glob("*.apk"), key=lambda item: item.stat().st_mtime, reverse=True)
    return candidates[0] if candidates else None


def latest_update() -> AppUpdateInfo:
    apk = apk_path()
    meta = _metadata()
    if apk is None:
        return AppUpdateInfo()
    try:
        version_code = int(meta.get("versionCode") or 0)
    except (TypeError, ValueError):
        version_code = 0
    if version_code <= 0:
        return AppUpdateInfo()
    return AppUpdateInfo(
        available=True,
        versionCode=version_code,
        versionName=str(meta.get("versionName") or ""),
        notes=str(meta.get("notes") or ""),
        size=apk.stat().st_size,
        sha256=str(meta.get("sha256") or ""),
        url=f"{settings.api_prefix}/app/update/download",
    )
