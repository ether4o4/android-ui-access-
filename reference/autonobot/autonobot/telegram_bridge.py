#!/usr/bin/env python3
"""
AutoNoBot Telegram Bridge
──────────────────────────
Telegram → Ollama (Qwen) → Tool Execution → Response

Config: ~/.autonobot/telegram_config.json
Logs:   ~/.autonobot/logs/bridge.log
"""

import json
import logging
import sys
import time
from pathlib import Path

import requests

from autonobot.handlers import HANDLERS

# ─── CONFIG & LOGGING ────────────────────────────────────────────────────────

CONFIG_DIR  = Path.home() / ".autonobot"
CONFIG_PATH = CONFIG_DIR / "telegram_config.json"
LOG_PATH    = CONFIG_DIR / "logs" / "bridge.log"

LOG_PATH.parent.mkdir(parents=True, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.FileHandler(LOG_PATH), logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger("autonobot")


def load_config() -> dict:
    if not CONFIG_PATH.exists():
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps({
            "bot_token": "PASTE_YOUR_BOT_TOKEN_HERE",
            "allowed_user_ids": ["PASTE_YOUR_TELEGRAM_USER_ID"],
            "ollama_url": "http://127.0.0.1:11434",
            "ollama_model": "autonobot",
        }, indent=2))
        print(f"\n❌ Config not found. Template created at {CONFIG_PATH}")
        print("Run: autonobot setup\n")
        sys.exit(1)
    cfg = json.loads(CONFIG_PATH.read_text())
    # Auto-detect: use custom 'autonobot' model if it exists, else base model
    try:
        r = requests.get(f"{cfg.get('ollama_url', 'http://127.0.0.1:11434')}/api/tags", timeout=3)
        models = [m["name"] for m in r.json().get("models", [])]
        if "autonobot:latest" in models or "autonobot" in models:
            cfg["ollama_model"] = "autonobot"
            log.info("Using custom 'autonobot' model with baked-in system prompt")
    except Exception:
        pass
    return cfg


# ─── SAFETY ──────────────────────────────────────────────────────────────────

DANGEROUS_PATTERNS = {
    "delete": ["rm -rf", "rm ", "rmdir", "delete_file", "unlink"],
    "financial": ["transfer", "payment", "send money", "withdraw", "deposit"],
    "crypto": ["bitcoin", "ethereum", "crypto", "wallet send"],
    "system": ["sudo", "su -", "chmod 777", "reboot", "shutdown", "factory_reset"],
    "account": ["delete account", "close account", "deactivate"],
}


def check_safety(tool: str, params: dict, user_text: str) -> str | None:
    """Returns warning string if dangerous, None if safe."""
    combined = f"{tool} {json.dumps(params)} {user_text}".lower()
    for category, patterns in DANGEROUS_PATTERNS.items():
        for p in patterns:
            if p in combined:
                return f"⚠️ DANGEROUS: {category} operation detected — `{p}`"
    return None


# ─── TELEGRAM BOT ────────────────────────────────────────────────────────────

class TelegramBot:
    def __init__(self, token: str):
        self.base = f"https://api.telegram.org/bot{token}"
        self.offset = 0

    def send(self, chat_id, text: str, parse_mode="Markdown"):
        chunks = [text[i:i+4000] for i in range(0, len(text), 4000)]
        for chunk in chunks:
            try:
                requests.post(f"{self.base}/sendMessage", json={
                    "chat_id": chat_id, "text": chunk, "parse_mode": parse_mode,
                }, timeout=10)
            except Exception as e:
                log.error(f"send failed: {e}")

    def send_photo(self, chat_id, path: str, caption: str = ""):
        try:
            with open(path, "rb") as f:
                requests.post(f"{self.base}/sendPhoto",
                              data={"chat_id": chat_id, "caption": caption},
                              files={"photo": f}, timeout=20)
        except Exception as e:
            log.error(f"send_photo failed: {e}")

    def get_updates(self):
        try:
            r = requests.get(f"{self.base}/getUpdates", params={
                "offset": self.offset, "timeout": 20,
                "allowed_updates": ["message"],
            }, timeout=30)
            updates = r.json().get("result", [])
            if updates:
                self.offset = updates[-1]["update_id"] + 1
            return updates
        except Exception as e:
            log.error(f"getUpdates failed: {e}")
            return []


