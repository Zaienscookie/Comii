"""Users, sessions and directory permissions for Comii.

The library is still the source of truth; permissions are grants on path
prefixes.  Roles:

* owner  – everything, including user management and R18
* admin  – all content operations, cannot manage users
* viewer – view only
* custom – only the capabilities granted on matching paths
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from pathlib import Path
from typing import Any, Iterable, Optional

from fastapi import HTTPException, Request

from .config import settings

DB_PATH = settings.data_root / "users.db"
COOKIE_NAME = "comii_session"
SESSION_TTL = 7 * 24 * 3600
REMEMBER_TTL = 30 * 24 * 3600
ACTIONS = ("view", "download", "upload", "create", "delete", "move", "manage")
ROLES = ("owner", "admin", "viewer", "custom")


def _connect() -> sqlite3.Connection:
    settings.data_root.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=10)
    connection.row_factory = sqlite3.Row
    return connection


def ensure_schema() -> None:
    with _connect() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'viewer',
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at REAL NOT NULL,
                last_login_at REAL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                expires_at REAL NOT NULL,
                created_at REAL NOT NULL,
                user_agent TEXT,
                remember INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS api_tokens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                token_hash TEXT NOT NULL UNIQUE,
                created_at REAL NOT NULL,
                expires_at REAL,
                last_used_at REAL,
                revoked INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS audit_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts REAL NOT NULL,
                user_id INTEGER,
                username TEXT,
                action TEXT NOT NULL,
                path TEXT,
                status INTEGER,
                ip TEXT,
                user_agent TEXT,
                detail TEXT
            );
            CREATE TABLE IF NOT EXISTS grants (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                path_prefix TEXT NOT NULL DEFAULT '',
                can_view INTEGER NOT NULL DEFAULT 0,
                can_download INTEGER NOT NULL DEFAULT 0,
                can_upload INTEGER NOT NULL DEFAULT 0,
                can_create INTEGER NOT NULL DEFAULT 0,
                can_delete INTEGER NOT NULL DEFAULT 0,
                can_move INTEGER NOT NULL DEFAULT 0,
                can_manage INTEGER NOT NULL DEFAULT 0,
                UNIQUE(user_id, path_prefix)
            );
            """
        )


def _hash_password(password: str) -> str:
    salt = os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 220_000)
    return f"pbkdf2$220000${salt.hex()}${digest.hex()}"


def _verify_password(password: str, stored: str) -> bool:
    try:
        scheme, rounds, salt_hex, digest_hex = stored.split("$")
        if scheme != "pbkdf2":
            return False
        digest = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), bytes.fromhex(salt_hex), int(rounds)
        )
        return hmac.compare_digest(digest.hex(), digest_hex)
    except Exception:
        return False


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _row_user(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": int(row["id"]),
        "username": row["username"],
        "role": row["role"],
        "enabled": bool(row["enabled"]),
        "created_at": row["created_at"],
        "last_login_at": row["last_login_at"],
    }


def enabled() -> bool:
    """Auth can be disabled for a trusted/local server (e.g. frp neko)."""
    return os.environ.get("COMII_AUTH_ENABLED", "1").strip().lower() not in {"0", "false", "no", "off"}


LOCAL_OWNER = {"id": 0, "username": "local", "role": "owner", "enabled": True, "created_at": 0, "last_login_at": 0}

def initialized() -> bool:
    ensure_schema()
    with _connect() as db:
        return db.execute("SELECT COUNT(*) FROM users").fetchone()[0] > 0


def count_users() -> int:
    with _connect() as db:
        return int(db.execute("SELECT COUNT(*) FROM users").fetchone()[0])


def setup_owner(username: str, password: str) -> dict[str, Any]:
    ensure_schema()
    if count_users() > 0:
        raise HTTPException(status_code=409, detail="已经初始化过了")
    username = username.strip()
    if not username or len(password) < 6:
        raise HTTPException(status_code=400, detail="用户名不能为空，密码至少 6 位")
    now = time.time()
    with _connect() as db:
        cursor = db.execute(
            "INSERT INTO users(username,password_hash,role,enabled,created_at) VALUES(?,?,?,?,?)",
            (username, _hash_password(password), "owner", 1, now),
        )
        user_id = int(cursor.lastrowid)
        row = db.execute("SELECT * FROM users WHERE id=?", (user_id,)).fetchone()
    return _row_user(row)


def find_user(username: str) -> Optional[dict[str, Any]]:
    with _connect() as db:
        row = db.execute("SELECT * FROM users WHERE username=?", (username.strip(),)).fetchone()
    return _row_user(row) if row else None


