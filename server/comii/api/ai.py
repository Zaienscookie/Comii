"""OpenAI-compatible proxy for the WebUI / WebView AI page.

The browser page calls this same-origin endpoint; the server forwards the
request to the user's OpenAI-compatible base URL.  No key is stored server
side — it is passed with each request and only used for the upstream call.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Iterator

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel

router = APIRouter(prefix="/api/v1/ai", tags=["ai"])

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36 Comii-WebAI"
)
TIMEOUT = 300


class AiMessage(BaseModel):
    role: str
    content: str


class AiChatRequest(BaseModel):
    baseUrl: str
    apiKey: str = ""
    model: str
    messages: list[AiMessage]
    temperature: float = 0.8
    stream: bool = True


def _endpoint(base: str) -> str:
    base = (base or "").strip().rstrip("/")
    if not base.startswith(("http://", "https://")):
        raise HTTPException(status_code=400, detail="Base URL 必须是 http(s) 地址")
    if base.endswith("/chat/completions"):
        return base
    if base.endswith("/v1"):
        return f"{base}/chat/completions"
    return f"{base}/v1/chat/completions"


def _dump(message: AiMessage) -> dict:
    if hasattr(message, "model_dump"):
        return message.model_dump()
    return message.dict()


def _payload(payload: AiChatRequest) -> bytes:
    body = {
        "model": payload.model.strip(),
        "messages": [_dump(message) for message in payload.messages],
        "temperature": payload.temperature,
        "stream": payload.stream,
    }
    return json.dumps(body, ensure_ascii=False).encode("utf-8")


def _headers(payload: AiChatRequest) -> dict[str, str]:
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "User-Agent": USER_AGENT,
        "Accept": "text/event-stream" if payload.stream else "application/json",
    }
    if payload.apiKey.strip():
        headers["Authorization"] = f"Bearer {payload.apiKey.strip()}"
    return headers


def _error_detail(raw: bytes, code: int) -> str:
    try:
        data = json.loads(raw.decode("utf-8", "replace"))
        if isinstance(data.get("error"), dict) and data["error"].get("message"):
            return str(data["error"]["message"])
        if data.get("message"):
            return str(data["message"])
    except (ValueError, UnicodeDecodeError):
        pass
    return f"上游返回 HTTP {code}"


@router.post("/chat", response_model=None)
def ai_chat(payload: AiChatRequest):
    if not payload.model.strip():
        raise HTTPException(status_code=400, detail="model 不能为空")
    if not payload.messages:
        raise HTTPException(status_code=400, detail="messages 不能为空")
    request = urllib.request.Request(
        _endpoint(payload.baseUrl),
        data=_payload(payload),
        headers=_headers(payload),
        method="POST",
    )

    if payload.stream:
        try:
            upstream = urllib.request.urlopen(request, timeout=TIMEOUT)
        except urllib.error.HTTPError as error:
            raise HTTPException(status_code=502, detail=_error_detail(error.read(), error.code))
        except Exception as error:  # noqa: BLE001
            raise HTTPException(status_code=502, detail=f"连接失败：{error}")

        def generate() -> Iterator[bytes]:
            try:
                with upstream:
                    for raw in upstream:
                        line = raw.decode("utf-8", "replace").strip()
                        if line.startswith("data:"):
                            yield (line + "\n\n").encode("utf-8")
            except Exception as error:  # noqa: BLE001
                message = json.dumps({"error": {"message": str(error)}}, ensure_ascii=False)
                yield f"data: {message}\n\n".encode("utf-8")

        return StreamingResponse(
            generate(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            return JSONResponse(json.loads(response.read().decode("utf-8", "replace")))
    except urllib.error.HTTPError as error:
        raise HTTPException(status_code=502, detail=_error_detail(error.read(), error.code))
    except Exception as error:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=f"连接失败：{error}")
