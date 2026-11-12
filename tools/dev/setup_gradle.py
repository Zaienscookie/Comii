#!/usr/bin/env python
"""Fetch a Gradle distribution into <workspace>/buildtools/gradle (proxy aware).

Usage: python setup_gradle.py <workspace> [version]
"""
from __future__ import annotations

import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

DEFAULT_VERSION = "8.9"


def log(message: str) -> None:
    print(f"[gradle] {message}", flush=True)


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    workspace = Path(sys.argv[1]).resolve()
    version = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_VERSION
    target_root = workspace / "buildtools" / "gradle"
    target_root.mkdir(parents=True, exist_ok=True)
    marker = target_root / f"gradle-{version}" / "bin" / "gradle.bat"
    if marker.is_file():
        log(f"already available: {marker}")
        return 0

    archive = target_root / f"gradle-{version}-bin.zip"
    if not archive.is_file():
        url = f"https://services.gradle.org/distributions/gradle-{version}-bin.zip"
        log(f"download {url}")
        with urllib.request.urlopen(url, timeout=600) as response, archive.open("wb") as handle:
            shutil.copyfileobj(response, handle)
    staging = target_root / "_staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir()
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(staging)
    destination = target_root / f"gradle-{version}"
    if destination.exists():
        shutil.rmtree(destination)
    shutil.move(str(staging / f"gradle-{version}"), str(destination))
    shutil.rmtree(staging, ignore_errors=True)
    log(f"ready: {destination / 'bin' / 'gradle.bat'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
