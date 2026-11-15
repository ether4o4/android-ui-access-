#!/usr/bin/env python3
"""
AutoNoBot CLI
─────────────
autonobot setup    → interactive first-time setup
autonobot start    → start Telegram bridge
autonobot status   → check config + dependencies
autonobot tools    → list available tools
autonobot skills   → list installed skills
"""

import json
import subprocess
import sys
from pathlib import Path

CONFIG_DIR = Path.home() / ".autonobot"
TG_CFG     = CONFIG_DIR / "telegram_config.json"
EMAIL_CFG  = CONFIG_DIR / "email_config.json"
SKILLS_DIR = CONFIG_DIR / "skills"


def _ok(msg):   print(f"  ✅  {msg}")
def _warn(msg): print(f"  ⚠️   {msg}")
def _err(msg):  print(f"  ❌  {msg}")
def _info(msg): print(f"  ℹ️   {msg}")

def _run(cmd, timeout=10):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

def _termux_api_ok() -> bool:
    return _run(["which", "termux-battery-status"]).returncode == 0


# ─── SETUP ───────────────────────────────────────────────────────────────────

def cmd_setup():
    print("\n🤖  AutoNoBot Setup\n" + "─" * 40)
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)

    # Telegram
    print("\n[1/3] Telegram Bot")
    if TG_CFG.exists():
        existing = json.loads(TG_CFG.read_text())
        token = existing.get("bot_token", "")
        if token and token != "PASTE_YOUR_BOT_TOKEN_HERE":
            _ok(f"Already configured (token ends …{token[-6:]})")
        else:
            _warn("Config exists but token not set")
            _prompt_telegram()
    else:
        _prompt_telegram()

    # Ollama
    print("\n[2/3] Ollama")
    ollama_url = "http://127.0.0.1:11434"
    r = _run(["curl", "-s", "--max-time", "3", f"{ollama_url}/api/tags"])
    if r.returncode == 0:
        _ok(f"Ollama reachable at {ollama_url}")
    else:
        _warn(f"Ollama not reachable at {ollama_url}")
        _info("Make sure Ollama is running: ollama serve")

    # termux-api
    print("\n[3/3] Termux:API")
    if _termux_api_ok():
        _ok("termux-api tools found")
    else:
        _warn("termux-api not installed")
        _info("Fix: pkg install termux-api")
        _info("Also install Termux:API companion app from F-Droid")

    # Email (optional)
    print("\n[Optional] Email")
    if EMAIL_CFG.exists():
        _ok("email_config.json found")
    else:
        _info(f"To enable email, create {EMAIL_CFG}")
        _info('{"sender_email":"you@gmail.com","app_password":"xxxx","smtp_server":"smtp.gmail.com","smtp_port":587}')

    print("\n" + "─" * 40)
    print("Setup complete. Run:  autonobot start\n")


def _prompt_telegram():
    print()
    print("  Get your bot token:")
    print("    1. Message @BotFather on Telegram → /newbot")
    print("    2. Copy the token it gives you")
    print()
    token = input("  Paste bot token (Enter to skip): ").strip()
    if not token:
        _warn("Skipped — edit ~/.autonobot/telegram_config.json manually")
        _write_tg_template()
        return
    uid = input("  Your Telegram user ID (@userinfobot): ").strip()
    cfg = {
        "bot_token": token,
        "allowed_user_ids": [uid] if uid else [],
        "ollama_url": "http://127.0.0.1:11434",
        "ollama_model": "autonobot",
    }
    TG_CFG.write_text(json.dumps(cfg, indent=2))
    _ok(f"Saved to {TG_CFG}")


def _write_tg_template():
    if not TG_CFG.exists():
        cfg = {
            "bot_token": "PASTE_YOUR_BOT_TOKEN_HERE",
            "allowed_user_ids": ["PASTE_YOUR_TELEGRAM_USER_ID"],
            "ollama_url": "http://127.0.0.1:11434",
            "ollama_model": "autonobot",
        }
        TG_CFG.write_text(json.dumps(cfg, indent=2))
        _info(f"Template written to {TG_CFG}")


# ─── STATUS ──────────────────────────────────────────────────────────────────

