import os
import secrets
import hashlib
import re
from datetime import datetime, timezone, timedelta
from html import escape as html_escape

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
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS otp_attempts INTEGER NOT NULL DEFAULT 0",
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
        for sql in [
            "ALTER TABLE gift_types ADD COLUMN IF NOT EXISTS collection TEXT NOT NULL DEFAULT 'FreeGram Originals'",
            "ALTER TABLE gift_types ADD COLUMN IF NOT EXISTS is_nft INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE gift_types ADD COLUMN IF NOT EXISTS background TEXT NOT NULL DEFAULT 'aurora'",
            "ALTER TABLE gift_types ADD COLUMN IF NOT EXISTS upgrade_cost BIGINT NOT NULL DEFAULT 0",
        ]:
            c.execute(sql)
        c.execute("""CREATE TABLE IF NOT EXISTS user_gifts (
            id BIGSERIAL PRIMARY KEY, gift_type_id BIGINT NOT NULL REFERENCES gift_types(id),
            owner_user_id BIGINT NOT NULL REFERENCES users(id), sender_user_id BIGINT REFERENCES users(id),
            chat_id BIGINT REFERENCES chats(id), created_at TEXT NOT NULL)""")
        for sql in [
            "ALTER TABLE user_gifts ADD COLUMN IF NOT EXISTS upgrade_level INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE user_gifts ADD COLUMN IF NOT EXISTS serial_no BIGINT",
        ]:
            c.execute(sql)
        c.execute("CREATE INDEX IF NOT EXISTS user_gifts_owner_idx ON user_gifts(owner_user_id,id DESC)")
        c.execute("""CREATE TABLE IF NOT EXISTS bot_outbox (
            id BIGSERIAL PRIMARY KEY, telegram_id BIGINT NOT NULL,
            virtual_number TEXT NOT NULL, code TEXT NOT NULL,
            created_at TEXT NOT NULL, sent_at TEXT)""")
        c.execute("CREATE INDEX IF NOT EXISTS bot_outbox_pending_idx ON bot_outbox(sent_at,id)")
        c.execute("""CREATE TABLE IF NOT EXISTS bot_notifications (
            id BIGSERIAL PRIMARY KEY, telegram_id BIGINT NOT NULL,
            message TEXT NOT NULL, created_at TEXT NOT NULL, sent_at TEXT)""")
        c.execute("CREATE INDEX IF NOT EXISTS bot_notifications_pending_idx ON bot_notifications(sent_at,id)")
        gifts = [
            ("rose","Роза","Классический цветочный подарок",15,"common","Everyday",0,"petal",5),
            ("heart","Сердце","Тёплый знак внимания",35,"common","Everyday",0,"ruby",10),
            ("crown","Корона","Золотой символ статуса",250,"rare","Royal",1,"gold",80),
            ("dragon","Дракон","Коллекционный хранитель",900,"epic","Mythic Beasts",1,"ember",180),
            ("pepe_frog","Pepe — Frog Mood","Лягушачий мем-персонаж в оригинальном стиле; не официальный Telegram collectible",1200,"epic","Pepe-inspired • FreeGram Originals",1,"swamp",240),
            ("redo_orbit","Redo — Orbit","Космический collectible из серии Redo",950,"epic","Redo • FreeGram Originals",1,"nebula",190),
            ("starfall","Звездопад","Созвездие в стеклянной сфере",420,"rare","Celestial",1,"night",100),
            ("moon_cat","Лунный кот","Котёнок на полумесяце",180,"rare","Dream Pets",1,"moon",60),
            ("pixel_fox","Пиксельная лиса","Ретро-питомец с сияющим хвостом",90,"common","Pixel World",1,"pixel",35),
            ("crystal_heart","Кристальное сердце","Гранёный рубиновый кристалл",650,"epic","Gems",1,"crystal",130),
            ("golden_duck","Золотая уточка","Коллекционная золотая фигурка",300,"rare","Toy Box",1,"sun",75),
            ("cloud_puppy","Облачный щенок","Мягкий небесный питомец",75,"common","Dream Pets",1,"sky",25),
            ("neon_skull","Неоновая маска","Светящаяся фантазийная маска",520,"epic","Neon Nights",1,"neon",110),
            ("sakura_box","Сакура","Цветущая сакура в подарочной коробке",110,"common","Botanical",1,"sakura",30),
            ("comet","Комета","Ледяной след кометы",800,"epic","Celestial",1,"comet",160),
            ("royal_ring","Королевское кольцо","Кольцо с сияющим камнем",1500,"legendary","Royal",1,"royal",300),
            ("jellyfish","Медуза","Светящаяся глубоководная медуза",240,"rare","Ocean",1,"ocean",65),
            ("volcano","Мини-вулкан","Сувенир с огненной лавой",360,"rare","Elements",1,"lava",90),
            ("paper_crane","Бумажный журавль","Оригами с золотой каймой",45,"common","Crafted",1,"paper",15),
            ("aurora_lamp","Северное сияние","Миниатюрная лампа с переливами",700,"epic","Celestial",1,"aurora",140),
            ("mystic_key","Мистический ключ","Ключ от неизвестной коллекции",1000,"legendary","Arcana",1,"mystic",250),
            ("red_rose_nft","Алая роза","Редкая роза в хрустальном куполе",560,"epic","Botanical",1,"ruby",120),
            ("robot_buddy","Робо-друг","Маленький дружелюбный робот",130,"rare","Pixel World",1,"steel",45),
            ("treasure_chest","Сундук сокровищ","Сундук с мерцающими монетами",1900,"legendary","Treasures",1,"treasure",380),
        ]
        for gift in gifts:
            c.execute("""INSERT INTO gift_types(code,title,description,price,rarity,collection,is_nft,background,upgrade_cost)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(code) DO NOTHING""", gift)

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
    virtual_id: str | None = None
    virtual_number: str | None = None
    code: str

