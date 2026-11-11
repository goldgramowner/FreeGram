import os
import json
import asyncio
import logging
import urllib.request
import urllib.error
import threading

import uvicorn
from fastapi import FastAPI
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
health_app = FastAPI()


@health_app.get("/")
@health_app.get("/health")
async def health():
    return {"project": "FreeGram Bot", "status": "online"}


def run_http_server():
    uvicorn.run(health_app, host="0.0.0.0",
                port=int(os.environ.get("PORT", "10000")), log_level="info")


def api_request(path, method="GET", data=None, secret_name=None):
    headers = {"Content-Type": "application/json"}
    if secret_name == "bot":
        headers["X-Bot-Secret"] = BOT_API_SECRET
    elif secret_name == "owner":
        headers["X-Owner-Secret"] = OWNER_API_SECRET
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(AUTH_API_URL + path, data=body,
                                 headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"API HTTP {e.code}: {e.read().decode()}")
    except Exception as e:
        raise RuntimeError(f"API connection error: {e}")


def owner_keyboard():
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("📊 Статистика", callback_data="owner_status"),
         InlineKeyboardButton("👥 Аккаунты", callback_data="owner_users")],
        [InlineKeyboardButton("📖 Команды управления", callback_data="owner_help")]
    ])


def is_owner(update):
    user = update.effective_user
    return bool(user and user.id == OWNER_TELEGRAM_ID)


async def owner_only(update):
    if not is_owner(update):
        if update.message:
            await update.message.reply_text("⛔ Нет доступа.")
        elif update.callback_query:
            await update.callback_query.edit_message_text("⛔ Нет доступа.")
        return False
    return True


async def owner_action(virtual_id, action, value=None):
    return await asyncio.to_thread(
        api_request, "/v1/owner/action", "POST",
        {"virtual_id": virtual_id, "action": action, "value": value}, "owner"
    )


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if is_owner(update):
        await update.message.reply_text(
            "🔐 FreeGram Owner Panel",
            reply_markup=owner_keyboard())
    else:
        await update.message.reply_text(
            "FreeGram Bot\\nДля регистрации: /register. Для повторного кода входа: /login FG-XXXXXXXXXX.")


async def register_user(update: Update, context: ContextTypes.DEFAULT_TYPE):
    telegram_user = update.effective_user
    try:
        result = await asyncio.to_thread(
            api_request, "/v1/registrations", "POST",
            {"telegram_id": telegram_user.id}, "bot"
        )
        await update.message.reply_text(
            "Аккаунт FreeGram готов.\\n"
            f'FreeGram ID: {result["virtual_id"]}\\n'
            f'Код входа: {result["code"]}\\n'
            "Код действует 10 минут. Введи ID и код в приложении."
        )
    except Exception:
        log.exception("FreeGram registration failed")
        await update.message.reply_text("Не удалось создать аккаунт. Попробуй позже.")


async def login_code(update: Update, context: ContextTypes.DEFAULT_TYPE):
    telegram_user = update.effective_user
    if not context.args:
        await update.message.reply_text("Формат: /login FG-XXXXXXXXXX")
        return
    virtual_id = context.args[0].strip().upper()
    if not (virtual_id.startswith("FG-") and len(virtual_id) == 13
            and virtual_id[3:].isalnum()):
        await update.message.reply_text("Проверь формат FreeGram ID.")
        return
    try:
        result = await asyncio.to_thread(
            api_request, "/v1/auth/code", "POST",
            {"virtual_id": virtual_id, "telegram_id": telegram_user.id}, "bot"
        )
        await update.message.reply_text(
            f'Код входа в FreeGram: {result["code"]}\\n'
            "Код действует 10 минут. Никому его не пересылай."
        )
    except Exception as e:
        log.exception("FreeGram login-code request failed")
        await update.message.reply_text(f"Не удалось выдать код: {str(e)[:180]}")


