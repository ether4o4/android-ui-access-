from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Dict

from dotenv import load_dotenv

from .providers import API_PROVIDERS, PROVIDER_BY_SLUG

ROOT_DIR = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT_DIR / ".env"
load_dotenv(ENV_PATH)


def env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)) or default)
    except ValueError:
        return default

CORE_KEYS = [
    ("TELEGRAM_BOT_TOKEN", "Telegram bot token"),
    ("TELEGRAM_CHAT_ID", "Allowed Telegram chat ID"),
    ("GITHUB_TOKEN", "GitHub token"),
    ("GITHUB_USERNAME", "GitHub username"),
    ("GITHUB_REPO", "GitHub repo owner/name"),
    ("WEBHOOK_PORT", "Webhook port", "8080"),
    ("WEBHOOK_PATH", "Webhook path", "/telegram"),
    ("CLOUDFLARED_TOKEN", "Cloudflared token"),
    ("PUBLIC_BASE_URL", "Public base URL"),
    ("APP_ENV", "App environment", "local"),
    ("LOG_LEVEL", "Log level", "INFO"),
]


@dataclass(frozen=True)
class Settings:
    telegram_bot_token: str = os.getenv("TELEGRAM_BOT_TOKEN", "")
    telegram_chat_id: str = os.getenv("TELEGRAM_CHAT_ID", "")
    github_token: str = os.getenv("GITHUB_TOKEN", "")
    github_username: str = os.getenv("GITHUB_USERNAME", "")
    github_repo: str = os.getenv("GITHUB_REPO", "")
    ai_provider: str = os.getenv("AI_PROVIDER", "")
    ai_model: str = os.getenv("AI_MODEL", "")
    webhook_port: int = env_int("WEBHOOK_PORT", 8080)
    webhook_path: str = os.getenv("WEBHOOK_PATH", "/telegram")
    public_base_url: str = os.getenv("PUBLIC_BASE_URL", "")
    log_level: str = os.getenv("LOG_LEVEL", "INFO")

    @property
    def selected_provider_slugs(self) -> list[str]:
        raw = os.getenv("AI_PROVIDERS", "")
        return [item.strip().lower() for item in raw.split(",") if item.strip()]

    @property
    def provider_keys(self) -> Dict[str, str]:
        return {key: os.getenv(key, "") for key, _ in API_PROVIDERS}

    @property
    def configured_providers(self) -> Dict[str, str]:
        configured: Dict[str, str] = {}
        for slug in self.selected_provider_slugs:
            provider = PROVIDER_BY_SLUG.get(slug)
            if provider and all(os.getenv(field.key, "") for field in provider.fields if field.required):
                configured[slug] = provider.name
        return configured


settings = Settings()