class VirtualNumberRequest(BaseModel):
    virtual_number: str = Field(min_length=12, max_length=13)

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

class ChatMemberAdd(BaseModel):
    username: str = Field(min_length=5, max_length=24)

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
    code = "".join(str(secrets.randbelow(10)) for _ in range(6))
    salt = secrets.token_hex(16)
    now = datetime.now(timezone.utc)
    with connect() as c:
        existing = c.execute("SELECT id,virtual_id FROM users WHERE telegram_id=%s",
                             (data.telegram_id,)).fetchone()
        if existing:
            virtual_id = existing["virtual_id"]
            c.execute("""UPDATE users SET otp_salt=%s,otp_hash=%s,otp_expires=%s,
                otp_attempts=0,verified=0,token_hash=NULL WHERE id=%s""",
                (salt, digest(salt + code), (now + timedelta(minutes=10)).isoformat(),
                 existing["id"]))
        else:
            virtual_id = "FG-" + secrets.token_hex(5).upper()
            c.execute("""INSERT INTO users
                (telegram_id,virtual_id,otp_salt,otp_hash,otp_expires,otp_attempts,
                 verified,token_hash,created_at)
                VALUES (%s,%s,%s,%s,%s,0,0,NULL,%s)""",
                (data.telegram_id, virtual_id, salt, digest(salt + code),
                 (now + timedelta(minutes=10)).isoformat(), now.isoformat()))
    return {"ok": True, "virtual_id": virtual_id, "code": code, "expires_in": 600}

