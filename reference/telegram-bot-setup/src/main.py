from __future__ import annotations

import logging
from typing import Iterable

from telegram import Update
from telegram.ext import Application, CommandHandler, ContextTypes, filters

from .config import settings
from .providers import PROVIDER_BY_SLUG

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
)
logger = logging.getLogger("telegram-bot-setup")


def mask(value: str) -> str:
    if not value:
        return "not set"
    if len(value) <= 8:
        return "set"
    return f"{value[:4]}...{value[-4:]}"


def provider_lines() -> Iterable[str]:
    configured = settings.configured_providers
    if not configured:
        yield "No AI providers configured yet."
        return
    for slug, name in configured.items():
        provider = PROVIDER_BY_SLUG[slug]
        values = [settings.provider_keys.get(field.key, "") for field in provider.fields if field.secret]
        state = mask(values[0]) if values else "endpoint ready"
        default = " (default)" if slug == settings.ai_provider else ""
        yield f"- {name}{default}: {state}"


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    chat_id = update.effective_chat.id if update.effective_chat else "unknown"
    text = (
        "Telegram GitHub Bot Setup is online.\n\n"
        f"Your chat ID: `{chat_id}`\n\n"
        "Commands:\n"
        "/id - show chat ID\n"
        "/status - show configured services\n"
        "/providers - show configured AI providers\n"
        "/help - command list"
    )
    await update.message.reply_markdown(text)


async def chat_id(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    chat_id = update.effective_chat.id if update.effective_chat else "unknown"
    await update.message.reply_text(f"Chat ID: {chat_id}")


async def status(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    checks = {
        "Telegram token": bool(settings.telegram_bot_token),
        "Telegram chat ID": bool(settings.telegram_chat_id),
        "GitHub token": bool(settings.github_token),
        "GitHub repo": bool(settings.github_repo),
        "Public base URL": bool(settings.public_base_url),
    }
    lines = ["Status:"]
    for name, ok in checks.items():
        lines.append(f"- {name}: {'OK' if ok else 'missing'}")
    lines.append(f"- AI providers configured: {len(settings.configured_providers)}")
    if settings.ai_provider:
        lines.append(f"- Default AI provider: {settings.ai_provider}")
    if settings.ai_model:
        lines.append(f"- Default AI model: {settings.ai_model}")
    await update.message.reply_text("\n".join(lines))


async def providers(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await update.message.reply_text("Configured providers:\n" + "\n".join(provider_lines()))


async def help_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await update.message.reply_text(
        "Commands:\n"
        "/start - boot message\n"
        "/id - get this chat ID\n"
        "/status - config health check\n"
        "/providers - configured API providers\n"
    )


def build_app() -> Application:
    if not settings.telegram_bot_token:
        raise RuntimeError("Missing TELEGRAM_BOT_TOKEN. Run python -m src.setup_env first.")
    app = Application.builder().token(settings.telegram_bot_token).build()
    chat_filter = filters.Chat(chat_id=int(settings.telegram_chat_id)) if settings.telegram_chat_id else None
    app.add_handler(CommandHandler("start", start, filters=chat_filter))
    app.add_handler(CommandHandler("id", chat_id, filters=chat_filter))
    app.add_handler(CommandHandler("status", status, filters=chat_filter))
    app.add_handler(CommandHandler("providers", providers, filters=chat_filter))
    app.add_handler(CommandHandler("help", help_cmd, filters=chat_filter))
    return app


def main() -> None:
    app = build_app()
    logger.info("Bot starting with polling mode")
    app.run_polling(close_loop=False)


if __name__ == "__main__":
    main()
