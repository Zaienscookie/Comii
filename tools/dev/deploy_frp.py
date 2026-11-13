#!/usr/bin/env python
"""Deploy Comii to the frp/tailscale host.

Environment:
    COMII_SSH_HOST      default frp-six.com
    COMII_SSH_PORT      default 45435
    COMII_SSH_USER      default debug
    COMII_SSH_PASSWORD  required
    COMII_REMOTE_CODE   default /home/debug/service/comii

The remote layout is expected to be:

    <code>/server/   source
    <code>/web/      web UI
    <code>/.venv/    python environment
    <code>/storage/  library
    <code>/data/     runtime data

Usage: python tools/dev/deploy_frp.py [workspace]
"""
from __future__ import annotations

import os
import tarfile
import time
from pathlib import Path

import paramiko

HOST = os.environ.get("COMII_SSH_HOST", "frp-six.com")
PORT = int(os.environ.get("COMII_SSH_PORT", "45435"))
USER = os.environ.get("COMII_SSH_USER", "debug")
PASSWORD = os.environ.get("COMII_SSH_PASSWORD", "")
REMOTE = os.environ.get("COMII_REMOTE_CODE", "/home/debug/service/comii")
PYTHON = f"{REMOTE}/.venv/bin/python"


def log(message: str) -> None:
    print(f"[deploy] {message}", flush=True)


def archive(workspace: Path) -> Path:
    target = Path(os.environ.get("TEMP", ".")) / "comii-deploy.tar.gz"
    with tarfile.open(target, "w:gz") as bundle:
        for folder in ("server", "web"):
            root = workspace / folder
            for path in root.rglob("*"):
                if "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo"}:
                    continue
                bundle.add(path, arcname=str(Path(folder) / path.relative_to(root)))
    log(f"archive {target} ({target.stat().st_size / 1024:.0f} KB)")
    return target


def run(client: paramiko.SSHClient, command: str, timeout: int = 600) -> tuple[int, str]:
    stdin, stdout, stderr = client.exec_command(command, timeout=timeout)
    stdin.close()
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    return stdout.channel.recv_exit_status(), out + err


def main() -> int:
    if not PASSWORD:
        print("COMII_SSH_PASSWORD is required", flush=True)
        return 2
    workspace = Path(os.environ.get("COMII_WORKSPACE", Path(__file__).resolve().parents[2])).resolve()
    bundle = archive(workspace)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, port=PORT, username=USER, password=PASSWORD, timeout=25,
                   banner_timeout=30, auth_timeout=30, look_for_keys=False, allow_agent=False)
    try:
        sftp = client.open_sftp()
        sftp.put(str(bundle), "/tmp/comii-deploy.tar.gz")
        sftp.close()

        stamp = time.strftime("%Y%m%d-%H%M%S")
        backup = f"/home/debug/service/backups/comii-before-{stamp}.tar.gz"
        code, out = run(
            client,
            "set -e; mkdir -p /home/debug/service/backups; "
            f"tar czf {backup} -C {REMOTE} server web; "
            f"rm -rf {REMOTE}/server {REMOTE}/web; "
            f"tar xzf /tmp/comii-deploy.tar.gz -C {REMOTE}; rm -f /tmp/comii-deploy.tar.gz; "
            f"{PYTHON} -m pip install -q -r {REMOTE}/server/requirements.txt",
        )
        log(out.strip()[-800:])
        if code != 0:
            return code

        code, out = run(
            client,
            f"printf '%s\\n' '{PASSWORD}' | sudo -S -p '' systemctl restart comii; "
            "sleep 2; systemctl is-active comii; "
            "curl -s -m 5 http://127.0.0.1:8001/api/v1/ping; echo",
        )
        log(out.strip())
        log(f"backup: {backup}")
        return 0
    finally:
        client.close()


if __name__ == "__main__":
    raise SystemExit(main())
