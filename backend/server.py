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
        c.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name TEXT NOT NULL DEFAULT ''")
        c.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS bio TEXT NOT NULL DEFAULT ''")
        c.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url TEXT NOT NULL DEFAULT ''")
        c.execute("ALTER TABLE chats ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT ''")
        c.execute("ALTER TABLE chats ADD COLUMN IF NOT EXISTS cover_url TEXT NOT NULL DEFAULT ''")
        c.execute("ALTER TABLE chats ADD COLUMN IF NOT EXISTS invite_code TEXT")
        c.execute("CREATE UNIQUE INDEX IF NOT EXISTS chats_invite_code_uq ON chats(invite_code) WHERE invite_code IS NOT NULL")
        c.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS nft_rarity TEXT NOT NULL DEFAULT 'common'")
        c.execute("""CREATE TABLE IF NOT EXISTS gift_types (
            id BIGSERIAL PRIMARY KEY, code TEXT UNIQUE NOT NULL, title TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT '', price BIGINT NOT NULL CHECK(price >= 0),
            image_url TEXT NOT NULL DEFAULT '', rarity TEXT NOT NULL DEFAULT 'common',
            active INTEGER NOT NULL DEFAULT 1)""")
        c.execute("""CREATE TABLE IF NOT EXISTS user_gifts (
            id BIGSERIAL PRIMARY KEY, gift_type_id BIGINT NOT NULL REFERENCES gift_types(id),
            owner_user_id BIGINT NOT NULL REFERENCES users(id), sender_user_id BIGINT REFERENCES users(id),
            chat_id BIGINT REFERENCES chats(id), created_at TEXT NOT NULL)""")
        c.execute("CREATE INDEX IF NOT EXISTS user_gifts_owner_idx ON user_gifts(owner_user_id,id DESC)")
        for gift in [
            ("rose", "Роза", "Небольшой знак внимания", 15, "common"),
            ("heart", "Сердце", "Подарок с теплом", 35, "common"),
            ("crown", "Корона", "Особый подарок", 250, "rare"),
            ("dragon", "Дракон", "Коллекционный подарок", 900, "epic"),
        ]:
            c.execute("""INSERT INTO gift_types(code,title,description,price,rarity)
                VALUES(%s,%s,%s,%s,%s) ON CONFLICT(code) DO NOTHING""", gift)

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

class LoginCodeRequest(BaseModel):
    virtual_id: str = Field(min_length=13, max_length=13)
    telegram_id: int

class DirectRegistration(BaseModel):
    username: str = Field(min_length=5, max_length=24)
    password: str = Field(min_length=8, max_length=128)

class DirectLogin(BaseModel):
    username: str = Field(min_length=5, max_length=24)
    password: str = Field(min_length=8, max_length=128)

class Verification(BaseModel):
    virtual_id: str
    code: str

class OwnerAction(BaseModel):
    virtual_id: str = Field(min_length=3, max_length=80)
    action: str
    value: str | int | None = None
class ChatCreate(BaseModel):
    kind: str = Field(default="direct", pattern="^(direct|group|channel)$")
    title: str | None = Field(default=None, max_length=80)
    username: str | None = Field(default=None, min_length=5, max_length=24)

class ProfileUpdate(BaseModel):
    username: str | None = Field(default=None, min_length=5, max_length=24)
    display_name: str | None = Field(default=None, max_length=64)
    bio: str | None = Field(default=None, max_length=160)
    avatar_url: str | None = Field(default=None, max_length=500)

class ChatUpdate(BaseModel):
    title: str | None = Field(default=None, max_length=80)
    description: str | None = Field(default=None, max_length=255)
    cover_url: str | None = Field(default=None, max_length=500)

class GiftSend(BaseModel):
    gift_code: str = Field(min_length=2, max_length=32)
    recipient_username: str = Field(min_length=5, max_length=24)
    chat_id: int | None = None

