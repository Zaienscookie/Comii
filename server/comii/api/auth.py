"""Authentication / user-management API."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request, Response

from .. import auth
from ..config import settings

router = APIRouter(prefix=settings.api_prefix, tags=["auth"])


def _cookie(response: Response, request: Request, token: str, remember: bool) -> None:
    response.set_cookie(
        auth.COOKIE_NAME,
        token,
        max_age=auth.REMEMBER_TTL if remember else auth.SESSION_TTL,
        httponly=True,
        samesite="lax",
        secure=request.url.scheme == "https",
        path="/",
    )


@router.get("/auth/status")
def status() -> dict:
    return {"initialized": auth.initialized(), "version": settings.version}


@router.post("/auth/setup")
def setup(payload: dict, request: Request, response: Response) -> dict:
    username = str(payload.get("username") or "")
    password = str(payload.get("password") or "")
    user = auth.setup_owner(username, password)
    token, expires = auth.create_session(int(user["id"]), True, request.headers.get("user-agent", ""))
    _cookie(response, request, token, True)
    return {"token": token, "expires_at": expires, **auth.session_payload(user)}


@router.post("/auth/login")
def login(payload: dict, request: Request, response: Response) -> dict:
    username = str(payload.get("username") or "")
    password = str(payload.get("password") or "")
    remember = bool(payload.get("remember", True))
    user = auth.find_user(username)
    if user is None or not user["enabled"]:
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    if not auth._verify_password(password, _password_hash(int(user["id"]))):
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    token, expires = auth.create_session(int(user["id"]), remember, request.headers.get("user-agent", ""))
    _cookie(response, request, token, remember)
    return {"token": token, "expires_at": expires, **auth.session_payload(user)}


def _password_hash(user_id: int) -> str:
    import sqlite3

    with auth._connect() as db:
        row = db.execute("SELECT password_hash FROM users WHERE id=?", (user_id,)).fetchone()
    return row["password_hash"] if row else ""


@router.post("/auth/logout")
def logout(request: Request, response: Response) -> dict:
    auth.revoke_session(auth.bearer_token(request))
    response.delete_cookie(auth.COOKIE_NAME, path="/")
    return {"status": "ok"}


@router.get("/auth/me")
def me(request: Request) -> dict:
    user = auth.require_user(request)
    return auth.session_payload(user)


# ---------------------------------------------------------------- admin users
def _owner(request: Request) -> dict:
    return auth.require_owner(request)


@router.get("/admin/users")
def list_users(request: Request) -> dict:
    _owner(request)
    import sqlite3

    with auth._connect() as db:
        rows = db.execute("SELECT id,username,role,enabled,created_at,last_login_at FROM users ORDER BY id").fetchall()
    users = []
    for row in rows:
        user = dict(row)
        user["grants"] = auth.grants_for(int(row["id"]))
        users.append(user)
    return {"items": users}


@router.post("/admin/users")
def create_user(payload: dict, request: Request) -> dict:
    _owner(request)
    import sqlite3

    username = str(payload.get("username") or "").strip()
    password = str(payload.get("password") or "")
    role = str(payload.get("role") or "viewer")
    if not username or len(password) < 6:
        raise HTTPException(status_code=400, detail="用户名不能为空，密码至少 6 位")
    if role not in auth.ROLES:
        raise HTTPException(status_code=400, detail="角色不合法")
    if auth.find_user(username):
        raise HTTPException(status_code=409, detail="用户名已存在")
    with auth._connect() as db:
        cursor = db.execute(
            "INSERT INTO users(username,password_hash,role,enabled,created_at) VALUES(?,?,?,?,?)",
            (username, auth._hash_password(password), role, 1, __import__("time").time()),
        )
        user_id = int(cursor.lastrowid)
    return auth.session_payload(auth.user_by_id(user_id) or {}) | {"status": "ok"}


@router.patch("/admin/users/{user_id}")
def update_user(user_id: int, payload: dict, request: Request) -> dict:
    _owner(request)
    user = auth.user_by_id(user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    fields = []
    values = []
    if "role" in payload:
        role = str(payload.get("role"))
        if role not in auth.ROLES:
            raise HTTPException(status_code=400, detail="角色不合法")
        fields.append("role=?")
        values.append(role)
    if "enabled" in payload:
        fields.append("enabled=?")
        values.append(1 if payload.get("enabled") else 0)
    if payload.get("password"):
        password = str(payload["password"])
        if len(password) < 6:
            raise HTTPException(status_code=400, detail="密码至少 6 位")
        fields.append("password_hash=?")
        values.append(auth._hash_password(password))
    if fields:
        import sqlite3

        with auth._connect() as db:
            db.execute(f"UPDATE users SET {', '.join(fields)} WHERE id=?", (*values, user_id))
    if payload.get("enabled") is False:
        import sqlite3

        with auth._connect() as db:
            db.execute("DELETE FROM sessions WHERE user_id=?", (user_id,))
    return {"status": "ok"}


@router.delete("/admin/users/{user_id}")
def delete_user(user_id: int, request: Request) -> dict:
    owner = _owner(request)
    if int(owner["id"]) == user_id:
        raise HTTPException(status_code=400, detail="不能删除自己")
    with auth._connect() as db:
        db.execute("DELETE FROM sessions WHERE user_id=?", (user_id,))
        db.execute("DELETE FROM grants WHERE user_id=?", (user_id,))
        db.execute("DELETE FROM users WHERE id=?", (user_id,))
    return {"status": "ok"}


@router.post("/admin/users/{user_id}/tokens")
def create_token(user_id: int, payload: dict, request: Request) -> dict:
    _owner(request)
    token, meta = auth.create_api_token(user_id, str(payload.get("name") or ""), int(payload.get("days") or 30))
    return {"token": token, **meta}


@router.get("/admin/users/{user_id}/tokens")
def list_tokens(user_id: int, request: Request) -> dict:
    _owner(request)
    return {"items": auth.list_api_tokens(user_id)}


@router.delete("/admin/tokens/{token_id}")
def delete_token(token_id: int, request: Request) -> dict:
    _owner(request)
    auth.revoke_api_token(token_id)
    return {"status": "ok"}


@router.get("/admin/users/{user_id}/grants")
def get_grants(user_id: int, request: Request) -> dict:
    _owner(request)
    return {"items": auth.grants_for(user_id)}


@router.put("/admin/users/{user_id}/grants")
def put_grants(user_id: int, payload: dict, request: Request) -> dict:
    _owner(request)
    auth.set_grants(user_id, payload.get("items") or [])
    return {"status": "ok", "items": auth.grants_for(user_id)}

