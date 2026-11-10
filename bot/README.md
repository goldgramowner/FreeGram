# FreeGram Auth Bot

Initial bot scaffold for the FreeGram Android client.

## Environment

Set these variables on the machine that runs the bot:

- `BOT_TOKEN` — token from BotFather. Never commit it.
- `OWNER_TELEGRAM_ID` — numeric Telegram ID of the owner.
- Optional: `FREEGRAM_BOT_DB` — SQLite database path (default: `freegram_bot.sqlite3`).

## Run

```bash
python -m pip install -r requirements.txt
python main.py
```

Open [@FreeGramAuthBot](https://t.me/FreeGramAuthBot) and send `/start`.

## Current scope

- Public `/start` flow.
- Creates a unique virtual FreeGram ID and a one-time demonstration code.
- Private `/owner` menu with server-side Telegram ID authorization.
- Stores test registration requests in SQLite.

The virtual ID is an application identifier, not a real phone number. The Android app is not connected yet. A shared backend/API must be built before codes can authenticate app sessions. Owner actions for Stars, labels, NFT usernames, verification and bans must use that same backend database.

Never put bot tokens, API hashes, passwords, or other secrets in source files.
