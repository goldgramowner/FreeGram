import os
import secrets
import hashlib
import re
from datetime import datetime, timezone, timedelta

import psycopg
from psycopg.rows import dict_row
from fastapi import FastAPI, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

DATABASE_URL = os.getenv("DATABASE_URL", "").strip()
BOT_SECRET = os.getenv("BOT_API_SECRET", "")
OWNER_SECRET = os.getenv("OWNER_API_SECRET", "")

def connect():
    if not DATABASE_URL:
        raise RuntimeError("DATABASE_URL is not configured")
    url = DATABASE_URL.replace("postgres://", "postgresql://", 1) if DATABASE_URL.startswith("postgres://") else DATABASE_URL
    return psycopg.connect(url, row_factory=dict_row)

app = FastAPI(title="FreeGram API", version="1.2.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

def init_db():
    with connect() as c:
        c.execute("""CREATE TABLE IF NOT EXISTS users (
            id BIGSERIAL PRIMARY KEY, telegram_id BIGINT UNIQUE,
            virtual_id TEXT UNIQUE NOT NULL, otp_salt TEXT, otp_hash TEXT,
            otp_expires TEXT, verified INTEGER DEFAULT 0, token_hash TEXT,
            created_at TEXT NOT NULL)""")
        c.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS username TEXT")
        c.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS password_salt TEXT")
        c.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash TEXT")
        c.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_username_uq ON users (LOWER(username)) WHERE username IS NOT NULL")
        for sql in [
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS banned INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS stars BIGINT NOT NULL DEFAULT 1000",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS label TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS nft_username TEXT",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS virtual_number TEXT",
        ]:
            c.execute(sql)
        c.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_nft_username_uq ON users (LOWER(nft_username)) WHERE nft_username IS NOT NULL")
        c.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_virtual_number_uq ON users (virtual_number) WHERE virtual_number IS NOT NULL")
        c.execute("""CREATE TABLE IF NOT EXISTS chats (
            id BIGSERIAL PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('direct','group','channel')),
            title TEXT NOT NULL DEFAULT '', direct_key TEXT UNIQUE,
            owner_user_id BIGINT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL)""")
        c.execute("""CREATE TABLE IF NOT EXISTS chat_members (
            chat_id BIGINT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
            user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            joined_at TEXT NOT NULL, PRIMARY KEY(chat_id,user_id))""")
        c.execute("""CREATE TABLE IF NOT EXISTS messages (
            id BIGSERIAL PRIMARY KEY, chat_id BIGINT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
            user_id BIGINT NOT NULL REFERENCES users(id), body TEXT NOT NULL,
            created_at TEXT NOT NULL)""")
        c.execute("CREATE INDEX IF NOT EXISTS messages_chat_id_idx ON messages(chat_id,id)")

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

class DirectRegistration(BaseModel):
    username: str = Field(min_length=5, max_length=24)
    password: str = Field(min_length=8, max_length=128)

class DirectLogin(BaseModel):
    username: str = Field(min_length=5, max_length=24)
    password: str = Field(min_length=8, max_length=128)

class Verification(BaseModel):
    virtual_number: str = Field(pattern=r"^\+1[0-9]{10}$")
    code: str = Field(pattern=r"^[0-9]{6}$")

class OwnerAction(BaseModel):
    virtual_id: str = Field(min_length=3, max_length=80)
    action: str
    value: str | int | None = None
class ChatCreate(BaseModel):
    kind: str = Field(default="direct", pattern="^(direct|group|channel)$")
    title: str | None = Field(default=None, max_length=80)
    username: str | None = Field(default=None, min_length=5, max_length=24)

class MessageCreate(BaseModel):
    text: str = Field(min_length=1, max_length=4000)


@app.on_event("startup")
def startup():
    init_db()

@app.get("/health")
def health():
    return {"project": "FreeGram", "status": "online", "api": "auth-v1", "database": "postgresql"}

def password_digest(salt, password):
    return hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 240000).hex()

def issue_session(c, user_id):
    token = secrets.token_urlsafe(40)
    c.execute("UPDATE users SET token_hash=%s, verified=1 WHERE id=%s", (digest(token), user_id))
    return token