async def owner(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not await owner_only(update):
        return
    await update.message.reply_text("🔐 FreeGram Owner Panel",
                                    reply_markup=owner_keyboard())


async def users(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not await owner_only(update):
        return
    try:
        result = await asyncio.to_thread(api_request, "/v1/owner/users",
                                         "GET", None, "owner")
        rows = result.get("users", [])
        if not rows:
            await update.message.reply_text("Пока нет аккаунтов.")
            return
        lines = ["👥 Последние аккаунты FreeGram:"]
        for u in rows[:30]:
            state = "🚫 бан" if u["banned"] else ("✅" if u["verified"] else "⏳")
            lines.append(
                f'{state} {u["virtual_id"]} | ⭐ {u["stars"]} | '
                f'метка: {u["label"] or "—"}'
            )
        await update.message.reply_text("\\n".join(lines),
                                         reply_markup=owner_keyboard())
    except Exception:
        log.exception("Owner users error")
        await update.message.reply_text("❌ Не удалось загрузить аккаунты API.")


async def owner_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not await owner_only(update):
        return
    command = update.message.text.split()[0].split("@")[0].lstrip("/").lower()
    args = context.args
    if command in ("ban", "unban", "verify", "unverify", "nft_remove", "number_remove", "number_generate"):
        if len(args) != 1:
            await update.message.reply_text(f"Формат: /{command} FG-XXXXXXXXXX")
            return
        action, value = command, None
    elif command in ("stars_add", "stars_remove", "stars_set"):
        if len(args) != 2 or not args[1].isdigit():
            await update.message.reply_text(f"Формат: /{command} FG-XXXXXXXXXX число")
            return
        action, value = command, int(args[1])
    elif command in ("label", "nft_set", "number_set"):
        if len(args) < 2:
            await update.message.reply_text(f"Формат: /{command} FG-XXXXXXXXXX значение")
            return
        if command == "nft_set" and len(args) >= 3:
            value = args[1] + "|" + args[2]
        else:
            value = " ".join(args[1:])
        action = command
    else:
        return
    try:
        result = await owner_action(args[0], action, value)
        u = result["user"]
        await update.message.reply_text(
            "✅ Изменения сохранены в общей БД FreeGram.\\n"
            f'ID: {u["virtual_id"]}\\n'
            f'Статус: {"заблокирован" if u["banned"] else "активен"}\\n'
            f'Проверен: {"да" if u["verified"] else "нет"}\\n'
            f'Stars: {u["stars"]}\\n'
            f'Метка: {u["label"] or "—"}\\n'
            f'NFT: {u["nft_username"] or "—"} ({u.get("nft_rarity", "common")})\\n'
            f'Номер: {u["virtual_number"] or "—"}'
        )
    except Exception as e:
        log.exception("Owner action failed")
        await update.message.reply_text(f"❌ Не выполнено: {str(e)[:300]}")


async def on_button(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    user = query.from_user
    if user.id != OWNER_TELEGRAM_ID:
        await query.edit_message_text("⛔ Нет доступа.")
        return
    if query.data == "owner_status":
        try:
            result = await asyncio.to_thread(api_request, "/v1/owner/status",
                                             "GET", None, "owner")
            await query.edit_message_text(
                "🔐 FreeGram Owner Panel\\n\\n"
                f'👥 Аккаунтов: {result["total_users"]}\\n'
                f'✅ Подтверждено: {result["verified_users"]}\\n'
                f'🚫 Заблокировано: {result["banned_users"]}',
                reply_markup=owner_keyboard())
        except Exception:
            log.exception("Owner status error")
            await query.edit_message_text("❌ Ошибка получения статистики.",
                                           reply_markup=owner_keyboard())
    elif query.data == "owner_users":
        try:
            result = await asyncio.to_thread(api_request, "/v1/owner/users",
                                             "GET", None, "owner")
            rows = result.get("users", [])
            lines = ["👥 Аккаунты (последние 20):"]
            for u in rows[:20]:
                state = "🚫" if u["banned"] else ("✅" if u["verified"] else "⏳")
                lines.append(f'{state} {u["virtual_id"]} | ⭐ {u["stars"]} | {u["label"] or "—"}')
            await query.edit_message_text("\\n".join(lines) if rows else "Аккаунтов пока нет.",
                                           reply_markup=owner_keyboard())
        except Exception:
            log.exception("Owner users callback error")
            await query.edit_message_text("❌ Не удалось получить список.",
                                           reply_markup=owner_keyboard())
    elif query.data == "owner_help":
        await query.edit_message_text(
            "🔐 Управление аккаунтами (только владелец):\\n\\n"
            "/users — список аккаунтов\\n"
            "/ban ID, /unban ID — блокировка\\n"
            "/verify ID, /unverify ID — проверка\\n"
            "/stars_add ID число — добавить Stars\\n"
            "/stars_remove ID число — снять Stars\\n"
            "/stars_set ID число — установить баланс\\n"
            "/label ID текст — назначить метку\\n"
            "/nft_set ID имя [rarity] — назначить NFT username\\n"
            "/nft_remove ID — снять NFT username\\n"
            "/number_set ID +888... — назначить номер\\n"
            "/number_generate ID — создать и назначить новый +888 номер\\n"
            "/number_remove ID — снять номер\\n\\n"
            "Все изменения идут в общую PostgreSQL БД API.",
            reply_markup=owner_keyboard())


def main():
    telegram_app = Application.builder().token(BOT_TOKEN).build()
    telegram_app.add_handler(CommandHandler("start", start))
    telegram_app.add_handler(CommandHandler("register", register_user))
    telegram_app.add_handler(CommandHandler("login", login_code))
    telegram_app.add_handler(CommandHandler("owner", owner))
    telegram_app.add_handler(CommandHandler("users", users))
    commands = [
        "ban", "unban", "verify", "unverify", "stars_add", "stars_remove",
        "stars_set", "label", "nft_set", "nft_remove", "number_set", "number_remove",
        "number_generate"
    ]
    for name in commands:
        telegram_app.add_handler(CommandHandler(name, owner_command))
    telegram_app.add_handler(CallbackQueryHandler(on_button))
    threading.Thread(target=run_http_server, daemon=True).start()
    log.info("FreeGram Bot and HTTP server starting")
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    telegram_app.run_polling()


if __name__ == "__main__":
    main()
