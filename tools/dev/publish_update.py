#!/usr/bin/env python
"""Publish an APK as the Comii in-app update.

Usage:
    set COMII_SSH_PASSWORD=...
    python tools/dev/publish_update.py <apk> <versionCode> <versionName> "<notes>"

Uploads to <remote>/data/updates/<apk name> and writes update.json, then prints
the /api/v1/app/update response.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
from pathlib import Path

import paramiko

HOST = os.environ.get("COMII_SSH_HOST", "frp-six.com")
PORT = int(os.environ.get("COMII_SSH_PORT", "45435"))
USER = os.environ.get("COMII_SSH_USER", "debug")
PASSWORD = os.environ.get("COMII_SSH_PASSWORD", "")
REMOTE = os.environ.get("COMII_REMOTE_CODE", "/home/debug/service/comii")
UPDATES = f"{REMOTE}/data/updates"


def log(message: str) -> None:
    print(f"[update] {message}", flush=True)


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    if not PASSWORD:
        print("COMII_SSH_PASSWORD is required", flush=True)
        return 2

    apk_path = Path(sys.argv[1]).resolve()
    version_code = int(sys.argv[2])
    version_name = sys.argv[3]
    notes = sys.argv[4] if len(sys.argv) > 4 else ""
    if not apk_path.is_file():
        raise SystemExit(f"APK not found: {apk_path}")

    digest = hashlib.sha256(apk_path.read_bytes()).hexdigest()
    target_name = f"comii-{version_name}.apk"

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, port=PORT, username=USER, password=PASSWORD, timeout=25,
                   banner_timeout=30, auth_timeout=30, look_for_keys=False, allow_agent=False)
    try:
        client.exec_command(f"mkdir -p {UPDATES}")[1].channel.recv_exit_status()
        sftp = client.open_sftp()
        sftp.put(str(apk_path), f"{UPDATES}/{target_name}")
        metadata = {
            "versionCode": version_code,
            "versionName": version_name,
            "notes": notes,
            "apk": target_name,
            "sha256": digest,
            "size": apk_path.stat().st_size,
        }
        with sftp.open(f"{UPDATES}/update.json", "w") as handle:
            handle.write(json.dumps(metadata, ensure_ascii=False, indent=2))
        sftp.close()
        log(f"uploaded {target_name} ({apk_path.stat().st_size / 1024 / 1024:.1f} MB)")

        stdin, stdout, stderr = client.exec_command(
            "curl -s -m 5 http://127.0.0.1:8001/api/v1/app/update", timeout=30
        )
        log(stdout.read().decode("utf-8", "replace").strip())
        return 0
    finally:
        client.close()


if __name__ == "__main__":
    raise SystemExit(main())
