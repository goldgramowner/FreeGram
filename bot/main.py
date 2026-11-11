import os
import sys
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

# Only the Telegram token is required to start the bot. API/owner settings are
# checked when their corresponding commands are used, so a missing API secret
# cannot make the whole Telegram bot silently disappear.
BOT_TOKEN = os.environ.get("BOT_TOKEN", "").strip()
OWNER_TELEGRAM_ID = int(os.environ.get("OWNER_TELEGRAM_ID", "0") or "0")
AUTH_API_URL = os.environ.get("AUTH_API_URL", "").strip().rstrip("/")
BOT_API_SECRET = os.environ.get("BOT_API_SECRET", "").strip()
OWNER_API_SECRET = os.environ.get("OWNER_API_SECRET", "").strip()

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("freegram-bot")
health_app = FastAPI()
bot_state = {"status": "starting", "polling": False}


@health_app.get("/")
@health_app.get("/health")
async def health():
    return {
        "project": "FreeGram Bot",
        "status": bot_state["status"],
        "polling": bot_state["polling"],
    }


def run_http_server():
    port = int(os.environ.get("PORT", "10000"))
    try:
        uvicorn.run(
            health_app, host="0.0.0.0", port=port,
            log_level="warning", access_log=False,
        )
    except Exception:
        log.exception("Health HTTP server failed")


def api_request(path, method="GET", data=None, secret_name=None):
    if not AUTH_API_URL:
        raise RuntimeError("Не задана переменная AUTH_API_URL в Render.")
    headers = {"Content-Type": "application/json"}
    if secret_name == "bot":
        if not BOT_API_SECRET:
            raise RuntimeError("Не задан BOT_API_SECRET в Render.")
        headers["X-Bot-Secret"] = BOT_API_SECRET
    elif secret_name == "owner":
        if not OWNER_API_SECRET:
            raise RuntimeError("Не задан OWNER_API_SECRET в Render.")
        headers["X-Owner-Secret"] = OWNER_API_SECRET

    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(
        AUTH_API_URL + path, data=body, headers=headers, method=method
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"API HTTP {exc.code}: {detail[:400]}") from exc
    except Exception as exc:
        raise RuntimeError(f"API connection error: {exc}") from exc


def main_keyboard():
    return InlineKeyboardMarkup([[
        InlineKeyboardButton("🆔 Получить FreeGram ID", callback_data="get_id")
    ]])


def owner_keyboard():
    return InlineKeyboardMarkup([
        [
            InlineKeyboardButton("📊 Статистика", callback_data="owner_status"),
            InlineKeyboardButton("👥 Аккаунты", callback_data="owner_users"),
        ],
        [InlineKeyboardButton("📖 Команды управления", callback_data="owner_help")],
    ])


def is_owner(update):
    user = update.effective_user
    return bool(user and OWNER_TELEGRAM_ID and user.id == OWNER_TELEGRAM_ID)


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
            "🔐 FreeGram Owner Panel\nВы вошли как владелец.",
            reply_markup=owner_keyboard(),
        )
    else:
        await update.message.reply_text(
            "Добро пожаловать в FreeGram!\n\n"
            "Нажми кнопку, чтобы получить ID и код входа в приложение.",
            reply_markup=main_keyboard(),
        )