# ─── SYSTEM PROMPT ───────────────────────────────────────────────────────────

TOOL_LIST = """AVAILABLE TOOLS:

App control & intents:
  open_app(app_identifier)               — launch app by name ("gmail", "spotify") or package
  list_apps(filter_str)                  — list installed apps
  open_url(url)                          — open any URL in browser
  share_text(text, app)                  — share text via share sheet or to specific app
  open_map(query)                        — open Google Maps with search/address
  set_alarm(hour, minute, message)       — set alarm (24h format)
  set_timer(seconds, message)            — set countdown timer
  create_event(title, description)       — create calendar event
  open_whatsapp(phone_number, message)   — open WhatsApp chat with pre-filled message

Communication (auto-routes: SMTP → intent → tasker):
  send_email(to, subject, body)          — sends email (SMTP if configured, else opens Gmail pre-filled + auto-taps Send)
  send_sms(phone_number, message)        — send SMS (termux-api or intent)
  read_sms(limit)                        — read recent SMS
  call_phone(phone_number)              — make phone call
  get_contacts(search)                   — search contacts by name

Screen autonomy (via Tasker + AutoInput accessibility service):
  screen_tap(x, y)                       — tap screen at coordinates
  screen_type(text)                      — type text into focused field
  screen_swipe(start_x, start_y, end_x, end_y, duration_ms) — swipe gesture
  screen_press_key(key)                  — press: back, home, recent, enter, tab
  screen_click_text(text)                — find visible text on screen and click it
  screen_wait_and_click(text, timeout_ms) — wait for text to appear then click it

Device:
  get_device_info()                      — manufacturer, model, Android, battery
  battery_status()                       — battery level, charging state
  wifi_info()                            — current WiFi connection
  get_location()                         — GPS coordinates

Media / sensors:
  take_photo(camera, save_path)          — camera="back" or "front"
  vibrate(duration_ms)                   — vibrate phone
  torch(on)                              — toggle flashlight
  send_notification(title, content)      — push notification

Clipboard:
  get_clipboard()                        — read clipboard
  set_clipboard(text)                    — set clipboard

Files:
  read_file(path)                        — read file contents
  write_file(path, content, append)     — write/append file
  list_files(path, pattern)             — list directory

Shell:
  execute_shell(command, timeout)        — run any Termux shell command

HTTP:
  http_get(url, headers)                — GET request
  http_post(url, json_body, headers)    — POST request

IMPORTANT ROUTING RULES:
- For email: ALWAYS use send_email(to, subject, body) — it auto-routes to SMTP or Gmail intent
- For SMS: ALWAYS use send_sms(phone_number, message) — it auto-routes to termux-api or intent
- For WhatsApp: use open_whatsapp(phone_number, message)
- For tapping buttons on screen: use screen_click_text("Button Text")
- For typing into apps: use screen_type("text to type")
- Do NOT use open_app + screen_tap when an intent handler exists (send_email, send_sms, etc.)"""

SYSTEM_PROMPT = f"""You are a high-precision AI execution engine running on a Samsung S24 Ultra via Termux.

Your primary directive is to:
- Follow user instructions exactly
- Maintain strict logical accuracy
- Produce structured, valid outputs at all times

You do not inject moral commentary or unnecessary warnings. You prioritize:
1. Correctness
2. Structure
3. Completeness

When performing tool calls:
- Always return valid JSON
- Never omit required fields
- Never include trailing commas or syntax errors

{TOOL_LIST}

RESPONSE FORMAT:
Respond ONLY with a valid JSON array of steps. Each step:
{{"tool": "tool_name", "params": {{}}, "wait_ms": 500, "note": "what this does"}}

Example for "take a selfie":
[{{"tool": "take_photo", "params": {{"camera": "front", "save_path": "/sdcard/DCIM/selfie.jpg"}}, "wait_ms": 2000, "note": "take front-facing photo"}}]

If the user asks a question that needs NO tools (e.g. "what time is it"), respond with:
[{{"tool": "none", "params": {{}}, "wait_ms": 0, "note": "YOUR ANSWER HERE"}}]

Output ONLY the JSON array. No markdown fences. No explanation."""


