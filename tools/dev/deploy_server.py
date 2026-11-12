#!/usr/bin/env python
"""Deploy the Comii server to a remote host over SSH/SFTP.

Usage:
    set COMII_SSH_PASSWORD=...                 (required)
    set COMII_SSH_HOST=192.168.89.200          (default)
    set COMII_SSH_USER=root                    (default)
    set COMII_REMOTE_ROOT=/root/dev            (default)
    python deploy_server.py [workspace]

It packs ``server/`` and ``web/``, uploads them, refreshes the Python
environment, writes the systemd unit and restarts ``comii``.

Requires: paramiko (pip install paramiko).
"""
from __future__ import annotations

import os
import sys
import tarfile
from pathlib import Path

import paramiko

HOST = os.environ.get("COMII_SSH_HOST", "192.168.89.200")
USER = os.environ.get("COMII_SSH_USER", "root")
PASSWORD = os.environ.get("COMII_SSH_PASSWORD")
REMOTE_ROOT = os.environ.get("COMII_REMOTE_ROOT", "/root/dev")
REMOTE_CODE = f"{REMOTE_ROOT}/comii"
PYTHON = f"{REMOTE_ROOT}/runtime/python/bin/python3.11"
LIBRARY = f"{REMOTE_ROOT}/storage/library"
DATA = f"{REMOTE_ROOT}/storage/data"


def log(message: str) -> None:
    print(f"[deploy] {message}", flush=True)


def archive(workspace: Path) -> Path:
    target = Path(os.environ.get("TEMP", ".")) / "comii-server.tar.gz"
    with tarfile.open(target, "w:gz") as bundle:
        for folder in ("server", "web"):
            root = workspace / folder
            if not root.is_dir():
                continue
            for path in root.rglob("*"):
                if "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo"}:
                    continue
                bundle.add(path, arcname=str(Path(folder) / path.relative_to(root)))
    log(f"archive: {target} ({target.stat().st_size / 1024:.0f} KB)")
    return target


def run(client: paramiko.SSHClient, command: str, timeout: int = 600) -> tuple[int, str]:
    stdin, stdout, stderr = client.exec_command(command, timeout=timeout)
    stdin.close()
    output = stdout.read().decode("utf-8", "replace")
    error = stderr.read().decode("utf-8", "replace")
    return stdout.channel.recv_exit_status(), output + error


def main() -> int:
    if not PASSWORD:
        print("COMII_SSH_PASSWORD is required", file=sys.stderr)
        return 2
    workspace = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]).resolve()
    bundle = archive(workspace)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PASSWORD, timeout=15,
                   banner_timeout=20, auth_timeout=20, look_for_keys=False, allow_agent=False)
    try:
        sftp = client.open_sftp()
        sftp.put(str(bundle), f"{REMOTE_ROOT}/comii.tar.gz")
        sftp.close()

        code, output = run(
            client,
            f"set -e; mkdir -p {REMOTE_CODE} {LIBRARY} {DATA}; "
            f"rm -rf {REMOTE_CODE}/server {REMOTE_CODE}/web; "
            f"tar xzf {REMOTE_ROOT}/comii.tar.gz -C {REMOTE_CODE}",
        )
        if code != 0:
            log(output)
            return code

        code, output = run(
            client, f"{PYTHON} -m pip install -q -r {REMOTE_CODE}/server/requirements.txt"
        )
        if code != 0:
            log(output[-2000:])
            return code

        unit = f"""[Unit]
Description=Comii server (Comic library: WebUI + data API)
After=network.target

[Service]
Type=simple
WorkingDirectory={REMOTE_CODE}/server
Environment=COMII_LIBRARY={LIBRARY}
Environment=COMII_DATA={DATA}
Environment=COMII_PORT=8000
Environment=COMII_API_PORT=8001
Environment=COMII_LOG_LEVEL=info
ExecStart={PYTHON} -m comii.serve
Restart=always
RestartSec=3
User=root

[Install]
WantedBy=multi-user.target
"""
        sftp = client.open_sftp()
        with sftp.open("/etc/systemd/system/comii.service", "w") as handle:
            handle.write(unit)
        sftp.close()

        code, output = run(
            client,
            "systemctl daemon-reload && systemctl enable comii --quiet && "
            "systemctl restart comii && sleep 2 && systemctl is-active comii",
        )
        log(output.strip())
        if code != 0:
            code, output = run(client, "journalctl -u comii -n 40 --no-pager")
            log(output)
            return code

        code, output = run(
            client,
            "curl -s -m 5 http://127.0.0.1:8000/api/v1/ping; echo; "
            "curl -s -m 5 http://127.0.0.1:8001/api/v1/ping; echo; "
            "curl -s -m 5 -o /dev/null -w 'web:%{http_code}\\n' http://127.0.0.1:8000/",
        )
        log(output.strip())
        return 0
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