class SearchQuery(BaseModel):
    query: str = Field(min_length=2, max_length=40)

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
    now = datetime.now(timezone.utc)
    code = "".join(str(secrets.randbelow(10)) for _ in range(6))
    salt = secrets.token_hex(16)
    expires = (now + timedelta(minutes=10)).isoformat()
    with connect() as c:
        existing = c.execute(
            "SELECT id, virtual_id, banned FROM users WHERE telegram_id=%s",
            (data.telegram_id,)
        ).fetchone()
        if existing:
            if existing["banned"]:
                raise HTTPException(403, "Account banned")
            virtual_id = existing["virtual_id"]
            c.execute("""UPDATE users SET otp_salt=%s, otp_hash=%s, otp_expires=%s
                WHERE id=%s""",
                (salt, digest(salt + code), expires, existing["id"]))
        else:
            virtual_id = "FG-" + secrets.token_hex(5).upper()
            c.execute("""INSERT INTO users
                (telegram_id, virtual_id, otp_salt, otp_hash, otp_expires,
                 verified, token_hash, created_at)
                VALUES (%s,%s,%s,%s,%s,0,NULL,%s)""",
                (data.telegram_id, virtual_id, salt, digest(salt + code),
                 expires, now.isoformat()))
    return {"ok": True, "virtual_id": virtual_id, "code": code, "expires_in": 600}


@app.post("/v1/auth/code")
def issue_login_code(data: LoginCodeRequest, x_bot_secret: str = Header(default="")):
    require_bot(x_bot_secret)
    now = datetime.now(timezone.utc)
    code = "".join(str(secrets.randbelow(10)) for _ in range(6))
    salt = secrets.token_hex(16)
    with connect() as c:
        user = c.execute("""SELECT id, telegram_id, banned FROM users
            WHERE virtual_id=%s""", (data.virtual_id.upper(),)).fetchone()
        if not user or user["telegram_id"] != data.telegram_id:
            raise HTTPException(404, "FreeGram ID not found for this Telegram account")
        if user["banned"]:
            raise HTTPException(403, "Account banned")
        c.execute("""UPDATE users SET otp_salt=%s, otp_hash=%s, otp_expires=%s
            WHERE id=%s""",
            (salt, digest(salt + code), (now + timedelta(minutes=10)).isoformat(), user["id"]))
    return {"ok": True, "virtual_id": data.virtual_id.upper(),
            "code": code, "expires_in": 600}


@app.post("/v1/auth/verify")
def verify(data: Verification):
    now = datetime.now(timezone.utc)
    with connect() as c:
        user = c.execute("SELECT * FROM users WHERE virtual_id=%s", (data.virtual_id.upper(),)).fetchone()
        if not user or not user["otp_hash"] or datetime.fromisoformat(user["otp_expires"]) < now:
            raise HTTPException(400, "Invalid ID or expired code")
        if not secrets.compare_digest(digest(user["otp_salt"] + data.code), user["otp_hash"]):
            raise HTTPException(400, "Invalid ID or code")
        if user["banned"]:
            raise HTTPException(403, "Account banned")
        token = secrets.token_urlsafe(32)
        c.execute("""UPDATE users SET verified=1, otp_hash=NULL, otp_salt=NULL,
            otp_expires=NULL, token_hash=%s WHERE id=%s""", (digest(token), user["id"]))
    return {"ok": True, "access_token": token, "token_type": "Bearer", "virtual_id": data.virtual_id.upper()}

