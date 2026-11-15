from __future__ import annotations

import argparse
import getpass
import os
import shutil
import sys
from datetime import datetime
from pathlib import Path

from dotenv import dotenv_values

from .config import CORE_KEYS, ENV_PATH
from .providers import PROVIDERS, PROVIDER_BY_SLUG
from .validation import ValidationIssue, selected_provider_slugs, validate_values

APP_KEYS = ["AI_PROVIDERS", "AI_PROVIDER", "AI_MODEL"]
KNOWN_KEYS = [item[0] for item in CORE_KEYS] + APP_KEYS + [
    field.key for provider in PROVIDERS for field in provider.fields
]
OPTIONAL_INTEGRATION_KEYS = [
    "SERPAPI_API_KEY", "BRAVE_SEARCH_API_KEY", "TAVILY_API_KEY",
    "PINECONE_API_KEY", "SUPABASE_URL", "SUPABASE_ANON_KEY",
    "SUPABASE_SERVICE_ROLE_KEY", "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY", "AWS_REGION",
]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Configure and validate Telegram-bot-setup without exposing secrets."
    )
    parser.add_argument("--env-file", type=Path, default=ENV_PATH, help="environment file to read or write")
    parser.add_argument("--provider", action="append", default=[], help="provider slug; repeat or use commas")
    parser.add_argument("--all-providers", action="store_true", help="configure every provider")
    parser.add_argument("--non-interactive", action="store_true", help="use existing file and environment variables only")
    parser.add_argument("--validate-only", action="store_true", help="validate configuration without changing files")
    parser.add_argument("--list-providers", action="store_true", help="print supported provider slugs and exit")
    return parser.parse_args(argv)


def load_values(env_path: Path) -> dict[str, str]:
    values = {key: str(value or "") for key, value in dotenv_values(env_path).items()} if env_path.exists() else {}
    for key in set(KNOWN_KEYS + OPTIONAL_INTEGRATION_KEYS):
        if os.getenv(key) is not None:
            values[key] = os.environ[key]
    return values


def prompt_value(label: str, current: str = "", *, secret: bool = False, default: str = "") -> str:
    if secret:
        suffix = " [keep current]" if current else ""
        value = getpass.getpass(f"{label}{suffix}: ").strip()
    else:
        shown_default = current or default
        suffix = f" [{shown_default}]" if shown_default else ""
        value = input(f"{label}{suffix}: ").strip()
    return value or current or default


def prompt_yes_no(label: str, default: bool = False) -> bool:
    suffix = " [Y/n]" if default else " [y/N]"
    value = input(f"{label}{suffix}: ").strip().lower()
    if not value:
        return default
    return value in {"y", "yes"}


def parse_requested_providers(args: argparse.Namespace, values: dict[str, str]) -> list[str]:
    if args.all_providers:
        return [provider.slug for provider in PROVIDERS]
    requested = [slug.strip().lower() for item in args.provider for slug in item.split(",") if slug.strip()]
    selected = requested or selected_provider_slugs(values)
    return list(dict.fromkeys(selected))


def prompt_provider_selection(current: list[str]) -> list[str]:
    print("\nAI providers (choose only what you plan to use):")
    for index, provider in enumerate(PROVIDERS, 1):
        print(f"  {index:>2}. {provider.name:<22} {provider.slug}")
    current_text = ",".join(current)
    suffix = f" [{current_text}]" if current_text else " [none]"
    answer = input(f"Provider numbers or slugs, comma-separated{suffix}: ").strip()
    if not answer:
        return current

    selected: list[str] = []
    for item in (part.strip().lower() for part in answer.split(",")):
        if item.isdigit() and 1 <= int(item) <= len(PROVIDERS):
            slug = PROVIDERS[int(item) - 1].slug
        else:
            slug = item
        if slug not in selected:
            selected.append(slug)
    return selected


def prompt_configuration(values: dict[str, str], selected: list[str]) -> dict[str, str]:
    print("\nRequired Telegram configuration")
    values["TELEGRAM_BOT_TOKEN"] = prompt_value(
        "BotFather token", values.get("TELEGRAM_BOT_TOKEN", ""), secret=True
    )
    values["TELEGRAM_CHAT_ID"] = prompt_value(
        "Allowed chat ID (optional; /id can show it later)", values.get("TELEGRAM_CHAT_ID", "")
    )

    github_current = any(values.get(key) for key in ("GITHUB_TOKEN", "GITHUB_USERNAME", "GITHUB_REPO"))
    if prompt_yes_no("Configure optional GitHub access", github_current):
        values["GITHUB_TOKEN"] = prompt_value("GitHub token", values.get("GITHUB_TOKEN", ""), secret=True)
        values["GITHUB_USERNAME"] = prompt_value("GitHub username", values.get("GITHUB_USERNAME", ""))
        values["GITHUB_REPO"] = prompt_value("GitHub repository (owner/name)", values.get("GITHUB_REPO", ""))

    values.setdefault("WEBHOOK_PORT", "8080")
    values.setdefault("WEBHOOK_PATH", "/telegram")
    values.setdefault("APP_ENV", "local")
    values.setdefault("LOG_LEVEL", "INFO")

    for slug in selected:
        provider = PROVIDER_BY_SLUG.get(slug)
        if not provider:
            continue
        print(f"\n{provider.name}")
        for field in provider.fields:
            values[field.key] = prompt_value(
                field.label,
                values.get(field.key, ""),
                secret=field.secret,
                default=field.default,
            )

    values["AI_PROVIDERS"] = ",".join(selected)
    current_default = values.get("AI_PROVIDER", "")
    if current_default not in selected:
        current_default = selected[0] if selected else ""
    if selected:
        values["AI_PROVIDER"] = prompt_value("Default provider", current_default)
        provider = PROVIDER_BY_SLUG.get(values["AI_PROVIDER"])
        model_default = provider.default_model if provider else ""
        values["AI_MODEL"] = prompt_value("Default model (optional)", values.get("AI_MODEL", ""), default=model_default)
    else:
        values["AI_PROVIDER"] = ""
        values["AI_MODEL"] = ""
    return values