@app.post("/v1/auth/request-code")
def request_virtual_number_code(data: VirtualNumberRequest):
    number = data.virtual_number.strip()
    if not re.fullmatch(r"\+7\d{10}", number):
        raise HTTPException(400, "Введите виртуальный номер в формате +7XXXXXXXXXX")
    now = datetime.now(timezone.utc)
    code = "".join(str(secrets.randbelow(10)) for _ in range(6))
    salt = secrets.token_hex(16)
    with connect() as c:
        user = c.execute("""SELECT id, telegram_id, banned, otp_expires
            FROM users WHERE virtual_number=%s""", (number,)).fetchone()
        # Keep responses identical for unknown, unlinked and blocked numbers.
        if not user or user["telegram_id"] is None or user["banned"]:
            return {"ok": True, "message": "Если номер активен, код придёт в FreeGram Bot."}
        recent_request = c.execute("""SELECT 1 FROM bot_outbox
            WHERE virtual_number=%s AND created_at>%s LIMIT 1""",
            (number, (now - timedelta(seconds=60)).isoformat())).fetchone()
        if recent_request:
            return {"ok": True, "message": "Если номер активен, код придёт в FreeGram Bot."}
        c.execute("""UPDATE users SET otp_salt=%s, otp_hash=%s, otp_expires=%s,
            otp_attempts=0, verified=0, token_hash=NULL WHERE id=%s""",
            (salt, digest(salt + code), (now + timedelta(minutes=10)).isoformat(), user["id"]))
        c.execute("""INSERT INTO bot_outbox(telegram_id,virtual_number,code,created_at)
            VALUES(%s,%s,%s,%s)""",
            (user["telegram_id"], number, code, now.isoformat()))
    return {"ok": True, "message": "Если номер активен, код придёт в FreeGram Bot."}


@app.get("/v1/bot/notifications")
def bot_notifications(x_bot_secret: str = Header(default="")):
    require_bot(x_bot_secret)
    with connect() as c:
        rows = c.execute("""SELECT id,telegram_id,message FROM bot_notifications
            WHERE sent_at IS NULL ORDER BY id LIMIT 30""").fetchall()
    return {"ok": True, "items": rows}


@app.post("/v1/bot/notifications/{item_id}/sent")
def bot_notification_sent(item_id: int, x_bot_secret: str = Header(default="")):
    require_bot(x_bot_secret)
    with connect() as c:
        c.execute("UPDATE bot_notifications SET sent_at=%s WHERE id=%s AND sent_at IS NULL",
                  (datetime.now(timezone.utc).isoformat(), item_id))
    return {"ok": True}


@app.get("/v1/bot/outbox")
def bot_outbox(x_bot_secret: str = Header(default="")):
    require_bot(x_bot_secret)
    with connect() as c:
        rows = c.execute("""SELECT id,telegram_id,virtual_number,code
            FROM bot_outbox WHERE sent_at IS NULL AND created_at>%s
            ORDER BY id LIMIT 20""",
            ((datetime.now(timezone.utc)-timedelta(minutes=10)).isoformat(),)).fetchall()
    return {"ok": True, "items": rows}


@app.post("/v1/bot/outbox/{item_id}/sent")
def bot_outbox_sent(item_id: int, x_bot_secret: str = Header(default="")):
    require_bot(x_bot_secret)
    with connect() as c:
        c.execute("UPDATE bot_outbox SET sent_at=%s, code='' WHERE id=%s AND sent_at IS NULL",
                  (datetime.now(timezone.utc).isoformat(), item_id))
    return {"ok": True}


@app.post("/v1/auth/verify")
def verify(data: Verification):
    now = datetime.now(timezone.utc)
    failure = None
    token = None
    user = None
    with connect() as c:
        if data.virtual_number:
            if not re.fullmatch(r"\+7\d{10}", data.virtual_number.strip()):
                raise HTTPException(400, "Invalid virtual number")
            user = c.execute("SELECT * FROM users WHERE virtual_number=%s",
                             (data.virtual_number.strip(),)).fetchone()
        elif data.virtual_id:
            user = c.execute("SELECT * FROM users WHERE virtual_id=%s",
                             (data.virtual_id.upper(),)).fetchone()
        else:
            raise HTTPException(400, "Virtual number or ID required")
        if not user or not user["otp_hash"] or not user["otp_expires"] or datetime.fromisoformat(user["otp_expires"]) < now:
            failure = (400, "Invalid ID or expired code")
        elif int(user.get("otp_attempts") or 0) >= 5:
            failure = (429, "Too many code attempts. Request a new code.")
        elif not secrets.compare_digest(digest(user["otp_salt"] + data.code), user["otp_hash"]):
            c.execute("UPDATE users SET otp_attempts=otp_attempts+1 WHERE id=%s", (user["id"],))
            failure = (400, "Invalid ID or code")
        elif user["banned"]:
            failure = (403, "Account banned")
        else:
            token = secrets.token_urlsafe(32)
            c.execute("""UPDATE users SET verified=1, otp_hash=NULL, otp_salt=NULL,
                otp_expires=NULL, otp_attempts=0, token_hash=%s WHERE id=%s""",
                (digest(token), user["id"]))
    if failure:
        raise HTTPException(failure[0], failure[1])
    return {"ok": True, "access_token": token, "token_type": "Bearer",
            "virtual_id": user["virtual_id"], "virtual_number": user["virtual_number"]}

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

