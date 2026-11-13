from __future__ import annotations

import io
import json
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
    monkeypatch.setattr(settings, "update_root", (data / "updates").resolve())
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


def test_app_update_channel(client: TestClient) -> None:
    empty = client.get("/api/v1/app/update").json()
    assert empty["available"] is False

    updates_dir = settings.update_root
    apk = updates_dir / "comii-9.9.9.apk"
    apk.write_bytes(b"PK\x03\x04comii-demo-apk")
    (updates_dir / "update.json").write_text(
        '{"versionCode": 99, "versionName": "9.9.9", "notes": "test build", "apk": "comii-9.9.9.apk"}',
        encoding="utf-8",
    )

    body = client.get("/api/v1/app/update").json()
    assert body["available"] is True
    assert body["versionCode"] == 99
    assert body["versionName"] == "9.9.9"
    assert body["size"] == apk.stat().st_size

    download = client.get(body["url"])
    assert download.status_code == 200
    assert download.content.startswith(b"PK")
    assert download.headers["content-type"] == "application/vnd.android.package-archive"


class _FakeUpstream:
    def __init__(self, lines: list[bytes]) -> None:
        self.lines = lines

    def __enter__(self):
        return self

    def __exit__(self, *args) -> bool:
        return False

    def __iter__(self):
        return iter(self.lines)

    def read(self) -> bytes:
        return b"".join(self.lines)


def test_ai_proxy_rejects_bad_scheme(client: TestClient) -> None:
    response = client.post(
        "/api/v1/ai/chat",
        json={
            "baseUrl": "ftp://example.com",
            "model": "m",
            "messages": [{"role": "user", "content": "hi"}],
        },
    )
    assert response.status_code == 400


def test_ai_proxy_forwards_stream(client: TestClient, monkeypatch) -> None:
    from comii.api import ai as ai_module

    def fake_urlopen(request, timeout=0):
        body = json.loads(request.data)
        assert body["stream"] is True
        assert body["messages"][0]["content"] == "hi"
        return _FakeUpstream([
            b'data: {"choices":[{"delta":{"content":"he"}}]}\n',
            b'data: {"choices":[{"delta":{"content":"llo"}}]}\n',
            b"data: [DONE]\n",
        ])

    monkeypatch.setattr(ai_module.urllib.request, "urlopen", fake_urlopen)
    response = client.post(
        "/api/v1/ai/chat",
        json={
            "baseUrl": "https://example.com/v1",
            "model": "m",
            "stream": True,
            "messages": [{"role": "user", "content": "hi"}],
        },
    )
    assert response.status_code == 200, response.text
    assert "text/event-stream" in response.headers["content-type"]
    assert "he" in response.text and "llo" in response.text


def test_ai_proxy_forwards_json(client: TestClient, monkeypatch) -> None:
    from comii.api import ai as ai_module

    captured = {}

    def fake_urlopen(request, timeout=0):
        captured["url"] = request.full_url
        captured["auth"] = request.get_header("Authorization")
        return _FakeUpstream([
            b'{"choices":[{"message":{"role":"assistant","content":"ok"}}]}'
        ])

    monkeypatch.setattr(ai_module.urllib.request, "urlopen", fake_urlopen)
    response = client.post(
        "/api/v1/ai/chat",
        json={
            "baseUrl": "https://example.com",
            "apiKey": "sk-test",
            "model": "m",
            "stream": False,
            "messages": [{"role": "user", "content": "hi"}],
        },
    )
    assert response.status_code == 200
    assert response.json()["choices"][0]["message"]["content"] == "ok"
    assert captured["url"] == "https://example.com/v1/chat/completions"
    assert captured["auth"] == "Bearer sk-test"


def test_stats(client: TestClient) -> None:
    body = client.get("/api/v1/stats").json()
    assert body["series_count"] == 3
    assert body["image_count"] == 5
    assert body["video_count"] == 1


def test_new_empty_folder_is_visible(client: TestClient) -> None:
    created = client.post(
        "/api/v1/folders", json={"parent": "", "name": "新分类", "kind": "manga"}
    )
    assert created.status_code == 200
    names = {item["name"]: item for item in client.get("/api/v1/browse").json()["items"]}
    assert "新分类" in names
    assert names["新分类"]["kind"] == "folder"
    assert names["新分类"]["child_count"] == 0