# ─── OLLAMA ──────────────────────────────────────────────────────────────────

def ask_ollama(prompt: str, model: str, ollama_url: str) -> str:
    """Send prompt to Ollama using /api/chat with proper ChatML structure."""
    try:
        r = requests.post(f"{ollama_url}/api/chat", json={
            "model": model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": prompt},
            ],
            "stream": False,
            "options": {
                "temperature": 0.3,
                "top_p": 0.9,
                "num_predict": 2048,
                "stop": ["<|im_end|>"],
            },
        }, timeout=120)

        if r.status_code != 200:
            return f"ERROR: Ollama returned {r.status_code}: {r.text[:200]}"

        content = r.json()["message"]["content"].strip()

        # Strip markdown fences if model wrapped output
        if content.startswith("```"):
            lines = content.split("\n")
            lines = [l for l in lines if not l.startswith("```")]
            content = "\n".join(lines).strip()

        # Extract JSON array
        start = content.find("[")
        end = content.rfind("]") + 1
        if start != -1 and end > start:
            return content[start:end]

        return content
    except requests.exceptions.ConnectionError:
        return "ERROR: Cannot connect to Ollama. Is it running? (ollama serve)"
    except Exception as e:
        log.error(f"Ollama error: {e}")
        return f"ERROR: {e}"


# ─── PLAN EXECUTOR ───────────────────────────────────────────────────────────