def format_issue(issue: ValidationIssue) -> str:
    return f"{issue.field}: {issue.message}"


def print_validation(values: dict[str, str]) -> list[ValidationIssue]:
    issues = validate_values(values)
    errors = [issue for issue in issues if issue.level == "error"]
    if errors:
        print("\nConfiguration has errors:", file=sys.stderr)
        for issue in errors:
            print(f"  - {format_issue(issue)}", file=sys.stderr)
        return errors

    selected = selected_provider_slugs(values)
    default = values.get("AI_PROVIDER", "") or "none"
    print("\nConfiguration is valid.")
    print(f"  Telegram token: set")
    print(f"  AI providers: {', '.join(selected) if selected else 'none'}")
    print(f"  Default provider: {default}")
    return []


def env_value(value: str) -> str:
    if not value:
        return ""
    if all(char.isalnum() or char in "_./:@+-" for char in value):
        return value
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def write_env(env_path: Path, values: dict[str, str]) -> Path | None:
    backup: Path | None = None
    if env_path.exists():
        backup = env_path.with_name(f"{env_path.name}.backup.{datetime.now().strftime('%Y%m%d-%H%M%S')}")
        shutil.copy2(env_path, backup)

    sections: list[tuple[str, list[str]]] = [
        ("Telegram", ["TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"]),
        ("GitHub (optional)", ["GITHUB_TOKEN", "GITHUB_USERNAME", "GITHUB_REPO"]),
        ("AI selection", APP_KEYS),
        ("AI provider credentials and endpoints", [field.key for provider in PROVIDERS for field in provider.fields]),
        ("Optional integrations", OPTIONAL_INTEGRATION_KEYS),
        ("Webhook and tunnel", ["WEBHOOK_PORT", "WEBHOOK_PATH", "CLOUDFLARED_TOKEN", "PUBLIC_BASE_URL"]),
        ("Application", ["APP_ENV", "LOG_LEVEL"]),
    ]
    rendered = ["# Generated by Telegram-bot-setup. Never commit this file.", ""]
    emitted: set[str] = set()
    for heading, keys in sections:
        rendered.append(f"# {heading}")
        for key in keys:
            rendered.append(f"{key}={env_value(values.get(key, ''))}")
            emitted.add(key)
        rendered.append("")

    custom = sorted(key for key in values if key not in emitted)
    if custom:
        rendered.append("# Preserved custom values")
        for key in custom:
            rendered.append(f"{key}={env_value(values[key])}")
        rendered.append("")

    env_path.parent.mkdir(parents=True, exist_ok=True)
    env_path.write_text("\n".join(rendered), encoding="utf-8")
    try:
        env_path.chmod(0o600)
    except OSError:
        pass
    return backup


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.list_providers:
        for provider in PROVIDERS:
            print(f"{provider.slug:<16} {provider.name}")
        return 0

    values = load_values(args.env_file)
    selected = parse_requested_providers(args, values)
    unknown = [slug for slug in selected if slug not in PROVIDER_BY_SLUG]
    if unknown:
        print(f"Unknown provider(s): {', '.join(unknown)}", file=sys.stderr)
        print("Run with --list-providers to see valid names.", file=sys.stderr)
        return 2

    if args.validate_only:
        return 2 if print_validation(values) else 0

    if args.non_interactive:
        values["AI_PROVIDERS"] = ",".join(selected)
        if selected:
            if values.get("AI_PROVIDER", "").strip().lower() not in selected:
                values["AI_PROVIDER"] = selected[0]
        else:
            values["AI_PROVIDER"] = ""
            values["AI_MODEL"] = ""
    else:
        if not args.provider and not args.all_providers:
            selected = prompt_provider_selection(selected)
        values = prompt_configuration(values, selected)

    if print_validation(values):
        print("No files were changed.", file=sys.stderr)
        return 2

    backup = write_env(args.env_file, values)
    print(f"Saved {args.env_file}")
    if backup:
        print(f"Backup: {backup}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
