from __future__ import annotations

import io
import zipfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from comii.config import settings
from comii.main import app


def _png(color: tuple[int, int, int] = (40, 80, 160), size: tuple[int, int] = (300, 450)) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", size, color).save(buffer, "PNG")
    return buffer.getvalue()


@pytest.fixture()
def library_root(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    root = tmp_path / "library"
    data = tmp_path / "data"
    monkeypatch.setattr(settings, "library_root", root.resolve())
    monkeypatch.setattr(settings, "data_root", data.resolve())
    monkeypatch.setattr(settings, "thumb_root", (data / "thumbs").resolve())
    monkeypatch.setattr(settings, "trash_root", (data / "trash").resolve())
    monkeypatch.setattr(settings, "trash_index", (data / "trash" / "index.json").resolve())
    monkeypatch.setattr(settings, "progress_file", (data / "progress.json").resolve())
    monkeypatch.setattr(settings, "log_root", (data / "logs").resolve())
    settings.ensure_directories()

    series_a = root / "author_a" / "0001_first_series"
    series_a.mkdir(parents=True)
    for index in (1, 2, 3):
        (series_a / f"{index:03d}.jpg").write_bytes(_png((40 * index, 80, 160)))
    (series_a / "preview.mp4").write_bytes(b"0123456789")

    series_b = root / "author_a" / "0002_second_series"
    series_b.mkdir()
    (series_b / "001.png").write_bytes(_png((10, 120, 240)))

    loose = root / "loose_series"
    loose.mkdir()
    (loose / "001.jpg").write_bytes(_png((240, 120, 10)))

    return root


@pytest.fixture()
def client(library_root: Path) -> TestClient:
    with TestClient(app) as test_client:
        yield test_client


def test_ping(client: TestClient) -> None:
    response = client.get("/api/v1/ping")
    assert response.status_code == 200
    body = response.json()
    assert body["app"] == "Comii"
    assert body["status"] == "ok"
    assert "browse" in body["features"]


def test_browse_root(client: TestClient) -> None:
    body = client.get("/api/v1/browse").json()
    items = {item["name"]: item for item in body["items"]}
    assert items["author_a"]["kind"] == "folder"
    assert items["author_a"]["child_count"] == 2
    assert items["author_a"]["cover"] is not None
    assert items["loose_series"]["kind"] == "manga"
    assert items["loose_series"]["image_count"] == 1


def test_browse_author(client: TestClient) -> None:
    body = client.get("/api/v1/browse", params={"path": "author_a"}).json()
    assert body["path"] == "author_a"
    assert body["parent"] == ""
    kinds = {item["name"]: item["kind"] for item in body["items"]}
    assert kinds["0001_first_series"] == "mixed"
    assert kinds["0002_second_series"] == "manga"


def test_series_and_thumbnail(client: TestClient) -> None:
    body = client.get("/api/v1/series", params={"path": "author_a/0001_first_series"}).json()
    assert body["images"] == ["001.jpg", "002.jpg", "003.jpg"]
    assert body["videos"] == ["preview.mp4"]
    assert body["kind"] == "mixed"

    thumb = client.get(
        "/api/v1/media/thumb",
        params={"path": "author_a/0001_first_series", "name": "001.jpg"},
    )
    assert thumb.status_code == 200
    assert thumb.headers["content-type"] == "image/jpeg"
    assert len(thumb.content) > 100


def test_video_range(client: TestClient) -> None:
    response = client.get(
        "/api/v1/media/video",
        params={"path": "author_a/0001_first_series", "name": "preview.mp4"},
        headers={"Range": "bytes=2-5"},
    )
    assert response.status_code == 206
    assert response.headers["content-range"] == "bytes 2-5/10"
    assert response.content == b"2345"


def test_path_traversal_rejected(client: TestClient) -> None:
    assert client.get("/api/v1/browse", params={"path": "../"}).status_code == 400


def test_folder_rename_and_move(client: TestClient) -> None:
    created = client.post(
        "/api/v1/folders", json={"parent": "", "name": "收藏", "kind": "manga"}
    )
    assert created.status_code == 200
    assert created.json()["path"] == "收藏"

    moved = client.post(
        "/api/v1/move", json={"paths": ["loose_series"], "to": "收藏"}
    )
    assert moved.status_code == 200
    assert moved.json()["status"] == "ok"
    assert (library_root_path() / "收藏" / "loose_series").is_dir()

    renamed = client.post(
        "/api/v1/rename", json={"path": "收藏/loose_series", "name": "renamed_series"}
    )
    assert renamed.status_code == 200
    assert renamed.json()["path"] == "收藏/renamed_series"


def library_root_path() -> Path:
    return settings.library_root


def test_delete_restore_and_purge(client: TestClient) -> None:
    deleted = client.delete("/api/v1/items", params={"path": "loose_series"})
    assert deleted.status_code == 200
    assert not (library_root_path() / "loose_series").exists()

    trash_body = client.get("/api/v1/trash").json()
    assert trash_body["total"] == 1
    entry = trash_body["items"][0]
    assert entry["original_path"] == "loose_series"
    assert entry["remaining_seconds"] > 3 * 24 * 3600 - 60

    restored = client.post(f"/api/v1/trash/{entry['id']}/restore")
    assert restored.status_code == 200
    assert (library_root_path() / "loose_series").is_dir()

    client.delete("/api/v1/items", params={"path": "loose_series"})
    entry = client.get("/api/v1/trash").json()["items"][0]
    purged = client.delete(f"/api/v1/trash/{entry['id']}")
    assert purged.status_code == 200
    assert client.get("/api/v1/trash").json()["total"] == 0


def test_progress_roundtrip(client: TestClient) -> None:
    updated = client.put(
        "/api/v1/progress",
        json={"path": "author_a/0001_first_series", "page": 2, "total": 3},
    )
    assert updated.status_code == 200
    detail = client.get("/api/v1/series", params={"path": "author_a/0001_first_series"}).json()
    assert detail["progress"]["page"] == 2
    assert client.get("/api/v1/progress").json()["author_a/0001_first_series"]["total"] == 3


def test_upload_images_creates_series(client: TestClient) -> None:
    response = client.post(
        "/api/v1/upload",
        data={"folder": "author_a", "name": "上传测试"},
        files=[
            ("files", ("001.png", _png(), "image/png")),
            ("files", ("002.png", _png((1, 2, 3)), "image/png")),
        ],
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["path"] == "author_a/上传测试"
    assert body["images"] == 2
    assert (library_root_path() / "author_a" / "上传测试" / "001.png").is_file()


def test_upload_cbz(client: TestClient) -> None:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as bundle:
        bundle.writestr("ch1/001.png", _png())
        bundle.writestr("ch1/002.png", _png((9, 9, 9)))
        bundle.writestr("__MACOSX/._001.png", b"junk")
    buffer.seek(0)
    response = client.post(
        "/api/v1/upload",
        data={"folder": "", "name": "cbz_series"},
        files=[("files", ("bundle.cbz", buffer.getvalue(), "application/zip"))],
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["images"] == 2
    assert (library_root_path() / "cbz_series" / "0001.png").is_file()


def test_export_zip(client: TestClient) -> None:
    response = client.get("/api/v1/export", params={"path": "author_a/0001_first_series"})
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/zip"
    with zipfile.ZipFile(io.BytesIO(response.content)) as bundle:
        names = bundle.namelist()
    assert "0001_first_series/001.jpg" in names
    assert any(name.endswith(".mp4") for name in names)


def test_stats(client: TestClient) -> None:
    body = client.get("/api/v1/stats").json()
    assert body["series_count"] == 3
    assert body["image_count"] == 5
    assert body["video_count"] == 1