@app.post("/v1/auth/register")
def direct_register(data: DirectRegistration):
    username = data.username.strip().lower()
    if not re.fullmatch(r"[a-z0-9_]{5,24}", username):
        raise HTTPException(400, "Username: 5-24 латинских букв, цифр или _")
    salt = secrets.token_hex(16)
    now = datetime.now(timezone.utc).isoformat()
    virtual_id = "FG-" + secrets.token_hex(5).upper()
    with connect() as c:
        if c.execute("SELECT 1 FROM users WHERE LOWER(username)=LOWER(%s)", (username,)).fetchone():
            raise HTTPException(409, "Username already taken")
        try:
            cur = c.execute("""INSERT INTO users
                (virtual_id, verified, token_hash, created_at, username, password_salt, password_hash)
                VALUES (%s,1,NULL,%s,%s,%s,%s) RETURNING id""",
                (virtual_id, now, username, salt, password_digest(salt, data.password)))
        except psycopg.errors.UniqueViolation:
            raise HTTPException(409, "Username already taken")
        token = issue_session(c, cur.fetchone()["id"])
    return {"ok": True, "virtual_id": virtual_id, "username": username,
            "access_token": token, "token_type": "Bearer"}

@app.post("/v1/auth/login")
def direct_login(data: DirectLogin):
    username = data.username.strip().lower()
    with connect() as c:
        user = c.execute("SELECT id, virtual_id, password_salt, password_hash, banned FROM users WHERE LOWER(username)=LOWER(%s)", (username,)).fetchone()
        if not user or not user["password_hash"] or not secrets.compare_digest(password_digest(user["password_salt"], data.password), user["password_hash"]):
            raise HTTPException(401, "Неверный логин или пароль")
        if user["banned"]:
            raise HTTPException(403, "Account banned")
        token = issue_session(c, user["id"])
    return {"ok": True, "virtual_id": user["virtual_id"], "username": username,
            "access_token": token, "token_type": "Bearer"}

@app.post("/v1/registrations")
def register(data: Registration, x_bot_secret: str = Header(default="")):
    require_bot(x_bot_secret)
    virtual_id = "FG-" + secrets.token_hex(5).upper()
    virtual_number = "+1" + "".join(str(secrets.randbelow(10)) for _ in range(10))
    code = "".join(str(secrets.randbelow(10)) for _ in range(6))
    salt = secrets.token_hex(16)
    now = datetime.now(timezone.utc)
    with connect() as c:
        c.execute("""INSERT INTO users
            (telegram_id, virtual_id, virtual_number, otp_salt, otp_hash, otp_expires, verified, token_hash, created_at)
            VALUES (%s,%s,%s,%s,%s,%s,0,NULL,%s)
            ON CONFLICT (telegram_id) DO UPDATE SET
            virtual_id=EXCLUDED.virtual_id, virtual_number=EXCLUDED.virtual_number, otp_salt=EXCLUDED.otp_salt,
            otp_hash=EXCLUDED.otp_hash, otp_expires=EXCLUDED.otp_expires,
            verified=0, token_hash=NULL""",
            (data.telegram_id, virtual_id, virtual_number, salt, digest(salt + code),
             (now + timedelta(minutes=10)).isoformat(), now.isoformat()))
    return {"ok": True, "virtual_id": virtual_id, "virtual_number": virtual_number,
            "code": code, "expires_in": 600}

@app.post("/v1/auth/verify")
def verify(data: Verification):
    now = datetime.now(timezone.utc)
    with connect() as c:
        user = c.execute("SELECT * FROM users WHERE virtual_number=%s", (data.virtual_number,)).fetchone()
        if not user or not user["otp_hash"] or datetime.fromisoformat(user["otp_expires"]) < now:
            raise HTTPException(400, "Invalid ID or expired code")
        if not secrets.compare_digest(digest(user["otp_salt"] + data.code), user["otp_hash"]):
            raise HTTPException(400, "Invalid ID or code")
        if user["banned"]:
            raise HTTPException(403, "Account banned")
        token = secrets.token_urlsafe(32)
        c.execute("""UPDATE users SET verified=1, otp_hash=NULL, otp_salt=NULL,
            otp_expires=NULL, token_hash=%s WHERE id=%s""", (digest(token), user["id"]))
    return {"ok": True, "access_token": token, "token_type": "Bearer",
            "virtual_id": user["virtual_id"], "virtual_number": data.virtual_number}