@app.get("/v1/chats/search")
def search_public_chats(q: str, authorization: str = Header(default="")):
    authenticated_user(authorization)
    query = q.strip()
    if len(query) < 2 or len(query) > 60:
        raise HTTPException(400, "Search query must contain 2-60 characters")
    with connect() as c:
        rows = c.execute("""SELECT ch.id,ch.kind,ch.title,ch.description,
            (SELECT COUNT(*) FROM chat_members cm WHERE cm.chat_id=ch.id) AS member_count
            FROM chats ch WHERE ch.kind IN ('group','channel')
            AND (LOWER(ch.title) LIKE LOWER(%s) OR LOWER(ch.description) LIKE LOWER(%s))
            ORDER BY ch.id DESC LIMIT 30""", (f"%{query}%",f"%{query}%")).fetchall()
    return {"ok": True, "chats": rows}


@app.post("/v1/chats/{chat_id}/join")
def join_public_chat(chat_id: int, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    now = datetime.now(timezone.utc).isoformat()
    with connect() as c:
        chat = c.execute("SELECT id,kind FROM chats WHERE id=%s", (chat_id,)).fetchone()
        if not chat or chat["kind"] not in ("group","channel"):
            raise HTTPException(404, "Public group or channel not found")
        c.execute("""INSERT INTO chat_members(chat_id,user_id,joined_at)
            VALUES(%s,%s,%s) ON CONFLICT(chat_id,user_id) DO NOTHING""",
            (chat_id,user["id"],now))
    return {"ok": True, "chat_id": chat_id, "kind": chat["kind"]}


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
            verified,label,nft_username,nft_rarity,virtual_number,stars FROM users WHERE id=%s""",
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
            verified,label,nft_username,nft_rarity,virtual_number
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
            verified,label,nft_username,nft_rarity,virtual_number
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
            u.verified,u.label,u.nft_username,u.nft_rarity
            FROM chat_members cm JOIN users u ON u.id=cm.user_id
            WHERE cm.chat_id=%s ORDER BY cm.joined_at LIMIT 200""", (chat_id,)).fetchall()
    return {"ok": True, "chat": chat, "members": members,
            "is_owner": chat["owner_user_id"] == user["id"]}


