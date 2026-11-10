import os
import sqlite3
import secrets
import hashlib
from datetime import datetime, timezone, timedelta

from fastapi import FastAPI, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

DB = os.getenv("FREEGRAM_DB", "freegram.sqlite3")
BOT_SECRET = os.getenv("BOT_API_SECRET", "")
OWNER_SECRET = os.getenv("OWNER_API_SECRET", "")

app = FastAPI(title="FreeGram Auth API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

def connect():
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row
    return c

def init_db():
    with connect() as c:
        c.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            telegram_id INTEGER UNIQUE,
            virtual_id TEXT UNIQUE NOT NULL,
            otp_salt TEXT,
            otp_hash TEXT,
            otp_expires TEXT,
            verified INTEGER DEFAULT 0,
            token_hash TEXT,
            created_at TEXT NOT NULL
        )
        """)

def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()

def require_bot(secret):
    if not BOT_SECRET or secret != BOT_SECRET:
        raise HTTPException(403, "Bot access denied")

def require_owner(secret):
    if not OWNER_SECRET or secret != OWNER_SECRET:
        raise HTTPException(403, "Owner access denied")

class Registration(BaseModel):
    telegram_id: int

class Verification(BaseModel):
    virtual_id: str
    code: str

@app.on_event("startup")
def startup():
    init_db()

@app.get("/health")
def health():
    return {"project": "FreeGram", "status": "online", "api": "auth-v1"}

@app.post("/v1/registrations")
def register(data: Registration, x_bot_secret: str = Header(default="")):
    require_bot(x_bot_secret)

    virtual_id = "FG-" + secrets.token_hex(5).upper()
    code = "".join(str(secrets.randbelow(10)) for _ in range(6))
    salt = secrets.token_hex(16)
    now = datetime.now(timezone.utc)

    with connect() as c:
        c.execute("""
        INSERT INTO users
        (telegram_id, virtual_id, otp_salt, otp_hash, otp_expires,
         verified, token_hash, created_at)
        VALUES (?, ?, ?, ?, ?, 0, NULL, ?)
        ON CONFLICT(telegram_id) DO UPDATE SET
        virtual_id=excluded.virtual_id,
        otp_salt=excluded.otp_salt,
        otp_hash=excluded.otp_hash,
        otp_expires=excluded.otp_expires,
        verified=0,
        token_hash=NULL
        """, (
            data.telegram_id, virtual_id, salt,
            digest(salt + code),
            (now + timedelta(minutes=10)).isoformat(),
            now.isoformat()
        ))

    return {
        "ok": True,
        "virtual_id": virtual_id,
        "code": code,
        "expires_in": 600
    }

@app.post("/v1/auth/verify")
def verify(data: Verification):
    now = datetime.now(timezone.utc)

    with connect() as c:
        user = c.execute(
            "SELECT * FROM users WHERE virtual_id=?",
            (data.virtual_id.upper(),)
        ).fetchone()

        if not user or not user["otp_hash"]:
            raise HTTPException(400, "Invalid ID or code")

        if datetime.fromisoformat(user["otp_expires"]) < now:
            raise HTTPException(400, "Code expired")

        if not secrets.compare_digest(
            digest(user["otp_salt"] + data.code),
            user["otp_hash"]
        ):
            raise HTTPException(400, "Invalid ID or code")

        token = secrets.token_urlsafe(32)
        c.execute("""
        UPDATE users SET verified=1, otp_hash=NULL,
        otp_salt=NULL, otp_expires=NULL, token_hash=?
        WHERE id=?
        """, (digest(token), user["id"]))

    return {
        "ok": True,
        "access_token": token,
        "token_type": "Bearer",
        "virtual_id": data.virtual_id.upper()
    }

@app.get("/v1/me")
def me(authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Authorization required")

    token = authorization[7:]
    with connect() as c:
        user = c.execute(
            "SELECT virtual_id, telegram_id, verified, created_at "
            "FROM users WHERE token_hash=?",
            (digest(token),)
        ).fetchone()

    if not user or not user["verified"]:
        raise HTTPException(401, "Invalid session")

    return {"ok": True, **dict(user)}

@app.get("/v1/owner/status")
def owner_status(x_owner_secret: str = Header(default="")):
    require_owner(x_owner_secret)
    with connect() as c:
        total = c.execute("SELECT COUNT(*) FROM users").fetchone()[0]
        verified = c.execute(
            "SELECT COUNT(*) FROM users WHERE verified=1"
        ).fetchone()[0]

    return {
        "ok": True,
        "total_users": total,
        "verified_users": verified
    }