@app.get("/v1/me")
def me(authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Authorization required")
    with connect() as c:
        user = c.execute("""SELECT virtual_id, username, telegram_id, verified, created_at,
            banned, stars, label, nft_username, nft_rarity, virtual_number,
            display_name, bio, avatar_url
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
        rows = c.execute("""SELECT ch.id,ch.kind,ch.title,ch.description,ch.cover_url,
            ch.invite_code,ch.created_at,
            (SELECT m.body FROM messages m WHERE m.chat_id=ch.id ORDER BY m.id DESC LIMIT 1) AS last_message,
            (SELECT u.username FROM chat_members cm JOIN users u ON u.id=cm.user_id
             WHERE cm.chat_id=ch.id AND cm.user_id<>%s LIMIT 1) AS other_username,
            (SELECT COUNT(*) FROM chat_members cm WHERE cm.chat_id=ch.id) AS member_count
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
            invite_code = secrets.token_urlsafe(9)
            cur=c.execute("""INSERT INTO chats(kind,title,owner_user_id,created_at,invite_code)
                VALUES(%s,%s,%s,%s,%s) RETURNING id""",
                (data.kind,title,user["id"],now,invite_code))
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


@app.patch("/v1/me")
def update_me(data: ProfileUpdate, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    fields = data.model_dump(exclude_unset=True)
    if not fields:
        raise HTTPException(400, "No profile changes supplied")
    if "username" in fields and fields["username"] is not None:
        name = fields["username"].strip().lower()
        if not re.fullmatch(r"[a-z0-9_]{5,24}", name):
            raise HTTPException(400, "Username: 5-24 латинских букв, цифр или _")
        with connect() as c:
            other = c.execute("SELECT id FROM users WHERE LOWER(username)=LOWER(%s) AND id<>%s",
                              (name, user["id"])).fetchone()
            if other:
                raise HTTPException(409, "Username already taken")
            fields["username"] = name
    allowed = {"username", "display_name", "bio", "avatar_url"}
    if not set(fields).issubset(allowed):
        raise HTTPException(400, "Unsupported profile field")
    with connect() as c:
        for key, value in fields.items():
            c.execute(f"UPDATE users SET {key}=%s WHERE id=%s", (value or "", user["id"]))
        row = c.execute("""SELECT virtual_id,username,display_name,bio,avatar_url,
            verified,label,nft_username,virtual_number,stars FROM users WHERE id=%s""",
            (user["id"],)).fetchone()
    return {"ok": True, "profile": row}


@app.get("/v1/users/search")
def search_users(q: str, authorization: str = Header(default="")):
    authenticated_user(authorization)
    q = q.strip().lstrip("@").lower()
    if len(q) < 2 or len(q) > 40:
        raise HTTPException(400, "Search query must contain 2-40 characters")
    with connect() as c:
        rows = c.execute("""SELECT virtual_id,username,display_name,bio,avatar_url,
            verified,label,nft_username,virtual_number
            FROM users WHERE verified=1 AND banned=0
            AND (LOWER(username) LIKE %s OR LOWER(nft_username) LIKE %s
                 OR LOWER(display_name) LIKE %s)
            ORDER BY verified DESC, username LIMIT 30""",
            (f"%{q}%", f"%{q}%", f"%{q}%")).fetchall()
    return {"ok": True, "users": rows}


@app.get("/v1/users/{username}")
def get_user_profile(username: str, authorization: str = Header(default="")):
    authenticated_user(authorization)
    name = username.strip().lstrip("@").lower()
    with connect() as c:
        row = c.execute("""SELECT virtual_id,username,display_name,bio,avatar_url,
            verified,label,nft_username,virtual_number
            FROM users WHERE LOWER(username)=LOWER(%s) AND verified=1 AND banned=0""",
            (name,)).fetchone()
    if not row:
        raise HTTPException(404, "User not found")
    return {"ok": True, "profile": row}


@app.get("/v1/chats/{chat_id}")
def chat_details(chat_id: int, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    with connect() as c:
        member = c.execute("SELECT 1 FROM chat_members WHERE chat_id=%s AND user_id=%s",
                           (chat_id, user["id"])).fetchone()
        if not member:
            raise HTTPException(404, "Chat not found")
        chat = c.execute("""SELECT id,kind,title,description,cover_url,invite_code,
            owner_user_id,created_at FROM chats WHERE id=%s""", (chat_id,)).fetchone()
        members = c.execute("""SELECT u.virtual_id,u.username,u.display_name,u.avatar_url,
            u.verified,u.label,u.nft_username
            FROM chat_members cm JOIN users u ON u.id=cm.user_id
            WHERE cm.chat_id=%s ORDER BY cm.joined_at LIMIT 200""", (chat_id,)).fetchall()
    return {"ok": True, "chat": chat, "members": members}


@app.patch("/v1/chats/{chat_id}")
def update_chat(chat_id: int, data: ChatUpdate, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    fields = data.model_dump(exclude_unset=True)
    if not fields:
        raise HTTPException(400, "No chat changes supplied")
    with connect() as c:
        chat = c.execute("SELECT owner_user_id,kind FROM chats WHERE id=%s", (chat_id,)).fetchone()
        if not chat or chat["owner_user_id"] != user["id"]:
            raise HTTPException(403, "Only chat owner can edit this chat")
        for key, value in fields.items():
            c.execute(f"UPDATE chats SET {key}=%s WHERE id=%s", (value or "", chat_id))
        row = c.execute("SELECT id,kind,title,description,cover_url,invite_code FROM chats WHERE id=%s",
                        (chat_id,)).fetchone()
    return {"ok": True, "chat": row}


@app.post("/v1/chats/{chat_id}/invite")
def refresh_chat_invite(chat_id: int, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    with connect() as c:
        chat = c.execute("SELECT owner_user_id,kind FROM chats WHERE id=%s", (chat_id,)).fetchone()
        if not chat or chat["owner_user_id"] != user["id"]:
            raise HTTPException(403, "Only chat owner can create invite links")
        code = secrets.token_urlsafe(9)
        c.execute("UPDATE chats SET invite_code=%s WHERE id=%s", (code, chat_id))
    return {"ok": True, "invite_code": code, "invite_link": "https://freegram-x.onrender.com/i/" + code}


@app.post("/v1/invites/{code}/join")
def join_by_invite(code: str, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    now = datetime.now(timezone.utc).isoformat()
    with connect() as c:
        chat = c.execute("SELECT id,kind FROM chats WHERE invite_code=%s", (code,)).fetchone()
        if not chat:
            raise HTTPException(404, "Invite link is invalid or revoked")
        c.execute("""INSERT INTO chat_members(chat_id,user_id,joined_at)
            VALUES(%s,%s,%s) ON CONFLICT(chat_id,user_id) DO NOTHING""",
            (chat["id"], user["id"], now))
    return {"ok": True, "chat_id": chat["id"], "kind": chat["kind"]}


@app.get("/v1/shop/gifts")
def gift_catalog(authorization: str = Header(default="")):
    authenticated_user(authorization)
    with connect() as c:
        rows = c.execute("""SELECT code,title,description,price,image_url,rarity
            FROM gift_types WHERE active=1 ORDER BY price,id""").fetchall()
    return {"ok": True, "gifts": rows}


@app.get("/v1/shop/my-gifts")
def my_gifts(authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    with connect() as c:
        rows = c.execute("""SELECT ug.id,gt.code,gt.title,gt.description,gt.image_url,gt.rarity,
            sender.username AS sender_username,ug.created_at
            FROM user_gifts ug JOIN gift_types gt ON gt.id=ug.gift_type_id
            LEFT JOIN users sender ON sender.id=ug.sender_user_id
            WHERE ug.owner_user_id=%s ORDER BY ug.id DESC LIMIT 200""",
            (user["id"],)).fetchall()
    return {"ok": True, "gifts": rows}


@app.post("/v1/shop/gifts/send")
def send_gift(data: GiftSend, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    recipient_name = data.recipient_username.strip().lstrip("@").lower()
    now = datetime.now(timezone.utc).isoformat()
    with connect() as c:
        recipient = c.execute("""SELECT id,username FROM users
            WHERE LOWER(username)=LOWER(%s) AND verified=1 AND banned=0""",
            (recipient_name,)).fetchone()
        if not recipient:
            raise HTTPException(404, "Recipient not found")
        if recipient["id"] == user["id"]:
            raise HTTPException(400, "You cannot send a gift to yourself")
        gift = c.execute("SELECT id,price FROM gift_types WHERE code=%s AND active=1",
                         (data.gift_code.lower(),)).fetchone()
        if not gift:
            raise HTTPException(404, "Gift not found")
        if data.chat_id is not None:
            member = c.execute("SELECT 1 FROM chat_members WHERE chat_id=%s AND user_id=%s",
                               (data.chat_id, user["id"])).fetchone()
            recipient_member = c.execute("SELECT 1 FROM chat_members WHERE chat_id=%s AND user_id=%s",
                                         (data.chat_id, recipient["id"])).fetchone()
            if not member or not recipient_member:
                raise HTTPException(403, "Both users must be chat members")
        balance = c.execute("SELECT stars FROM users WHERE id=%s FOR UPDATE",
                            (user["id"],)).fetchone()["stars"]
        if balance < gift["price"]:
            raise HTTPException(400, "Not enough Stars")
        c.execute("UPDATE users SET stars=stars-%s WHERE id=%s", (gift["price"], user["id"]))
        cur = c.execute("""INSERT INTO user_gifts(gift_type_id,owner_user_id,sender_user_id,chat_id,created_at)
            VALUES(%s,%s,%s,%s,%s) RETURNING id""",
            (gift["id"], recipient["id"], user["id"], data.chat_id, now))
        gift_id = cur.fetchone()["id"]
    return {"ok": True, "gift_id": gift_id, "recipient": recipient["username"],
            "spent_stars": gift["price"], "remaining_stars": balance - gift["price"]}


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
            stars, label, nft_username, nft_rarity, virtual_number, created_at
            FROM users ORDER BY id DESC LIMIT 200""").fetchall()
    return {"ok": True, "users": rows}

@app.post("/v1/owner/action")
def owner_action(data: OwnerAction, x_owner_secret: str = Header(default="")):
    require_owner(x_owner_secret)
    actions = {"ban", "unban", "verify", "unverify", "stars_add", "stars_remove", "stars_set",
               "label", "nft_set", "nft_remove", "number_set", "number_remove", "number_generate"}
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
            raw = str(data.value or "").strip()
            parts = raw.split("|", 1)
            name = parts[0].strip().lstrip("@").lower()
            rarity = parts[1].strip().lower() if len(parts) > 1 else "common"
            if not name or len(name) > 32 or not name.replace("_", "").isalnum():
                raise HTTPException(400, "Invalid NFT username")
            if rarity not in ("common", "rare", "epic", "legendary"):
                raise HTTPException(400, "Rarity must be common, rare, epic or legendary")
            try: c.execute("UPDATE users SET nft_username=%s,nft_rarity=%s WHERE id=%s", (name, rarity, user["id"]))
            except psycopg.errors.UniqueViolation: raise HTTPException(409, "NFT username already assigned")
        elif data.action == "nft_remove":
            c.execute("UPDATE users SET nft_username=NULL WHERE id=%s", (user["id"],))
        elif data.action == "number_set":
            number = str(data.value or "").strip()
            if not number.startswith("+888") or not number[1:].isdigit() or len(number) not in (12, 13, 14):
                raise HTTPException(400, "Number must be a valid +888 virtual number")
            try: c.execute("UPDATE users SET virtual_number=%s WHERE id=%s", (number, user["id"]))
            except psycopg.errors.UniqueViolation: raise HTTPException(409, "Number already assigned")
        elif data.action == "number_remove":
            c.execute("UPDATE users SET virtual_number=NULL WHERE id=%s", (user["id"],))
        elif data.action == "number_generate":
            number = None
            for _ in range(30):
                candidate = "+888" + "".join(str(secrets.randbelow(10)) for _ in range(8))
                if not c.execute("SELECT 1 FROM users WHERE virtual_number=%s", (candidate,)).fetchone():
                    number = candidate
                    break
            if not number:
                raise HTTPException(503, "Could not allocate a virtual number")
            c.execute("UPDATE users SET virtual_number=%s WHERE id=%s", (number, user["id"]))
        result = c.execute("""SELECT virtual_id, verified, banned, stars, label,
            nft_username, nft_rarity, virtual_number FROM users WHERE id=%s""", (user["id"],)).fetchone()
    return {"ok": True, "user": result}