def test_search_matches_folders_series_and_files(client: TestClient) -> None:
    client.post("/api/v1/folders", json={"parent": "", "name": "收藏夹", "kind": "manga"})

    folders = client.get("/api/v1/search", params={"q": "收藏"}).json()
    assert any(item["name"] == "收藏夹" and item["kind"] == "folder" for item in folders["items"])

    series = client.get("/api/v1/search", params={"q": "loose"}).json()
    assert any(item["name"] == "loose_series" for item in series["items"])

    by_file = client.get("/api/v1/search", params={"q": "002"}).json()
    assert any(item["name"] == "0002_second_series" for item in by_file["items"])

    assert client.get("/api/v1/search", params={"q": ""}).json()["total"] == 0


def test_legacy_nested_delete_uses_full_path(client: TestClient) -> None:
    """WebUI 删除必须传完整相对路径，多层文件夹内的系列才能正确进入回收站。"""
    deleted = client.delete("/api/delete/author_a/0001_first_series")
    assert deleted.status_code == 200
    assert not (library_root_path() / "author_a" / "0001_first_series").exists()

    trash_body = client.get("/api/trash").json()
    assert trash_body["items"][0]["name"] == "author_a/0001_first_series"

    restored = client.post("/api/trash/restore/author_a/0001_first_series")
    assert restored.status_code == 200
    assert (library_root_path() / "author_a" / "0001_first_series").is_dir()


def test_legacy_search_and_empty_folder(client: TestClient) -> None:
    created = client.post("/api/folder", json={"name": "空目录", "parent": "", "kind": "video"})
    assert created.status_code == 200

    tree = client.get("/api/tree").json()["tree"]
    node = next(item for item in tree if item["name"] == "空目录")
    assert node["kind"] == "folder"
    assert node["children"] == []

    found = client.get("/api/search", params={"q": "空目"}).json()["items"]
    assert any(item["name"] == "空目录" and item["kind"] == "folder" for item in found)

    series = client.get("/api/search", params={"q": "loose"}).json()["items"]
    assert any(item["name"] == "loose_series" and item["kind"] == "series" for item in series)


def test_folder_forced_kind_and_collection(client: TestClient) -> None:
    client.post("/api/v1/folders", json={"parent": "", "name": "漫画分类", "kind": "manga"})
    client.post("/api/v1/folders", json={"parent": "", "name": "视频分类", "kind": "video"})

    browse = {item["name"]: item for item in client.get("/api/v1/browse").json()["items"]}
    assert browse["漫画分类"]["forced_kind"] == "manga"
    assert browse["视频分类"]["forced_kind"] == "video"

    collection = client.get("/api/v1/collection", params={"path": "author_a"}).json()
    assert collection["total"] == 2
    assert {item["name"] for item in collection["items"]} == {
        "0001_first_series",
        "0002_second_series",
    }


def test_set_kind_for_existing_folder(client: TestClient) -> None:
    client.post("/api/v1/folders", json={"parent": "", "name": "待定分类", "kind": ""})

    def kind_of() -> str | None:
        items = {item["name"]: item for item in client.get("/api/v1/browse").json()["items"]}
        return items["待定分类"]["forced_kind"]

    assert kind_of() is None

    response = client.post("/api/v1/folders/kind", json={"path": "待定分类", "kind": "video"})
    assert response.status_code == 200
    assert kind_of() == "video"

    client.post("/api/v1/folders/kind", json={"path": "待定分类", "kind": ""})
    assert kind_of() is None


def test_gallery_and_custom_cover(client: TestClient) -> None:
    gallery = client.get("/api/v1/gallery").json()
    assert gallery["total"] == 6
    images = client.get("/api/v1/gallery", params={"kind": "image"}).json()
    assert images["total"] == 5
    videos = client.get("/api/v1/gallery", params={"kind": "video"}).json()
    assert videos["total"] == 1

    series = "author_a/0001_first_series"
    before = client.get("/api/v1/series", params={"path": series}).json()["cover"]
    updated = client.post("/api/v1/cover", json={"path": series, "name": "002.jpg"})
    assert updated.status_code == 200
    after = client.get("/api/v1/series", params={"path": series}).json()["cover"]
    assert after != before
    assert "002.jpg" in after

    tree = client.get("/api/tree").json()["tree"]
    author = next(item for item in tree if item["name"] == "author_a")
    first = next(item for item in author["children"] if item["name"] == "0001_first_series")
    assert "002.jpg" in first["cover"]
    assert author["cover"]  # 文件夹继承第一个子系列的封面

    legacy = client.get("/api/gallery").json()
    assert legacy["total"] == 6

    client.post("/api/v1/cover", json={"path": series, "name": ""})
    cleared = client.get("/api/v1/series", params={"path": series}).json()["cover"]
    assert "001.jpg" in cleared