def user_by_id(user_id: int) -> Optional[dict[str, Any]]:
    with _connect() as db:
        row = db.execute("SELECT * FROM users WHERE id=?", (user_id,)).fetchone()
    return _row_user(row) if row else None


def create_session(user_id: int, remember: bool, user_agent: str = "") -> tuple[str, float]:
    token = secrets.token_urlsafe(32)
    now = time.time()
    ttl = REMEMBER_TTL if remember else SESSION_TTL
    expires = now + ttl
    with _connect() as db:
        db.execute(
            "INSERT INTO sessions(token_hash,user_id,expires_at,created_at,user_agent,remember) VALUES(?,?,?,?,?,?)",
            (_token_hash(token), user_id, expires, now, user_agent[:300], 1 if remember else 0),
        )
        db.execute("UPDATE users SET last_login_at=? WHERE id=?", (now, user_id))
    return token, expires


def revoke_session(token: str) -> None:
    if not token:
        return
    with _connect() as db:
        db.execute("DELETE FROM sessions WHERE token_hash=?", (_token_hash(token),))


def user_from_token(token: str) -> Optional[dict[str, Any]]:
    if not token:
        return None
    now = time.time()
    with _connect() as db:
        if token.startswith("cm_"):
            row = db.execute(
                "SELECT u.*, t.id AS token_id, t.expires_at AS token_expires "
                "FROM api_tokens t JOIN users u ON u.id=t.user_id "
                "WHERE t.token_hash=? AND t.revoked=0 AND u.enabled=1",
                (_token_hash(token),),
            ).fetchone()
            if row is None:
                return None
            if row["token_expires"] is not None and float(row["token_expires"]) < now:
                return None
            db.execute("UPDATE api_tokens SET last_used_at=? WHERE id=?", (now, row["token_id"]))
            return _row_user(row)
        row = db.execute(
            "SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id "
            "WHERE s.token_hash=? AND s.expires_at>? AND u.enabled=1",
            (_token_hash(token), now),
        ).fetchone()
    return _row_user(row) if row else None


def create_api_token(user_id: int, name: str, days: int = 30) -> tuple[str, dict[str, Any]]:
    if user_by_id(user_id) is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    token = "cm_" + secrets.token_urlsafe(36)
    now = time.time()
    expires = now + max(1, days) * 24 * 3600
    with _connect() as db:
        cursor = db.execute(
            "INSERT INTO api_tokens(user_id,name,token_hash,created_at,expires_at) VALUES(?,?,?,?,?)",
            (user_id, name.strip() or "API Token", _token_hash(token), now, expires),
        )
        token_id = int(cursor.lastrowid)
    return token, {"id": token_id, "name": name, "expires_at": expires}


def list_api_tokens(user_id: int) -> list[dict[str, Any]]:
    with _connect() as db:
        rows = db.execute(
            "SELECT id,name,created_at,expires_at,last_used_at,revoked FROM api_tokens WHERE user_id=? ORDER BY id DESC",
            (user_id,),
        ).fetchall()
    return [dict(row) for row in rows]


def revoke_api_token(token_id: int) -> None:
    with _connect() as db:
        db.execute("UPDATE api_tokens SET revoked=1 WHERE id=?", (token_id,))


def bearer_token(request: Request) -> str:
    header = request.headers.get("authorization", "")
    if header.lower().startswith("bearer "):
        return header[7:].strip()
    return request.cookies.get(COOKIE_NAME, "") or ""


def user_from_request(request: Request) -> Optional[dict[str, Any]]:
    if not enabled():
        return dict(LOCAL_OWNER)
    user = getattr(request.state, "user", None)
    if user is not None:
        return user
    return user_from_token(bearer_token(request))


def require_user(request: Request) -> dict[str, Any]:
    if not enabled():
        return dict(LOCAL_OWNER)
    user = user_from_request(request)
    if user is None:
        raise HTTPException(
            status_code=401,
            detail="请先登录",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return user


def require_owner(request: Request) -> dict[str, Any]:
    user = require_user(request)
    if user["role"] != "owner":
        raise HTTPException(status_code=403, detail="只有 Owner 可以管理用户")
    return user


def grants_for(user_id: int) -> list[dict[str, Any]]:
    with _connect() as db:
        rows = db.execute("SELECT * FROM grants WHERE user_id=? ORDER BY path_prefix", (user_id,)).fetchall()
    result = []
    for row in rows:
        result.append(
            {
                "path_prefix": row["path_prefix"],
                "view": bool(row["can_view"]),
                "download": bool(row["can_download"]),
                "upload": bool(row["can_upload"]),
                "create": bool(row["can_create"]),
                "delete": bool(row["can_delete"]),
                "move": bool(row["can_move"]),
                "manage": bool(row["can_manage"]),
            }
        )
    return result


def set_grants(user_id: int, grants: Iterable[dict[str, Any]]) -> None:
    if user_by_id(user_id) is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    with _connect() as db:
        db.execute("DELETE FROM grants WHERE user_id=?", (user_id,))
        for grant in grants:
            prefix = str(grant.get("path_prefix") or "").strip().strip("/")
            db.execute(
                "INSERT OR REPLACE INTO grants(user_id,path_prefix,can_view,can_download,can_upload,can_create,can_delete,can_move,can_manage) "
                "VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    user_id,
                    prefix,
                    1 if grant.get("view") else 0,
                    1 if grant.get("download") else 0,
                    1 if grant.get("upload") else 0,
                    1 if grant.get("create") else 0,
                    1 if grant.get("delete") else 0,
                    1 if grant.get("move") else 0,
                    1 if grant.get("manage") else 0,
                ),
            )


