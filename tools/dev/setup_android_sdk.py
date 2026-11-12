#!/usr/bin/env python
"""Download + unpack the Android SDK pieces Comii needs (no sdkmanager).

Google's sdkmanager Java HTTP client often fails behind a local proxy, so this
script parses ``repository2-1.xml`` and unzips the Windows archives itself.

Usage:
    python setup_android_sdk.py <workspace> [--with-cmdline-tools]

Result: ``<workspace>/buildtools/sdk`` with
    platform-tools, platforms/android-35, build-tools/{35.0.0,34.0.0}, licenses
"""
from __future__ import annotations

import os
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path
from xml.etree import ElementTree

REPOSITORY_URL = "https://dl.google.com/android/repository/repository2-1.xml"
BASE_URL = "https://dl.google.com/android/repository/"
CMDLINE_VERSION = "11076708"
CMDLINE_URL = (
    "https://dl.google.com/android/repository/"
    f"commandlinetools-win-{CMDLINE_VERSION}_latest.zip"
)
WANTED = {
    "platform-tools": "platform-tools",
    "platforms;android-35": "platforms/android-35",
    "build-tools;35.0.0": "build-tools/35.0.0",
    "build-tools;34.0.0": "build-tools/34.0.0",
}
LICENSE_HASHES = {
    "android-sdk-license": "\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee",
    "android-sdk-preview-license": "\n84831b9409646a918e30573bab4c9c91346d8abd",
}
BAD_WORDS = ("ext", "rc", "beta", "preview", "canary", "dev")


def log(message: str) -> None:
    print(f"[sdk] {message}", flush=True)


def download(url: str, target: Path) -> None:
    if target.is_file() and target.stat().st_size > 1024:
        return
    log(f"download {url}")
    temporary = target.with_suffix(".part")
    with urllib.request.urlopen(url, timeout=300) as response, temporary.open("wb") as handle:
        shutil.copyfileobj(response, handle)
    temporary.replace(target)


def score(package) -> int:
    name = (package.findtext("display-name") or "").lower()
    value = 0 if any(word in name for word in BAD_WORDS) else 10
    if package.findtext("channel/id") in (None, "0"):
        value += 5
    revision = package.find("revision")
    if revision is not None:
        try:
            value += min(int(revision.findtext("major") or 0), 9)
        except ValueError:
            pass
    return value


def windows_archive(package) -> str | None:
    for archive in package.findall("./archives/archive"):
        if archive.findtext("./host-os") in (None, "windows"):
            url = archive.findtext("./complete/url")
            if url:
                return url
    return None


def install(package_name: str, destination_rel: str, sdk: Path, cache: Path) -> None:
    destination = sdk / destination_rel
    if destination.is_dir() and any(destination.iterdir()):
        log(f"ok {package_name}")
        return
    tree = ElementTree.parse(cache / "repository2-1.xml")
    candidates = [
        package
        for package in tree.getroot().findall("remotePackage")
        if package.get("path") == package_name and windows_archive(package)
    ]
    if not candidates:
        raise SystemExit(f"package not found: {package_name}")
    candidates.sort(key=score, reverse=True)
    url = windows_archive(candidates[0])
    log(f"selected {package_name}: {candidates[0].findtext('display-name')} -> {url}")
    archive = cache / url.split("/")[-1]
    download(BASE_URL + url, archive)
    staging = cache / "unzip"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir()
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(staging)
    roots = list(staging.iterdir())
    source = roots[0] if len(roots) == 1 and roots[0].is_dir() else staging
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        shutil.rmtree(destination)
    shutil.move(str(source), str(destination))
    shutil.rmtree(staging, ignore_errors=True)
    log(f"installed {package_name} -> {destination_rel}")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    workspace = Path(sys.argv[1]).resolve()
    sdk = workspace / "buildtools" / "sdk"
    cache = workspace / "buildtools" / "cache"
    sdk.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)

    download(REPOSITORY_URL, cache / "repository2-1.xml")
    for package_name, destination_rel in WANTED.items():
        install(package_name, destination_rel, sdk, cache)

    licenses = sdk / "licenses"
    licenses.mkdir(exist_ok=True)
    for name, content in LICENSE_HASHES.items():
        (licenses / name).write_text(content, encoding="utf-8")

    if "--with-cmdline-tools" in sys.argv:
        archive = cache / "cmdline-tools.zip"
        download(CMDLINE_URL, archive)
        target = sdk / "cmdline-tools" / "latest"
        if not target.is_dir():
            staging = cache / "cmdline"
            if staging.exists():
                shutil.rmtree(staging)
            with zipfile.ZipFile(archive) as bundle:
                bundle.extractall(staging)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(staging / "cmdline-tools"), str(target))
            shutil.rmtree(staging, ignore_errors=True)
        log(f"cmdline-tools -> {target}")

    log(f"sdk ready: {sdk}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
