from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urlparse

from .providers import PROVIDER_BY_SLUG, ProviderSpec


@dataclass(frozen=True)
class ValidationIssue:
    field: str
    message: str
    level: str = "error"


TOKEN_RE = re.compile(r"^\d{6,12}:[A-Za-z0-9_-]{30,}$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
VALID_LOG_LEVELS = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}


def is_http_url(value: str) -> bool:
    parsed = urlparse(value)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def selected_provider_slugs(values: dict[str, str]) -> list[str]:
    raw = values.get("AI_PROVIDERS", "")
    return [item.strip().lower() for item in raw.split(",") if item.strip()]


def provider_is_configured(provider: ProviderSpec, values: dict[str, str]) -> bool:
    return all(values.get(field.key, "").strip() for field in provider.fields if field.required)


def validate_values(values: dict[str, str]) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []
    token = values.get("TELEGRAM_BOT_TOKEN", "").strip()
    if not token:
        issues.append(ValidationIssue("TELEGRAM_BOT_TOKEN", "is required"))
    elif not TOKEN_RE.fullmatch(token):
        issues.append(ValidationIssue("TELEGRAM_BOT_TOKEN", "does not look like a BotFather token"))

    chat_id = values.get("TELEGRAM_CHAT_ID", "").strip()
    if chat_id and not re.fullmatch(r"-?\d+", chat_id):
        issues.append(ValidationIssue("TELEGRAM_CHAT_ID", "must be an integer (group IDs may start with -)"))

    repo = values.get("GITHUB_REPO", "").strip()
    if repo and not REPO_RE.fullmatch(repo):
        issues.append(ValidationIssue("GITHUB_REPO", "must use owner/repository format"))

    port = values.get("WEBHOOK_PORT", "8080").strip()
    try:
        if not 1 <= int(port) <= 65535:
            raise ValueError
    except ValueError:
        issues.append(ValidationIssue("WEBHOOK_PORT", "must be an integer from 1 to 65535"))

    webhook_path = values.get("WEBHOOK_PATH", "/telegram").strip()
    if webhook_path and not webhook_path.startswith("/"):
        issues.append(ValidationIssue("WEBHOOK_PATH", "must start with /"))

    for key in ("PUBLIC_BASE_URL",):
        value = values.get(key, "").strip()
        if value and not is_http_url(value):
            issues.append(ValidationIssue(key, "must be a valid http:// or https:// URL"))

    log_level = values.get("LOG_LEVEL", "INFO").strip().upper()
    if log_level not in VALID_LOG_LEVELS:
        issues.append(ValidationIssue("LOG_LEVEL", f"must be one of {', '.join(sorted(VALID_LOG_LEVELS))}"))

    selected = selected_provider_slugs(values)
    for slug in selected:
        provider = PROVIDER_BY_SLUG.get(slug)
        if not provider:
            issues.append(ValidationIssue("AI_PROVIDERS", f"unknown provider '{slug}'"))
            continue
        for field in provider.fields:
            value = values.get(field.key, "").strip()
            if field.required and not value:
                issues.append(ValidationIssue(field.key, f"is required for {provider.name}"))
            elif value and field.kind == "url" and not is_http_url(value):
                issues.append(ValidationIssue(field.key, "must be a valid http:// or https:// URL"))

    default_provider = values.get("AI_PROVIDER", "").strip().lower()
    if default_provider:
        if default_provider not in PROVIDER_BY_SLUG:
            issues.append(ValidationIssue("AI_PROVIDER", f"unknown provider '{default_provider}'"))
        elif default_provider not in selected:
            issues.append(ValidationIssue("AI_PROVIDER", "must also be listed in AI_PROVIDERS"))

    if values.get("PUBLIC_BASE_URL", "").strip() and not values.get("WEBHOOK_PATH", "").strip():
        issues.append(ValidationIssue("WEBHOOK_PATH", "is required when PUBLIC_BASE_URL is set"))

    return issues