@app.get("/v1/me")
def me(authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Authorization required")
    with connect() as c:
        user = c.execute("""SELECT virtual_id, username, telegram_id, verified, created_at,
            banned, stars, label, nft_username, virtual_number
            FROM users WHERE token_hash=%s""", (digest(authorization[7:]),)).fetchone()
    if not user or not user["verified"] or user["banned"]:
        raise HTTPException(401, "Invalid session")
    return {"ok": True, **user}


def authenticated_user(authorization):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Authorization required")
    with connect() as c:
        user = c.execute("SELECT id, virtual_id, username, banned, verified FROM users WHERE token_hash=%s",
                         (digest(authorization[7:]),)).fetchone()
    if not user or not user["verified"] or user["banned"]:
        raise HTTPException(401, "Invalid session")
    return user

@app.get("/v1/chats")
def list_chats(authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    with connect() as c:
        rows = c.execute("""SELECT ch.id,ch.kind,ch.title,ch.created_at,
            (SELECT m.body FROM messages m WHERE m.chat_id=ch.id ORDER BY m.id DESC LIMIT 1) AS last_message,
            (SELECT u.username FROM chat_members cm JOIN users u ON u.id=cm.user_id
             WHERE cm.chat_id=ch.id AND cm.user_id<>%s LIMIT 1) AS other_username
            FROM chats ch JOIN chat_members mine ON mine.chat_id=ch.id
            WHERE mine.user_id=%s ORDER BY ch.id DESC""", (user["id"],user["id"])).fetchall()
    return {"ok": True, "chats": rows}

@app.post("/v1/chats")
def create_chat(data: ChatCreate, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    now = datetime.now(timezone.utc).isoformat()
    with connect() as c:
        if data.kind == "direct":
            if not data.username:
                raise HTTPException(400, "username required for direct chat")
            other = c.execute("SELECT id,username FROM users WHERE LOWER(username)=LOWER(%s) AND verified=1 AND banned=0",
                              (data.username.strip(),)).fetchone()
            if not other:
                raise HTTPException(404, "User not found")
            if other["id"] == user["id"]:
                raise HTTPException(400, "Cannot create a chat with yourself")
            key = ":".join(map(str, sorted([user["id"], other["id"]])))
            chat = c.execute("SELECT id FROM chats WHERE direct_key=%s", (key,)).fetchone()
            if chat:
                return {"ok": True, "chat_id": chat["id"], "existing": True}
            cur = c.execute("""INSERT INTO chats(kind,title,direct_key,owner_user_id,created_at)
                VALUES('direct','',%s,%s,%s) RETURNING id""",(key,user["id"],now))
            chat_id=cur.fetchone()["id"]
            c.execute("INSERT INTO chat_members(chat_id,user_id,joined_at) VALUES(%s,%s,%s),(%s,%s,%s)",
                      (chat_id,user["id"],now,chat_id,other["id"],now))
        else:
            title=(data.title or "").strip()
            if len(title)<1:
                raise HTTPException(400, "Chat title required")
            cur=c.execute("""INSERT INTO chats(kind,title,owner_user_id,created_at)
                VALUES(%s,%s,%s,%s) RETURNING id""",(data.kind,title,user["id"],now))
            chat_id=cur.fetchone()["id"]
            c.execute("INSERT INTO chat_members(chat_id,user_id,joined_at) VALUES(%s,%s,%s)",
                      (chat_id,user["id"],now))
    return {"ok": True, "chat_id": chat_id, "existing": False}

@app.get("/v1/chats/{chat_id}/messages")
def get_messages(chat_id: int, authorization: str = Header(default=""), after_id: int = 0):
    user=authenticated_user(authorization)
    with connect() as c:
        member=c.execute("SELECT 1 FROM chat_members WHERE chat_id=%s AND user_id=%s",(chat_id,user["id"])).fetchone()
        if not member: raise HTTPException(404,"Chat not found")
        rows=c.execute("""SELECT m.id,m.body,m.created_at,u.username,u.virtual_id
            FROM messages m JOIN users u ON u.id=m.user_id
            WHERE m.chat_id=%s AND m.id>%s ORDER BY m.id ASC LIMIT 200""",(chat_id,after_id)).fetchall()
    return {"ok":True,"messages":rows}

@app.post("/v1/chats/{chat_id}/messages")
def send_message(chat_id: int, data: MessageCreate, authorization: str = Header(default="")):
    user=authenticated_user(authorization)
    body=data.text.strip()
    if not body: raise HTTPException(400,"Message cannot be empty")
    now=datetime.now(timezone.utc).isoformat()
    with connect() as c:
        chat=c.execute("SELECT kind,owner_user_id FROM chats WHERE id=%s",(chat_id,)).fetchone()
        member=c.execute("SELECT 1 FROM chat_members WHERE chat_id=%s AND user_id=%s",(chat_id,user["id"])).fetchone()
        if not chat or not member: raise HTTPException(404,"Chat not found")
        if chat["kind"]=="channel" and chat["owner_user_id"]!=user["id"]:
            raise HTTPException(403,"Only channel owner can post")
        row=c.execute("""INSERT INTO messages(chat_id,user_id,body,created_at)
            VALUES(%s,%s,%s,%s) RETURNING id,body,created_at""",(chat_id,user["id"],body,now)).fetchone()
    return {"ok":True,"message":{**row,"username":user["username"],"virtual_id":user["virtual_id"]}}

@app.get("/v1/owner/status")
def owner_status(x_owner_secret: str = Header(default="")):
    require_owner(x_owner_secret)
    with connect() as c:
        total = c.execute("SELECT COUNT(*) AS n FROM users").fetchone()["n"]
        verified = c.execute("SELECT COUNT(*) AS n FROM users WHERE verified=1").fetchone()["n"]
        banned = c.execute("SELECT COUNT(*) AS n FROM users WHERE banned=1").fetchone()["n"]
    return {"ok": True, "total_users": total, "verified_users": verified, "banned_users": banned}

@app.get("/v1/owner/users")
def owner_users(x_owner_secret: str = Header(default="")):
    require_owner(x_owner_secret)
    with connect() as c:
        rows = c.execute("""SELECT virtual_id, telegram_id, verified, banned,
            stars, label, nft_username, virtual_number, created_at
            FROM users ORDER BY id DESC LIMIT 200""").fetchall()
    return {"ok": True, "users": rows}

@app.post("/v1/owner/action")
def owner_action(data: OwnerAction, x_owner_secret: str = Header(default="")):
    require_owner(x_owner_secret)
    actions = {"ban", "unban", "verify", "unverify", "stars_add", "stars_remove", "stars_set",
               "label", "nft_set", "nft_remove", "number_set", "number_remove"}
    if data.action not in actions:
        raise HTTPException(400, "Unknown action")
    with connect() as c:
        user = c.execute("SELECT id, stars FROM users WHERE virtual_id=%s", (data.virtual_id.upper(),)).fetchone()
        if not user:
            raise HTTPException(404, "User not found")
        if data.action == "ban":
            c.execute("UPDATE users SET banned=1, token_hash=NULL WHERE id=%s", (user["id"],))
        elif data.action == "unban":
            c.execute("UPDATE users SET banned=0 WHERE id=%s", (user["id"],))
        elif data.action == "verify":
            c.execute("UPDATE users SET verified=1 WHERE id=%s", (user["id"],))
        elif data.action == "unverify":
            c.execute("UPDATE users SET verified=0, token_hash=NULL WHERE id=%s", (user["id"],))
        elif data.action in ("stars_add", "stars_remove", "stars_set"):
            try: amount = int(data.value)
            except (TypeError, ValueError): raise HTTPException(400, "Stars must be an integer")
            if amount < 0 or amount > 1000000000: raise HTTPException(400, "Invalid Stars amount")
            if data.action == "stars_add":
                c.execute("UPDATE users SET stars=stars + %s WHERE id=%s", (amount, user["id"]))
            elif data.action == "stars_remove":
                c.execute("UPDATE users SET stars=GREATEST(0, stars - %s) WHERE id=%s", (amount, user["id"]))
            else:
                c.execute("UPDATE users SET stars=%s WHERE id=%s", (amount, user["id"]))
        elif data.action == "label":
            label = str(data.value or "").strip()[:32]
            c.execute("UPDATE users SET label=%s WHERE id=%s", (label, user["id"]))
        elif data.action == "nft_set":
            name = str(data.value or "").strip().lstrip("@").lower()
            if not name or len(name) > 32 or not name.replace("_", "").isalnum():
                raise HTTPException(400, "Invalid NFT username")
            try: c.execute("UPDATE users SET nft_username=%s WHERE id=%s", (name, user["id"]))
            except psycopg.errors.UniqueViolation: raise HTTPException(409, "NFT username already assigned")
        elif data.action == "nft_remove":
            c.execute("UPDATE users SET nft_username=NULL WHERE id=%s", (user["id"],))
        elif data.action == "number_set":
            number = str(data.value or "").strip()
            valid_us_virtual = number.startswith("+1") and len(number) == 12 and number[1:].isdigit()
            valid_legacy = number.startswith("+888") and len(number) in (12, 13, 14) and number[1:].isdigit()
            if not (valid_us_virtual or valid_legacy):
                raise HTTPException(400, "Use a +1 virtual number (10 digits) or legacy +888 number")
            try: c.execute("UPDATE users SET virtual_number=%s WHERE id=%s", (number, user["id"]))
            except psycopg.errors.UniqueViolation: raise HTTPException(409, "Number already assigned")
        elif data.action == "number_remove":
            c.execute("UPDATE users SET virtual_number=NULL WHERE id=%s", (user["id"],))
        result = c.execute("""SELECT virtual_id, verified, banned, stars, label,
            nft_username, virtual_number FROM users WHERE id=%s""", (user["id"],)).fetchone()
    return {"ok": True, "user": result}
