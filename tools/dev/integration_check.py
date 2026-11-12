#!/usr/bin/env python3
"""Live integration check against a running Comii server (run on the host)."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

BASE = os.environ.get("COMII_BASE", "http://127.0.0.1:8000")


def call(path: str, method: str = "GET", data: dict | None = None) -> tuple[int, dict]:
    payload = None
    headers = {}
    if data is not None:
        payload = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(BASE + path, data=payload, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def curl(args: list[str]) -> str:
    result = subprocess.run(["curl", "-s", "-S", *args], capture_output=True, text=True, timeout=180)
    return result.stdout.strip()


PDF = b"""%PDF-1.4
1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj
4 0 obj << /Length 44 >> stream
BT /F1 24 Tf 40 200 Td (Comii PDF Test) Tj ET
endstream
endobj
5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj
trailer << /Root 1 0 R >>
%%EOF
"""


def main() -> int:
    status, ping = call("/api/v1/ping")
    print("ping:", status, ping.get("app"), ping.get("version"))
    status, browse = call("/api/v1/browse")
    print("browse:", status, "items:", browse.get("total"))
    first = browse["items"][0]
    status, tree = call("/api/v1/browse?path=" + urllib.parse.quote(first["path"]))
    print("browse child:", status, "items:", tree.get("total"))
    series_path = tree["items"][0]["path"]
    status, detail = call("/api/v1/series?path=" + urllib.parse.quote(series_path))
    print("series:", status, detail.get("name"), "images:", len(detail.get("images", [])))

    thumb = urllib.parse.urlencode({"path": series_path, "name": detail["images"][0]})
    with urllib.request.urlopen(f"{BASE}/api/v1/media/thumb?{thumb}", timeout=30) as response:
        print("thumb:", response.status, response.headers.get("Content-Type"), len(response.read()))

    # PDF import
    with open("/tmp/comii-test.pdf", "wb") as handle:
        handle.write(PDF)
    out = curl([
        "-F", "file=@/tmp/comii-test.pdf;type=application/pdf",
        "-F", "name=PDF测试导入", "-F", "folder=", "-F", "dpi=120",
        f"{BASE}/api/v1/import/pdf",
    ])
    print("pdf import:", out)
    pdf_result = json.loads(out) if out.startswith("{") else {}

    # upload an image series into the new folder
    image = os.environ.get("COMII_TEST_IMAGE") or os.path.join("/root/dev/library", series_path, detail["images"][0])
    out = curl([
        "-F", f"files=@{image};type=image/jpeg",
        "-F", "name=上传测试", "-F", "folder=PDF测试导入",
        f"{BASE}/api/v1/upload",
    ])
    print("upload:", out)

    # delete -> trash -> restore -> delete -> purge
    target = pdf_result.get("path") or "PDF测试导入"
    status, deleted = call("/api/v1/items?path=" + urllib.parse.quote(target), method="DELETE")
    print("delete:", status, deleted)
    status, trash = call("/api/v1/trash")
    print("trash:", status, [(item["name"], int(item["remaining_seconds"])) for item in trash.get("items", [])])
    entry = next((item for item in trash.get("items", []) if item["original_path"] == target), None)
    if entry:
        status, restored = call(f"/api/v1/trash/{entry['id']}/restore", method="POST", data={})
        print("restore:", status, restored)
        call("/api/v1/items?path=" + urllib.parse.quote(target), method="DELETE")
        status, trash = call("/api/v1/trash")
        entry = next((item for item in trash.get("items", []) if item["original_path"] == target), None)
        if entry:
            status, purged = call(f"/api/v1/trash/{entry['id']}", method="DELETE")
            print("purge:", status, purged)

    status, stats = call("/api/v1/stats")
    print("stats:", status, stats)

    # legacy web endpoints
    for path in ("/api/tree", "/api/ping", "/api/settings"):
        try:
            with urllib.request.urlopen(BASE + path, timeout=30) as response:
                print("legacy", path, response.status, len(response.read()))
        except Exception as error:  # noqa: BLE001
            print("legacy", path, "ERR", error)

    with urllib.request.urlopen(BASE + "/", timeout=30) as response:
        body = response.read()
        print("web index:", response.status, len(body), b"Comii" in body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
