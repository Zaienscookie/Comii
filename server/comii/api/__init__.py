"""API package: v1 for the native app, legacy for the browser UI."""

from __future__ import annotations

from . import legacy, v1

__all__ = ["legacy", "v1"]