def cmd_status():
    print("\n🤖  AutoNoBot Status\n" + "─" * 40)

    print("\nConfig:")
    if TG_CFG.exists():
        cfg = json.loads(TG_CFG.read_text())
        token = cfg.get("bot_token", "")
        if token and token != "PASTE_YOUR_BOT_TOKEN_HERE":
            _ok(f"telegram_config.json (token …{token[-6:]})")
        else:
            _err("telegram_config.json — token not set")
    else:
        _err("telegram_config.json — missing. Run: autonobot setup")

    if EMAIL_CFG.exists():
        _ok("email_config.json")
    else:
        _warn("email_config.json — optional, not set")

    print("\nOllama:")
    try:
        r = _run(["curl", "-s", "--max-time", "3", "http://127.0.0.1:11434/api/tags"])
        if r.returncode == 0:
            data = json.loads(r.stdout)
            models = [m["name"] for m in data.get("models", [])]
            _ok(f"Running | models: {', '.join(models) or 'none pulled yet'}")
        else:
            _err("Not reachable — run: ollama serve")
    except Exception as e:
        _err(f"Check failed: {e}")

    print("\nTermux:API:")
    for t in ["termux-battery-status", "termux-sms-send", "termux-camera-photo",
              "termux-notification", "termux-location"]:
        ok = _run(["which", t]).returncode == 0
        (_ok if ok else _warn)(t)

    print("\nSkills:")
    if SKILLS_DIR.exists():
        skills = [d.name for d in SKILLS_DIR.iterdir() if d.is_dir() and (d / "SKILL.md").exists()]
        _ok(f"{len(skills)} skills installed: {', '.join(skills[:5])}{'...' if len(skills) > 5 else ''}")
    else:
        _warn("No skills directory found")

    print()


# ─── START ───────────────────────────────────────────────────────────────────

def cmd_start():
    print("\n🤖  Starting AutoNoBot Telegram Bridge…\n")
    if not TG_CFG.exists():
        _err("No config found. Run: autonobot setup")
        sys.exit(1)
    cfg = json.loads(TG_CFG.read_text())
    if cfg.get("bot_token") == "PASTE_YOUR_BOT_TOKEN_HERE":
        _err("Bot token not set. Run: autonobot setup")
        sys.exit(1)
    from autonobot.telegram_bridge import main as bridge_main
    bridge_main()


# ─── TOOLS ───────────────────────────────────────────────────────────────────

def cmd_tools():
    from autonobot.handlers import HANDLERS
    print("\n🛠️  Available Tools\n" + "─" * 40)
    categories = {
        "App Control":    ["open_app", "list_apps"],
        "Device":         ["get_device_info", "battery_status", "wifi_info", "get_location"],
        "Communication":  ["send_sms", "read_sms", "send_email", "make_call", "get_contacts"],
        "Media/Sensors":  ["take_photo", "vibrate", "torch"],
        "Clipboard":      ["get_clipboard", "set_clipboard"],
        "Notifications":  ["send_notification"],
        "File System":    ["read_file", "write_file", "list_files"],
        "Shell":          ["execute_shell"],
        "HTTP":           ["http_get", "http_post"],
    }
    for cat, tools in categories.items():
        print(f"\n  {cat}:")
        for t in tools:
            print(f"    • {t}")
    print()


# ─── SKILLS ──────────────────────────────────────────────────────────────────

def cmd_skills():
    print("\n📦  Installed Skills\n" + "─" * 40)
    if not SKILLS_DIR.exists():
        _err("Skills directory not found")
        return

    for skill_dir in sorted(SKILLS_DIR.iterdir()):
        if not skill_dir.is_dir():
            continue
        skill_md = skill_dir / "SKILL.md"
        if skill_md.exists():
            # Read first line after # heading
            lines = skill_md.read_text().splitlines()
            name = skill_dir.name
            desc = ""
            for line in lines:
                if line.startswith("# "):
                    desc = line[2:].strip()
                    break
            tools_file = skill_dir / "tools.json"
            tool_count = 0
            if tools_file.exists():
                try:
                    data = json.loads(tools_file.read_text())
                    tool_count = len(data.get("tools", []))
                except Exception:
                    pass
            print(f"\n  {name}")
            if desc:
                print(f"    {desc}")
            if tool_count:
                print(f"    {tool_count} tools")
    print()


# ─── ENTRY POINT ─────────────────────────────────────────────────────────────

COMMANDS = {
    "setup":  cmd_setup,
    "start":  cmd_start,
    "status": cmd_status,
    "tools":  cmd_tools,
    "skills": cmd_skills,
}


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print("\nUsage: autonobot <command>\n")
        print("Commands:")
        print("  setup    → interactive first-time setup")
        print("  start    → start Telegram bridge")
        print("  status   → check config & dependencies")
        print("  tools    → list available tools")
        print("  skills   → list installed skills")
        print()
        sys.exit(0)
    COMMANDS[sys.argv[1]]()


if __name__ == "__main__":
    main()
