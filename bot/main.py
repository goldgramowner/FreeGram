import os
import json
import asyncio
import logging
import urllib.request
import urllib.error

from telegram import InlineKeyboardButton, InlineKeyboardMarkup, Update
from telegram.ext import (
    Application, CommandHandler, CallbackQueryHandler, ContextTypes
)

BOT_TOKEN = os.environ["BOT_TOKEN"]
OWNER_TELEGRAM_ID = int(os.environ["OWNER_TELEGRAM_ID"])
AUTH_API_URL = os.environ["AUTH_API_URL"].rstrip("/")
BOT_API_SECRET = os.environ["BOT_API_SECRET"]
OWNER_API_SECRET = os.environ["OWNER_API_SECRET"]

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("freegram-bot")


def api_request(path, method="GET", data=None, secret_name=None):
    headers = {"Content-Type": "application/json"}
    if secret_name == "bot":
        headers["X-Bot-Secret"] = BOT_API_SECRET
    elif secret_name == "owner":
        headers["X-Owner-Secret"] = OWNER_API_SECRET

    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(
        AUTH_API_URL + path,
        data=body,
        headers=headers,
        method=method
    )

    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode()
        except Exception:
            detail = str(e)
        raise RuntimeError(f"API HTTP {e.code}: {detail}")
    except Exception as e:
        raise RuntimeError(f"API connection error: {e}")


def main_keyboard():
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("🆔 Получить FreeGram ID", callback_data="get_id")]
    ])


def owner_keyboard():
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("📊 Статус FreeGram", callback_data="owner_status")],
        [InlineKeyboardButton("ℹ️ Команды", callback_data="owner_help")]
    ])


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "Добро пожаловать в FreeGram!\n\n"
        "Здесь можно получить ID для регистрации в приложении.",
        reply_markup=main_keyboard()
    )


async def owner(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    if not user or user.id != OWNER_TELEGRAM_ID:
        await update.message.reply_text("⛔ Нет доступа.")
        return

    await update.message.reply_text(
        "🔐 FreeGram Owner Panel",
        reply_markup=owner_keyboard()
    )


async def on_button(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    user = query.from_user

    if query.data == "get_id":
        await query.edit_message_text("⏳ Запрашиваю регистрацию у FreeGram API...")
        try:
            result = await asyncio.to_thread(
                api_request,
                "/v1/registrations",
                "POST",
                {"telegram_id": user.id},
                "bot"
            )
            await query.edit_message_text(
                "✅ Регистрация FreeGram создана!\n\n"
                f"🆔 FreeGram ID: {result['virtual_id']}\n"
                f"🔐 Одноразовый код: {result['code']}\n\n"
                "Код действует 10 минут.\n"
                "Используй его для входа после подключения регистрации в приложении."
            )
        except Exception as e:
            log.exception("Registration API error")
            await query.edit_message_text(
                "❌ Не удалось создать регистрацию.\n"
                "Сервер временно недоступен. Попробуй позже."
            )
        return

    if user.id != OWNER_TELEGRAM_ID:
        await query.edit_message_text("⛔ Нет доступа.")
        return

    if query.data == "owner_status":
        try:
            result = await asyncio.to_thread(
                api_request, "/v1/owner/status", "GET", None, "owner"
            )
            await query.edit_message_text(
                "🔐 FreeGram Owner Panel\n\n"
                f"👥 Всего аккаунтов: {result['total_users']}\n"
                f"✅ Подтверждено: {result['verified_users']}",
                reply_markup=owner_keyboard()
            )
        except Exception:
            log.exception("Owner status error")
            await query.edit_message_text(
                "❌ Не удалось получить статус API.",
                reply_markup=owner_keyboard()
            )

    elif query.data == "owner_help":
        await query.edit_message_text(
            "🔐 Команды владельца:\n\n"
            "/owner — открыть Owner Panel\n"
            "/start — главное меню\n\n"
            "Проверка аккаунтов уже подключена к API. "
            "Управление метками, Stars и номерами добавим следующим этапом.",
            reply_markup=owner_keyboard()
        )


def main():
    app = Application.builder().token(BOT_TOKEN).build()
    app.add_handler(CommandHandler("start", start))
    app.add_handler(CommandHandler("owner", owner))
    app.add_handler(CallbackQueryHandler(on_button))
    log.info("FreeGram Auth Bot starting")
    app.run_polling()


if __name__ == "__main__":
    main()