def _path_matches(prefix: str, path: str) -> bool:
    prefix = prefix.strip("/")
    path = path.strip("/")
    if not prefix:
        return True
    return path == prefix or path.startswith(prefix + "/")


def user_grants(user: dict[str, Any]) -> list[dict[str, Any]]:
    if user["role"] != "custom":
        return []
    return grants_for(int(user["id"]))


def can(user: dict[str, Any], path: str, action: str) -> bool:
    if action not in ACTIONS:
        return False
    role = user["role"]
    if role in ("owner", "admin"):
        return True
    if role == "viewer":
        return action == "view"
    if role != "custom":
        return False
    best: Optional[dict[str, Any]] = None
    best_len = -1
    for grant in user_grants(user):
        prefix = grant["path_prefix"].strip("/")
        if _path_matches(prefix, path) and len(prefix) >= best_len:
            best = grant
            best_len = len(prefix)
    return bool(best and best.get(action))


def can_see_path(user: dict[str, Any], path: str) -> bool:
    if user["role"] in ("owner", "admin", "viewer"):
        return True
    path = path.strip("/")
    for grant in user_grants(user):
        if not grant.get("view"):
            continue
        prefix = grant["path_prefix"].strip("/")
        if _path_matches(prefix, path) or (path and prefix.startswith(path + "/")):
            return True
    return False


def require_path(request: Request, path: str, action: str = "view") -> dict[str, Any]:
    user = require_user(request)
    if not can(user, path, action):
        raise HTTPException(status_code=403, detail=f"没有 {action} 权限")
    return user


def filter_nodes(user: dict[str, Any], nodes: Iterable[Any]) -> list[Any]:
    if user["role"] in ("owner", "admin", "viewer"):
        return list(nodes)
    return [node for node in nodes if can_see_path(user, getattr(node, "path", ""))]


def session_payload(user: dict[str, Any]) -> dict[str, Any]:
    return {
        "user": user,
        "grants": grants_for(int(user["id"])) if user["role"] == "custom" else [],
        "permissions": list(ACTIONS),
    }




AUDIT_RETENTION_DAYS = 30


def audit(
    user: Optional[dict[str, Any]],
    action: str,
    path: str = "",
    status: int = 0,
    ip: str = "",
    user_agent: str = "",
    detail: str = "",
) -> None:
    try:
        now = time.time()
        with _connect() as db:
            db.execute(
                "INSERT INTO audit_logs(ts,user_id,username,action,path,status,ip,user_agent,detail) "
                "VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    now,
                    int(user["id"]) if user else None,
                    user["username"] if user else "",
                    action,
                    path[:500],
                    status,
                    ip[:100],
                    user_agent[:300],
                    detail[:500],
                ),
            )
            db.execute(
                "DELETE FROM audit_logs WHERE ts < ?",
                (now - AUDIT_RETENTION_DAYS * 24 * 3600,),
            )
    except Exception:
        pass


def list_audit(limit: int = 200) -> list[dict[str, Any]]:
    limit = max(1, min(1000, int(limit)))
    with _connect() as db:
        rows = db.execute(
            "SELECT id,ts,user_id,username,action,path,status,ip,user_agent,detail "
            "FROM audit_logs ORDER BY id DESC LIMIT ?",
            (limit,),
        ).fetchall()
    return [dict(row) for row in rows]


def public_path(path: str) -> bool:
    if path in ("/health", "/api/v1/ping"):
        return True
    if path.startswith("/static/") or path in ("/login.html", "/login.js", "/favicon.svg"):
        return True
    if path.startswith("/api/v1/auth/"):
        return path in (
            "/api/v1/auth/status",
            "/api/v1/auth/setup",
            "/api/v1/auth/login",
        )
    if path.startswith("/api/v1/app/update"):
        return True
    return False