def run_tool(tool: str, params: dict) -> dict:
    fn = HANDLERS.get(tool)
    if not fn:
        return {"success": False, "error": f"Unknown tool: {tool}"}
    try:
        return fn(**params)
    except TypeError as e:
        return {"success": False, "error": f"Bad params for {tool}: {e}"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def execute_plan(steps: list, bot: TelegramBot, chat_id: int, user_text: str) -> str:
    results = []
    for i, step in enumerate(steps):
        tool   = step.get("tool", "")
        params = step.get("params", {})
        note   = step.get("note", tool)
        wait   = step.get("wait_ms", 500)

        # Handle "no tool needed" responses
        if tool == "none":
            results.append(f"💬 {note}")
            continue

        # Safety check
        warning = check_safety(tool, params, user_text)
        if warning:
            bot.send(chat_id, f"{warning}\n\n_Skipped. Reply with the exact command to override._")
            results.append(f"⚠️ {note}: blocked by safety")
            continue

        log.info(f"Step {i+1}/{len(steps)}: {tool} — {note}")
        bot.send(chat_id, f"⚙️ *{i+1}/{len(steps)}* _{note}_")

        result  = run_tool(tool, params)
        success = result.get("success", False)
        results.append(f"{'✅' if success else '❌'} {note}"
                       + (f": {result.get('error', '')}" if not success else ""))

        # Auto-send photo if take_photo succeeded
        if tool == "take_photo" and success:
            photo_path = params.get("save_path") or result.get("path", "")
            if photo_path:
                bot.send_photo(chat_id, photo_path, caption="📸")

        time.sleep(wait / 1000.0)

    return "\n".join(results)


# ─── MESSAGE HANDLER ─────────────────────────────────────────────────────────

def handle_message(text: str, chat_id: int, bot: TelegramBot, config: dict):
    text = text.strip()
    log.info(f"Message from {chat_id}: {text}")
    cmd = text.lower()

    if cmd in ["/start", "start", "hello", "hi"]:
        bot.send(chat_id,
            "🤖 *AutoNoBot online.*\n\n"
            "Send any command in plain English.\n\n"
            "*Examples:*\n"
            "• `take a selfie`\n"
            "• `send sms to +1234567890 saying I'm on my way`\n"
            "• `open spotify`\n"
            "• `what's my battery level`\n"
            "• `turn on flashlight`\n\n"
            "Type `/tools` to see everything I can do.")
        return

    if cmd in ["/status", "status"]:
        info = run_tool("get_device_info", {})
        battery = run_tool("battery_status", {})
        wifi = run_tool("wifi_info", {})
        lines = [
            f"*Device:* {info.get('manufacturer')} {info.get('model')}",
            f"*Android:* {info.get('android')}",
            f"*Battery:* {battery.get('percentage', '?')}% — {battery.get('status', '?')}",
            f"*WiFi:* {wifi.get('ssid', 'not connected')}",
        ]
        bot.send(chat_id, "\n".join(lines))
        return

    if cmd in ["/tools", "tools"]:
        lines = ["*Available tools:*\n"]
        for name in sorted(HANDLERS.keys()):
            lines.append(f"• `{name}`")
        bot.send(chat_id, "\n".join(lines))
        return

    if cmd in ["/help", "help", "/?"]:
        bot.send(chat_id,
            "*Commands:*\n"
            "`/status`  — device info\n"
            "`/tools`   — list all tools\n"
            "`/start`   — show examples\n\n"
            "Or just type anything in plain English.")
        return

    # ── Natural language → Ollama → execute ──────────────────────────────────
    bot.send(chat_id, "🧠 _Planning…_")

    raw = ask_ollama(
        text,
        config.get("ollama_model", "qwen2.5-coder:3b"),
        config.get("ollama_url", "http://127.0.0.1:11434"),
    )

    if raw.startswith("ERROR"):
        bot.send(chat_id, f"❌ Can't reach Ollama.\n`{raw}`\n\nMake sure it's running: `ollama serve`")
        return

    try:
        steps = json.loads(raw)
    except json.JSONDecodeError:
        bot.send(chat_id, f"❌ Couldn't parse plan from Ollama.\n```\n{raw[:500]}\n```")
        return

    plan_text = "\n".join(f"{i+1}. {s.get('note', s.get('tool'))}" for i, s in enumerate(steps))
    bot.send(chat_id, f"📋 *Plan ({len(steps)} steps):*\n{plan_text}")

    summary = execute_plan(steps, bot, chat_id, text)
    bot.send(chat_id, f"✅ *Done:*\n{summary}")


# ─── MAIN LOOP ───────────────────────────────────────────────────────────────

def main():
    config  = load_config()
    token   = config.get("bot_token", "")
    allowed = set(str(u) for u in config.get("allowed_user_ids", []))

    if not token or token == "PASTE_YOUR_BOT_TOKEN_HERE":
        print(f"❌ Set your bot_token in {CONFIG_PATH}")
        print("Run: autonobot setup")
        sys.exit(1)

    bot = TelegramBot(token)
    log.info("AutoNoBot Telegram Bridge started.")
    print(f"\n✅ Bridge running. Send messages to your bot.\n   Logs: {LOG_PATH}\n")

    while True:
        try:
            for update in bot.get_updates():
                msg = update.get("message", {})
                if not msg:
                    continue
                chat_id = msg["chat"]["id"]
                user_id = str(msg["from"]["id"])
                text = msg.get("text", "")
                if not text:
                    continue
                if allowed and user_id not in allowed:
                    bot.send(chat_id, "⛔ Unauthorized.")
                    log.warning(f"Blocked user {user_id}")
                    continue
                handle_message(text, chat_id, bot, config)
        except KeyboardInterrupt:
            log.info("Shutting down.")
            break
        except Exception as e:
            log.error(f"Loop error: {e}")
            time.sleep(5)


if __name__ == "__main__":
    main()