@app.post("/v1/chats/{chat_id}/members")
def add_chat_member(chat_id: int, data: ChatMemberAdd, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    username = data.username.strip().lstrip("@").lower()
    now = datetime.now(timezone.utc).isoformat()
    with connect() as c:
        chat = c.execute("SELECT id,owner_user_id,kind FROM chats WHERE id=%s", (chat_id,)).fetchone()
        if not chat or chat["owner_user_id"] != user["id"]:
            raise HTTPException(403, "Only chat owner can add members")
        member = c.execute("""SELECT id,username FROM users
            WHERE LOWER(username)=LOWER(%s) AND verified=1 AND banned=0""", (username,)).fetchone()
        if not member:
            raise HTTPException(404, "User not found")
        c.execute("""INSERT INTO chat_members(chat_id,user_id,joined_at)
            VALUES(%s,%s,%s) ON CONFLICT(chat_id,user_id) DO NOTHING""",
            (chat_id,member["id"],now))
    return {"ok": True, "username": member["username"]}


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
        if chat["kind"] == "direct":
            raise HTTPException(400, "Invite links are available for groups and channels only")
        code = secrets.token_urlsafe(9)
        c.execute("UPDATE chats SET invite_code=%s WHERE id=%s", (code, chat_id))
    return {"ok": True, "invite_code": code, "invite_link": "https://freegram-x.onrender.com/i/" + code}


@app.post("/v1/invites/{code}/join")
def join_by_invite(code: str, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    now = datetime.now(timezone.utc).isoformat()
    with connect() as c:
        chat = c.execute("SELECT id,kind FROM chats WHERE invite_code=%s", (code,)).fetchone()
        if not chat or chat["kind"] not in ("group", "channel"):
            raise HTTPException(404, "Invite link is invalid or revoked")
        c.execute("""INSERT INTO chat_members(chat_id,user_id,joined_at)
            VALUES(%s,%s,%s) ON CONFLICT(chat_id,user_id) DO NOTHING""",
            (chat["id"], user["id"], now))
    return {"ok": True, "chat_id": chat["id"], "kind": chat["kind"]}


@app.get("/v1/shop/gifts")
def gift_catalog(authorization: str = Header(default="")):
    authenticated_user(authorization)
    with connect() as c:
        rows = c.execute("""SELECT code,title,description,price,rarity,collection,is_nft,
            background,upgrade_cost,
            '/v1/shop/gifts/' || code || '/art.svg' AS image_url
            FROM gift_types WHERE active=1 ORDER BY collection,price,id""").fetchall()
    return {"ok": True, "gifts": rows, "count": len(rows)}


@app.get("/v1/shop/gifts/{code}/art.svg")
def gift_art(code: str):
    with connect() as c:
        gift = c.execute("SELECT title,rarity,background FROM gift_types WHERE code=%s AND active=1",
                         (code.lower(),)).fetchone()
    if not gift:
        raise HTTPException(404, "Gift not found")
    icons = {
        "rose":"🌹","heart":"❤️","crown":"👑","dragon":"🐉","pepe_frog":"🐸",
        "redo_orbit":"🪐","starfall":"🌠","moon_cat":"🐈","pixel_fox":"🦊",
        "crystal_heart":"💎","golden_duck":"🦆","cloud_puppy":"🐶","neon_skull":"🎭",
        "sakura_box":"🌸","comet":"☄️","royal_ring":"💍","jellyfish":"🪼",
        "volcano":"🌋","paper_crane":"🕊️","aurora_lamp":"🌌","mystic_key":"🗝️",
        "red_rose_nft":"🥀","robot_buddy":"🤖","treasure_chest":"🧰"
    }
    icon=icons.get(code.lower(),"✦")
    palettes = {
        "petal":("#f8b4c8","#8f315e"),"ruby":("#ff5b76","#5d1539"),
        "gold":("#ffe08a","#9a5c0b"),"ember":("#ff9b54","#6f183d"),
        "swamp":("#b5d77a","#245b47"),"nebula":("#b6a4ff","#38276f"),
        "night":("#9ab9ff","#182c68"),"moon":("#c8c5ff","#45418a"),
        "pixel":("#8ff0bd","#246c66"),"crystal":("#ff9ed6","#663b9b"),
        "sun":("#ffe28b","#a75b19"),"sky":("#b6e9ff","#3577ad"),
        "neon":("#ff8cdb","#38206f"),"sakura":("#ffc3d7","#9b476e"),
        "comet":("#b8f4ff","#31548d"),"royal":("#ffe8a5","#60418d"),
        "ocean":("#8ef0e6","#155a83"),"lava":("#ffc16c","#8b2929"),
        "paper":("#fff0c4","#8b6b45"),"aurora":("#a8f0d0","#3654a0"),
        "mystic":("#d4b7ff","#34265e"),"steel":("#b8d5e8","#3c536c"),
        "treasure":("#ffdc7a","#75421a")
    }
    a,b=palettes.get(gift["background"],("#b8c7ff","#35456f"))
    title=html_escape(gift["title"])
    rarity=html_escape(gift["rarity"].upper())
    svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="640" height="640" viewBox="0 0 640 640">
    <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop stop-color="{a}"/><stop offset="1" stop-color="{b}"/></linearGradient>
    <radialGradient id="r"><stop stop-color="#fff" stop-opacity=".75"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>
    <rect width="640" height="640" rx="72" fill="url(#g)"/><circle cx="320" cy="270" r="210" fill="url(#r)"/>
    <circle cx="320" cy="270" r="142" fill="#ffffff" fill-opacity=".16" stroke="#fff" stroke-opacity=".65" stroke-width="5"/>
    <text x="320" y="345" text-anchor="middle" font-family="sans-serif" font-size="178">{icon}</text>
    <text x="320" y="510" text-anchor="middle" font-family="sans-serif" font-size="34" font-weight="700" fill="#fff">{title}</text>
    <text x="320" y="560" text-anchor="middle" font-family="sans-serif" font-size="22" letter-spacing="5" fill="#fff" fill-opacity=".85">{rarity}</text></svg>'''
    from fastapi.responses import Response
    return Response(content=svg, media_type="image/svg+xml", headers={"Cache-Control":"public, max-age=86400"})


@app.get("/v1/shop/my-gifts")
def my_gifts(authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    with connect() as c:
        rows = c.execute("""SELECT ug.id,gt.code,gt.title,gt.description,gt.rarity,
            gt.collection,gt.is_nft,gt.background,ug.upgrade_level,
            CASE ug.upgrade_level WHEN 1 THEN 'rare' WHEN 2 THEN 'epic'
                 WHEN 3 THEN 'legendary' ELSE gt.rarity END AS rarity,
            COALESCE(ug.serial_no,ug.id) AS serial_no,
            sender.username AS sender_username,ug.created_at,
            '/v1/shop/gifts/' || gt.code || '/art.svg?level=' || ug.upgrade_level AS image_url
            FROM user_gifts ug JOIN gift_types gt ON gt.id=ug.gift_type_id
            LEFT JOIN users sender ON sender.id=ug.sender_user_id
            WHERE ug.owner_user_id=%s ORDER BY ug.id DESC LIMIT 200""",
            (user["id"],)).fetchall()
    return {"ok": True, "gifts": rows}


@app.post("/v1/shop/my-gifts/{gift_id}/upgrade")
def upgrade_gift(gift_id: int, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    with connect() as c:
        gift = c.execute("""SELECT ug.id,ug.upgrade_level,gt.upgrade_cost,gt.rarity,gt.title
            FROM user_gifts ug JOIN gift_types gt ON gt.id=ug.gift_type_id
            WHERE ug.id=%s AND ug.owner_user_id=%s FOR UPDATE OF ug""",
            (gift_id,user["id"])).fetchone()
        if not gift:
            raise HTTPException(404,"Gift not found in your collection")
        levels=["common","rare","epic","legendary"]
        current=min(int(gift["upgrade_level"]),3)
        if current>=3:
            raise HTTPException(400,"Gift is already at maximum level")
        cost=int(gift["upgrade_cost"] or 0) * (current+1)
        balance=c.execute("SELECT stars FROM users WHERE id=%s FOR UPDATE",(user["id"],)).fetchone()["stars"]
        if balance<cost:
            raise HTTPException(400,"Not enough Stars")
        c.execute("UPDATE users SET stars=stars-%s WHERE id=%s",(cost,user["id"]))
        level=current+1
        c.execute("UPDATE user_gifts SET upgrade_level=%s WHERE id=%s",(level,gift_id))
    return {"ok":True,"gift_id":gift_id,"upgrade_level":level,
            "rarity":levels[level],"spent_stars":cost,"remaining_stars":balance-cost}


@app.post("/v1/shop/gifts/{code}/buy")
def buy_gift_for_self(code: str, authorization: str = Header(default="")):
    user = authenticated_user(authorization)
    now = datetime.now(timezone.utc).isoformat()
    with connect() as c:
        gift = c.execute("""SELECT id,price,title FROM gift_types
            WHERE code=%s AND active=1 FOR SHARE""", (code.lower(),)).fetchone()
        if not gift:
            raise HTTPException(404, "Gift not found")
        balance = c.execute("SELECT stars FROM users WHERE id=%s FOR UPDATE",
                            (user["id"],)).fetchone()["stars"]
        price = int(gift["price"])
        if balance < price:
            raise HTTPException(400, "Not enough Stars")
        c.execute("UPDATE users SET stars=stars-%s WHERE id=%s", (price,user["id"]))
        cur = c.execute("""INSERT INTO user_gifts
            (gift_type_id,owner_user_id,sender_user_id,created_at)
            VALUES(%s,%s,%s,%s) RETURNING id""",
            (gift["id"],user["id"],user["id"],now))
        gift_id = cur.fetchone()["id"]
        c.execute("UPDATE user_gifts SET serial_no=%s WHERE id=%s", (gift_id,gift_id))
    return {"ok":True,"gift_id":gift_id,"title":gift["title"],
            "spent_stars":price,"remaining_stars":balance-price}


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
        user = c.execute("SELECT id, stars, telegram_id FROM users WHERE virtual_id=%s", (data.virtual_id.upper(),)).fetchone()
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
            if user["telegram_id"] is None:
                raise HTTPException(400, "User must start FreeGram Bot and register with /register before receiving a +7 number")
            number = str(data.value or "").strip()
            valid_plus7 = bool(re.fullmatch(r"\+7\d{10}", number))
            valid_legacy = bool(re.fullmatch(r"\+888\d{8,10}", number))
            if not (valid_plus7 or valid_legacy):
                raise HTTPException(400, "Number must be +7 followed by 10 digits")
            try: c.execute("UPDATE users SET virtual_number=%s WHERE id=%s", (number, user["id"]))
            except psycopg.errors.UniqueViolation: raise HTTPException(409, "Number already assigned")
            c.execute("INSERT INTO bot_notifications(telegram_id,message,created_at) VALUES(%s,%s,%s)",
                (user["telegram_id"], "Тебе выдан виртуальный номер FreeGram: " + number +
                 "\nОткрой приложение FreeGram и войди по этому номеру. Код подтверждения придёт сюда.",
                 datetime.now(timezone.utc).isoformat()))
        elif data.action == "number_remove":
            c.execute("UPDATE users SET virtual_number=NULL WHERE id=%s", (user["id"],))
        elif data.action == "number_generate":
            if user["telegram_id"] is None:
                raise HTTPException(400, "User must start FreeGram Bot and register with /register before receiving a +7 number")
            number = None
            for _ in range(30):
                candidate = "+7" + "".join(str(secrets.randbelow(10)) for _ in range(10))
                if not c.execute("SELECT 1 FROM users WHERE virtual_number=%s", (candidate,)).fetchone():
                    number = candidate
                    break
            if not number:
                raise HTTPException(503, "Could not allocate a virtual number")
            c.execute("UPDATE users SET virtual_number=%s WHERE id=%s", (number, user["id"]))
            c.execute("INSERT INTO bot_notifications(telegram_id,message,created_at) VALUES(%s,%s,%s)",
                (user["telegram_id"], "Тебе выдан виртуальный номер FreeGram: " + number +
                 "\nОткрой приложение FreeGram и войди по этому номеру. Код подтверждения придёт сюда.",
                 datetime.now(timezone.utc).isoformat()))
        result = c.execute("""SELECT virtual_id, verified, banned, stars, label,
            nft_username, nft_rarity, virtual_number FROM users WHERE id=%s""", (user["id"],)).fetchone()
    return {"ok": True, "user": result}