async def ping(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text("🟢 Бот отвечает. FreeGram Bot is online.")


async def owner(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not await owner_only(update):
        return
    await update.message.reply_text(
        "🔐 FreeGram Owner Panel", reply_markup=owner_keyboard()
    )


async def users(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not await owner_only(update):
        return
    try:
        result = await asyncio.to_thread(
            api_request, "/v1/owner/users", "GET", None, "owner"
        )
        rows = result.get("users", [])
        if not rows:
            await update.message.reply_text("Пока нет аккаунтов.")
            return
        lines = ["👥 Последние аккаунты FreeGram:"]
        for user in rows[:30]:
            state = "🚫 бан" if user.get("banned") else (
                "✅" if user.get("verified") else "⏳"
            )
            lines.append(
                f'{state} {user.get("virtual_id", "—")} | '
                f'⭐ {user.get("stars", 0)} | '
                f'метка: {user.get("label") or "—"}'
            )
        await update.message.reply_text(
            "\n".join(lines), reply_markup=owner_keyboard()
        )
    except Exception:
        log.exception("Owner users error")
        await update.message.reply_text(
            "❌ Не удалось загрузить аккаунты API. Проверь AUTH_API_URL и OWNER_API_SECRET."
        )


async def owner_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not await owner_only(update):
        return
    command = update.message.text.split()[0].split("@")[0].lstrip("/").lower()
    args = context.args
    if command in (
        "ban", "unban", "verify", "unverify", "nft_remove", "number_remove"
    ):
        if len(args) != 1:
            await update.message.reply_text(
                f"Формат: /{command} FG-XXXXXXXXXX"
            )
            return
        action, value = command, None
    elif command in ("stars_add", "stars_remove", "stars_set"):
        if len(args) != 2 or not args[1].isdigit():
            await update.message.reply_text(
                f"Формат: /{command} FG-XXXXXXXXXX число"
            )
            return
        action, value = command, int(args[1])
    elif command in ("label", "nft_set", "number_set"):
        if len(args) < 2:
            await update.message.reply_text(
                f"Формат: /{command} FG-XXXXXXXXXX значение"
            )
            return
        action, value = command, " ".join(args[1:])
    else:
        return

    try:
        result = await owner_action(args[0], action, value)
        user = result["user"]
        await update.message.reply_text(
            "✅ Изменения сохранены в общей БД FreeGram.\n"
            f'ID: {user["virtual_id"]}\n'
            f'Статус: {"заблокирован" if user["banned"] else "активен"}\n'
            f'Проверен: {"да" if user["verified"] else "нет"}\n'
            f'Stars: {user["stars"]}\n'
            f'Метка: {user["label"] or "—"}\n'
            f'NFT: {user["nft_username"] or "—"}\n'
            f'Номер: {user["virtual_number"] or "—"}'
        )
    except Exception as exc:
        log.exception("Owner action failed")
        await update.message.reply_text(f"❌ Не выполнено: {str(exc)[:300]}")


async def on_button(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    user = query.from_user

    if query.data == "get_id":
        await query.edit_message_text("⏳ Запрашиваю регистрацию у FreeGram API...")
        try:
            result = await asyncio.to_thread(
                api_request, "/v1/registrations", "POST",
                {"telegram_id": user.id}, "bot"
            )
            await query.edit_message_text(
                "✅ Регистрация FreeGram создана!\n\n"
                f'🆔 FreeGram ID: {result["virtual_id"]}\n'
                f'🔐 Одноразовый код: {result["code"]}\n\n'
                "Код действует 10 минут. Введи эти данные в приложении FreeGram."
            )
        except Exception as exc:
            log.exception("Registration API error")
            await query.edit_message_text(
                f"❌ Не удалось создать регистрацию: {str(exc)[:250]}"
            )
        return

    if user.id != OWNER_TELEGRAM_ID or not OWNER_TELEGRAM_ID:
        await query.edit_message_text("⛔ Нет доступа.")
        return

    if query.data == "owner_status":
        try:
            result = await asyncio.to_thread(
                api_request, "/v1/owner/status", "GET", None, "owner"
            )
            await query.edit_message_text(
                "🔐 FreeGram Owner Panel\n\n"
                f'👥 Аккаунтов: {result["total_users"]}\n'
                f'✅ Подтверждено: {result["verified_users"]}\n'
                f'🚫 Заблокировано: {result["banned_users"]}',
                reply_markup=owner_keyboard(),
            )
        except Exception:
            log.exception("Owner status error")
            await query.edit_message_text(
                "❌ Ошибка получения статистики. Проверь настройки API.",
                reply_markup=owner_keyboard(),
            )
    elif query.data == "owner_users":
        try:
            result = await asyncio.to_thread(
                api_request, "/v1/owner/users", "GET", None, "owner"
            )
            rows = result.get("users", [])
            lines = ["👥 Аккаунты (последние 20):"]
            for item in rows[:20]:
                state = "🚫" if item.get("banned") else (
                    "✅" if item.get("verified") else "⏳"
                )
                lines.append(
                    f'{state} {item.get("virtual_id", "—")} | '
                    f'⭐ {item.get("stars", 0)} | {item.get("label") or "—"}'
                )
            await query.edit_message_text(
                "\n".join(lines) if rows else "Аккаунтов пока нет.",
                reply_markup=owner_keyboard(),
            )
        except Exception:
            log.exception("Owner users callback error")
            await query.edit_message_text(
                "❌ Не удалось получить список.",
                reply_markup=owner_keyboard(),
            )
    elif query.data == "owner_help":
        await query.edit_message_text(
            "🔐 Управление аккаунтами (только владелец):\n\n"
            "/users — список аккаунтов\n"
            "/ban ID, /unban ID — блокировка\n"
            "/verify ID, /unverify ID — проверка\n"
            "/stars_add ID число — добавить Stars\n"
            "/stars_remove ID число — снять Stars\n"
            "/stars_set ID число — установить баланс\n"
            "/label ID текст — назначить метку\n"
            "/nft_set ID имя — назначить NFT username\n"
            "/nft_remove ID — снять NFT username\n"
            "/number_set ID +888... — назначить номер\n"
            "/number_remove ID — снять номер\n\n"
            "Все изменения идут в общую БД API.",
            reply_markup=owner_keyboard(),
        )


async def error_handler(update: object, context: ContextTypes.DEFAULT_TYPE):
    log.error(
        "Unhandled Telegram application error: %s",
        context.error,
        exc_info=(type(context.error), context.error, context.error.__traceback__)
        if context.error else None,
    )


async def post_init(application: Application):
    bot_state["status"] = "online"
    bot_state["polling"] = True
    log.info("Telegram application initialized; polling startup requested")


async def post_shutdown(application: Application):
    bot_state["status"] = "stopping"
    bot_state["polling"] = False


def main():
    if not BOT_TOKEN:
        log.critical("BOT_TOKEN is missing. Add it to Render Environment.")
        sys.exit(2)

    if not OWNER_TELEGRAM_ID:
        log.warning("OWNER_TELEGRAM_ID is missing; owner commands are disabled.")
    if not AUTH_API_URL:
        log.warning("AUTH_API_URL is missing; Telegram replies work, API actions do not.")

    threading.Thread(
        target=run_http_server, name="health-http", daemon=True
    ).start()

    application = (
        Application.builder()
        .token(BOT_TOKEN)
        .connect_timeout(20)
        .read_timeout(35)
        .write_timeout(35)
        .pool_timeout(35)
        .post_init(post_init)
        .post_shutdown(post_shutdown)
        .build()
    )
    application.add_error_handler(error_handler)
    application.add_handler(CommandHandler("start", start))
    application.add_handler(CommandHandler("ping", ping))
    application.add_handler(CommandHandler("owner", owner))
    application.add_handler(CommandHandler("users", users))

    commands = [
        "ban", "unban", "verify", "unverify", "stars_add", "stars_remove",
        "stars_set", "label", "nft_set", "nft_remove", "number_set",
        "number_remove",
    ]
    for name in commands:
        application.add_handler(CommandHandler(name, owner_command))
    application.add_handler(CallbackQueryHandler(on_button))

    log.info("Starting FreeGram Telegram polling")
    application.run_polling(
        poll_interval=1.0,
        timeout=30,
        bootstrap_retries=-1,
        read_timeout=35,
        write_timeout=35,
        connect_timeout=20,
        pool_timeout=35,
        drop_pending_updates=False,
        close_loop=False,
    )


if __name__ == "__main__":
    main()
