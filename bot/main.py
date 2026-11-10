import os
import asyncio
import sqlite3
import secrets
import hashlib
import logging
from datetime import datetime, timezone

from telegram import InlineKeyboardButton, InlineKeyboardMarkup, Update
from telegram.ext import Application, CommandHandler, CallbackQueryHandler, ContextTypes

BOT_TOKEN = os.environ["BOT_TOKEN"]
OWNER_TELEGRAM_ID = int(os.environ["OWNER_TELEGRAM_ID"])
DB_PATH = os.getenv("FREEGRAM_BOT_DB", "freegram_bot.sqlite3")

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("freegram-bot")


def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with db() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS registrations (
                telegram_id INTEGER PRIMARY KEY,
                virtual_number TEXT UNIQUE NOT NULL,
                otp_hash TEXT NOT NULL,
                created_at TEXT NOT NULL,
                verified INTEGER NOT NULL DEFAULT 0
            )
        """)


def new_registration(telegram_id: int):
    # In-app identifier only; this is not a telephone number.
    number = "+132" + "".join(str(secrets.randbelow(10)) for _ in range(8))
    otp = "".join(str(secrets.randbelow(10)) for _ in range(6))
    salt = secrets.token_hex(16)
    digest = hashlib.sha256((salt + otp).encode()).hexdigest()
    with db() as conn:
        conn.execute(
            "INSERT OR REPLACE INTO registrations "
            "(telegram_id, virtual_number, otp_hash, created_at, verified) VALUES (?, ?, ?, ?, 0)",
            (telegram_id, number, salt + ":" + digest, datetime.now(timezone.utc).isoformat())
        )
    return number, otp


def owner_keyboard():
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("📊 Статус бота", callback_data="owner_status")],
        [InlineKeyboardButton("ℹ️ Команды владельца", callback_data="owner_help")]
    ])


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    keyboard = InlineKeyboardMarkup([
        [InlineKeyboardButton("🆔 Получить FreeGram ID", callback_data="get_id")]
    ])
    await update.message.reply_text(
        "Добро пожаловать в FreeGram!\n\n"
        "Бот выдаёт виртуальный ID для будущей регистрации в приложении. "
        "Это не номер телефона и он не принимает SMS.",
        reply_markup=keyboard
    )


async def owner(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    if not user or user.id != OWNER_TELEGRAM_ID:
        await update.message.reply_text("⛔ Нет доступа.")
        return
    await update.message.reply_text("🔐 Owner Panel", reply_markup=owner_keyboard())


async def on_button(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    user = query.from_user

    if query.data == "get_id":
        number, otp = new_registration(user.id)
        await query.edit_message_text(
            "✅ Тестовая регистрация FreeGram\n\n"
            f"Виртуальный ID: {number}\n"
            f"Одноразовый код: {otp}\n\n"
            "Приложение пока не подключено к серверу авторизации. "
            "Этот код демонстрационный и ещё не входит в FreeGram."
        )
        return

    if user.id != OWNER_TELEGRAM_ID:
        await query.edit_message_text("⛔ Нет доступа.")
        return

    if query.data == "owner_status":
        with db() as conn:
            count = conn.execute("SELECT COUNT(*) AS n FROM registrations").fetchone()["n"]
        await query.edit_message_text(
            f"🔐 Owner Panel\nТестовых заявок: {count}",
            reply_markup=owner_keyboard()
        )
    elif query.data == "owner_help":
        await query.edit_message_text(
            "Команды:\n/owner — открыть панель владельца\n\n"
            "Stars, метки, NFT-юзернеймы, верификацию и блокировки "
            "подключим после создания общей базы/API FreeGram.",
            reply_markup=owner_keyboard()
        )


def main():
    init_db()
    app = Application.builder().token(BOT_TOKEN).build()
    app.add_handler(CommandHandler("start", start))
    app.add_handler(CommandHandler("owner", owner))
    app.add_handler(CallbackQueryHandler(on_button))
    log.info("FreeGram bot starting")
    async def run():
        await app.initialize()
        await app.start()
        await app.updater.start_polling(allowed_updates=Update.ALL_TYPES)
        print("FreeGram Bot запущен!")
        try:
            await asyncio.Event().wait()
        finally:
            await app.updater.stop()
            await app.stop()
            await app.shutdown()
    asyncio.run(run())


if __name__ == "__main__":
    main()
