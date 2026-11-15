#!/usr/bin/env python3
"""
AutoNoBot Tool Handlers for Termux — Samsung S24 Ultra
────────────────────────────────────────────────────────
Three execution layers:
  1. Android Intents (am start) — open apps, pre-fill compose screens
  2. Termux:API — SMS, camera, clipboard, contacts, notifications, etc.
  3. Tasker/AutoInput bridge — full screen autonomy (tap, type, swipe)
  4. Direct — SMTP email, HTTP, file ops, shell

Install requirements:
  pkg install termux-api termux-tools
  Also: Termux:API app, Termux:Tasker app, Tasker app, AutoInput plugin
"""

import json
import subprocess
import sys
import smtplib
import time
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from pathlib import Path
from typing import Any, Dict, List
from datetime import datetime

CONFIG_DIR = Path.home() / ".autonobot"


# ═══════════════════════════════════════════════════════════════════════════════
# HELPERS
# ═══════════════════════════════════════════════════════════════════════════════

def _run(cmd: List[str], timeout: int = 15, input_text: str = None) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, input=input_text)


def _shell(cmd: str, timeout: int = 30) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)


def _termux_api_ok() -> bool:
    return _run(["which", "termux-sms-send"]).returncode == 0


def _tasker_ok() -> bool:
    """Check if Termux:Tasker can send commands."""
    return _run(["which", "termux-tasker"]).returncode == 0 or \
           Path("/data/data/com.termux.tasker").exists()


def _timestamp() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


# ═══════════════════════════════════════════════════════════════════════════════
# LAYER 1: ANDROID INTENTS — zero-tap actions via am start
# ═══════════════════════════════════════════════════════════════════════════════

KNOWN_APPS = {
    "snapchat":    "com.snapchat.android",
    "instagram":   "com.instagram.android",
    "whatsapp":    "com.whatsapp",
    "twitter":     "com.twitter.android",
    "x":           "com.twitter.android",
    "tiktok":      "com.zhiliaoapp.musically",
    "youtube":     "com.google.android.youtube",
    "chrome":      "com.android.chrome",
    "gmail":       "com.google.android.gm",
    "spotify":     "com.spotify.music",
    "telegram":    "org.telegram.messenger",
    "discord":     "com.discord",
    "reddit":      "com.reddit.frontpage",
    "netflix":     "com.netflix.mediaclient",
    "maps":        "com.google.android.apps.maps",
    "camera":      "com.sec.android.app.camera",
    "gallery":     "com.sec.android.gallery3d",
    "settings":    "com.android.settings",
    "calculator":  "com.sec.android.app.popupcalculator",
    "clock":       "com.sec.android.app.clockpackage",
    "contacts":    "com.samsung.android.contacts",
    "phone":       "com.samsung.android.dialer",
    "messages":    "com.samsung.android.messaging",
    "files":       "com.sec.android.app.myfiles",
    "samsung notes": "com.samsung.android.app.notes",
    "calendar":    "com.samsung.android.calendar",
    "play store":  "com.android.vending",
    "google maps": "com.google.android.apps.maps",
}


def handle_open_app(app_identifier: str) -> Dict[str, Any]:
    """Launch any app by name or package."""
    try:
        pkg = KNOWN_APPS.get(app_identifier.lower(), app_identifier)
        r = _run(["am", "start", "-a", "android.intent.action.MAIN",
                   "-c", "android.intent.category.LAUNCHER", "-p", pkg], timeout=8)
        if r.returncode == 0 or "Starting" in r.stdout:
            return {"success": True, "package": pkg}
        r2 = _run(["am", "start", pkg], timeout=8)
        if r2.returncode == 0:
            return {"success": True, "package": pkg}
        return {"success": False, "error": r.stderr.strip() or r2.stderr.strip(),
                "tip": "Run list_apps to find the exact package name"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_list_apps(filter_str: str = None) -> Dict[str, Any]:
    """List installed user apps."""
    try:
        r = _run(["pm", "list", "packages", "-3"], timeout=15)
        packages = [l.replace("package:", "").strip()
                    for l in r.stdout.splitlines() if l.startswith("package:")]
        if filter_str:
            packages = [p for p in packages if filter_str.lower() in p.lower()]
        return {"success": True, "count": len(packages), "apps": packages}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_send_intent_email(to: str, subject: str = "", body: str = "") -> Dict[str, Any]:
    """Open Gmail compose screen pre-filled. AutoInput can tap Send."""
    try:
        cmd = [
            "am", "start",
            "-a", "android.intent.action.SENDTO",
            "-d", f"mailto:{to}",
            "--es", "android.intent.extra.SUBJECT", subject,
            "--es", "android.intent.extra.TEXT", body,
        ]
        r = _run(cmd, timeout=8)
        success = r.returncode == 0 or "Starting" in r.stdout
        result = {"success": success, "to": to, "subject": subject, "method": "intent"}
        # Auto-tap send if Tasker is available
        if success:
            tap = _tasker_autoinput_tap_send()
            if tap.get("success"):
                result["auto_sent"] = True
        return result
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_send_intent_sms(phone_number: str, message: str = "") -> Dict[str, Any]:
    """Open SMS compose pre-filled via intent."""
    try:
        cmd = [
            "am", "start",
            "-a", "android.intent.action.SENDTO",
            "-d", f"smsto:{phone_number}",
            "--es", "sms_body", message,
        ]
        r = _run(cmd, timeout=8)
        return {"success": r.returncode == 0 or "Starting" in r.stdout,
                "to": phone_number, "method": "intent"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_open_url(url: str) -> Dict[str, Any]:
    """Open any URL in the default browser."""
    try:
        cmd = ["am", "start", "-a", "android.intent.action.VIEW", "-d", url]
        r = _run(cmd, timeout=8)
        return {"success": r.returncode == 0 or "Starting" in r.stdout, "url": url}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_call_phone(phone_number: str) -> Dict[str, Any]:
    """Open dialer with number pre-filled (intent) or direct call via termux-api."""
    try:
        # Try termux-api first (direct call)
        r = _run(["termux-telephony-call", phone_number], timeout=8)
        if r.returncode == 0:
            return {"success": True, "number": phone_number, "method": "termux-api"}
        # Fallback to intent (opens dialer)
        cmd = ["am", "start", "-a", "android.intent.action.DIAL", "-d", f"tel:{phone_number}"]
        r2 = _run(cmd, timeout=8)
        return {"success": r2.returncode == 0, "number": phone_number, "method": "intent"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_open_whatsapp_chat(phone_number: str, message: str = "") -> Dict[str, Any]:
    """Open WhatsApp chat to specific number with optional pre-filled message."""
    try:
        # WhatsApp deep link — works without contacts saved
        import urllib.parse
        encoded_msg = urllib.parse.quote(message)
        # Strip + and spaces from number
        clean_num = phone_number.replace("+", "").replace(" ", "").replace("-", "")
        url = f"https://wa.me/{clean_num}"
        if message:
            url += f"?text={encoded_msg}"
        cmd = ["am", "start", "-a", "android.intent.action.VIEW", "-d", url]
        r = _run(cmd, timeout=8)
        return {"success": r.returncode == 0 or "Starting" in r.stdout,
                "number": phone_number, "method": "whatsapp_deeplink"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_share_text(text: str, app: str = None) -> Dict[str, Any]:
    """Share text via Android share sheet or directly to a specific app."""
    try:
        cmd = [
            "am", "start",
            "-a", "android.intent.action.SEND",
            "-t", "text/plain",
            "--es", "android.intent.extra.TEXT", text,
        ]
        if app:
            pkg = KNOWN_APPS.get(app.lower(), app)
            cmd += ["-p", pkg]
        r = _run(cmd, timeout=8)
        return {"success": r.returncode == 0 or "Starting" in r.stdout,
                "text": text[:100], "method": "share_intent"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_open_map_location(query: str) -> Dict[str, Any]:
    """Open Google Maps with a search query or address."""
    try:
        import urllib.parse
        encoded = urllib.parse.quote(query)
        cmd = ["am", "start", "-a", "android.intent.action.VIEW",
               "-d", f"geo:0,0?q={encoded}"]
        r = _run(cmd, timeout=8)
        return {"success": r.returncode == 0 or "Starting" in r.stdout, "query": query}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_set_alarm(hour: int, minute: int, message: str = "Alarm") -> Dict[str, Any]:
    """Set an alarm via Android intent."""
    try:
        cmd = [
            "am", "start",
            "-a", "android.intent.action.SET_ALARM",
            "--ei", "android.intent.extra.alarm.HOUR", str(hour),
            "--ei", "android.intent.extra.alarm.MINUTES", str(minute),
            "--es", "android.intent.extra.alarm.MESSAGE", message,
            "--ez", "android.intent.extra.alarm.SKIP_UI", "true",
        ]
        r = _run(cmd, timeout=8)
        return {"success": r.returncode == 0 or "Starting" in r.stdout,
                "time": f"{hour:02d}:{minute:02d}", "message": message}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_set_timer(seconds: int, message: str = "Timer") -> Dict[str, Any]:
    """Set a countdown timer via Android intent."""
    try:
        cmd = [
            "am", "start",
            "-a", "android.intent.action.SET_TIMER",
            "--ei", "android.intent.extra.alarm.LENGTH", str(seconds),
            "--es", "android.intent.extra.alarm.MESSAGE", message,
            "--ez", "android.intent.extra.alarm.SKIP_UI", "true",
        ]
        r = _run(cmd, timeout=8)
        return {"success": r.returncode == 0 or "Starting" in r.stdout,
                "seconds": seconds, "message": message}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_create_calendar_event(title: str, description: str = "",
                                  begin_time: int = None) -> Dict[str, Any]:
    """Create calendar event via intent."""
    try:
        cmd = [
            "am", "start",
            "-a", "android.intent.action.INSERT",
            "-d", "content://com.android.calendar/events",
            "--es", "title", title,
            "--es", "description", description,
        ]
        if begin_time:
            cmd += ["--el", "beginTime", str(begin_time)]
        r = _run(cmd, timeout=8)
        return {"success": r.returncode == 0 or "Starting" in r.stdout, "title": title}
    except Exception as e:
        return {"success": False, "error": str(e)}


# ═══════════════════════════════════════════════════════════════════════════════
# LAYER 2: TASKER / AUTOINPUT BRIDGE — full screen autonomy
# ═══════════════════════════════════════════════════════════════════════════════
#
# How it works:
#   Termux → writes command to ~/.autonobot/tasker_cmd.json
#   Tasker monitors that file → reads command → executes via AutoInput
#   AutoInput performs tap/type/swipe as an Accessibility Service
#   Knox does NOT block Accessibility Services.
#
# Setup (one time in Tasker):
#   1. Create Profile: Event → File Modified → ~/.autonobot/tasker_cmd.json
#   2. Create Task that reads the JSON and routes to AutoInput actions
#   3. See TASKER_SETUP.md for the full Tasker XML import

TASKER_CMD_FILE = CONFIG_DIR / "tasker_cmd.json"
TASKER_RESULT_FILE = CONFIG_DIR / "tasker_result.json"


def _send_tasker_command(action: str, params: dict, wait_result: bool = True,
                         timeout: int = 10) -> Dict[str, Any]:
    """Send a command to Tasker via file bridge."""
    try:
        TASKER_CMD_FILE.parent.mkdir(parents=True, exist_ok=True)

        # Clear previous result
        if TASKER_RESULT_FILE.exists():
            TASKER_RESULT_FILE.unlink()

        # Write command
        cmd = {"action": action, "params": params, "timestamp": time.time()}
        TASKER_CMD_FILE.write_text(json.dumps(cmd))

        if not wait_result:
            return {"success": True, "method": "tasker", "action": action}

        # Wait for Tasker to write result
        deadline = time.time() + timeout
        while time.time() < deadline:
            if TASKER_RESULT_FILE.exists():
                result = json.loads(TASKER_RESULT_FILE.read_text())
                TASKER_RESULT_FILE.unlink()
                return {"success": True, "method": "tasker", **result}
            time.sleep(0.3)

        return {"success": True, "method": "tasker", "note": "command sent, no confirmation received",
                "action": action}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_screen_tap(x: int, y: int) -> Dict[str, Any]:
    """Tap screen at coordinates via AutoInput (Accessibility Service)."""
    return _send_tasker_command("tap", {"x": x, "y": y})


def handle_screen_type(text: str) -> Dict[str, Any]:
    """Type text into focused field via AutoInput."""
    return _send_tasker_command("type", {"text": text})


def handle_screen_swipe(start_x: int, start_y: int,
                         end_x: int, end_y: int, duration_ms: int = 300) -> Dict[str, Any]:
    """Swipe on screen via AutoInput."""
    return _send_tasker_command("swipe", {
        "startX": start_x, "startY": start_y,
        "endX": end_x, "endY": end_y, "duration": duration_ms
    })


def handle_screen_press_key(key: str) -> Dict[str, Any]:
    """Press a system key via AutoInput. Keys: back, home, recent, enter, tab."""
    return _send_tasker_command("key", {"key": key})


def handle_screen_click_text(text: str) -> Dict[str, Any]:
    """Click on visible text on screen via AutoInput UI Query."""
    return _send_tasker_command("click_text", {"text": text})


def handle_screen_wait_and_click(text: str, timeout_ms: int = 5000) -> Dict[str, Any]:
    """Wait for text to appear on screen then click it."""
    return _send_tasker_command("wait_click", {"text": text, "timeout": timeout_ms})


def _tasker_autoinput_tap_send() -> Dict[str, Any]:
    """Helper: try to tap the Send button in email/SMS compose screens."""
    # Give the compose screen a moment to load
    time.sleep(1.5)
    return _send_tasker_command("click_text", {"text": "Send"}, wait_result=True, timeout=5)


# ═══════════════════════════════════════════════════════════════════════════════
# LAYER 3: TERMUX-API — direct device access
# ═══════════════════════════════════════════════════════════════════════════════

def handle_get_device_info() -> Dict[str, Any]:
    try:
        props = {"manufacturer": "ro.build.manufacturer", "model": "ro.build.model",
                 "android": "ro.build.version.release", "sdk": "ro.build.version.sdk"}
        info = {}
        for key, prop in props.items():
            r = _run(["getprop", prop], timeout=5)
            info[key] = r.stdout.strip()
        if _termux_api_ok():
            rb = _run(["termux-battery-status"], timeout=8)
            try:
                info["battery"] = json.loads(rb.stdout)
            except Exception:
                pass
        return {"success": True, **info}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_battery_status() -> Dict[str, Any]:
    try:
        r = _run(["termux-battery-status"], timeout=8)
        return {"success": True, **json.loads(r.stdout)}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_wifi_info() -> Dict[str, Any]:
    try:
        r = _run(["termux-wifi-connectioninfo"], timeout=8)
        return {"success": True, **json.loads(r.stdout)}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_get_location() -> Dict[str, Any]:
    try:
        r = _run(["termux-location"], timeout=30)
        return {"success": True, **json.loads(r.stdout)}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_send_sms(phone_number: str, message: str) -> Dict[str, Any]:
    """Send SMS — tries termux-api first, falls back to intent."""
    try:
        r = _run(["termux-sms-send", "-n", phone_number, message], timeout=15)
        if r.returncode == 0:
            return {"success": True, "to": phone_number, "method": "termux-api"}
        # Fallback: intent
        return handle_send_intent_sms(phone_number, message)
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_read_sms(limit: int = 10) -> Dict[str, Any]:
    try:
        r = _run(["termux-sms-list", "-l", str(limit)], timeout=15)
        data = json.loads(r.stdout)
        return {"success": True, "count": len(data), "messages": data}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_send_email(to: str, subject: str, body: str,
                      body_type: str = "plain", cc: str = None) -> Dict[str, Any]:
    """Send email — tries SMTP first (fully autonomous), falls back to intent."""
    try:
        cfg_path = CONFIG_DIR / "email_config.json"
        if cfg_path.exists():
            cfg = json.loads(cfg_path.read_text())
            msg = MIMEMultipart("alternative")
            msg["Subject"] = subject
            msg["From"] = cfg["sender_email"]
            msg["To"] = to
            if cc:
                msg["CC"] = cc
            msg.attach(MIMEText(body, body_type))
            with smtplib.SMTP(cfg.get("smtp_server", "smtp.gmail.com"),
                              int(cfg.get("smtp_port", 587))) as s:
                s.starttls()
                s.login(cfg["sender_email"], cfg["app_password"])
                s.send_message(msg)
            return {"success": True, "to": to, "subject": subject, "method": "smtp"}
        else:
            # No SMTP config — use intent (opens Gmail pre-filled)
            return handle_send_intent_email(to, subject, body)
    except Exception as e:
        # SMTP failed — fallback to intent
        return handle_send_intent_email(to, subject, body)


def handle_get_contacts(search: str = None) -> Dict[str, Any]:
    try:
        r = _run(["termux-contact-list"], timeout=15)
        contacts = json.loads(r.stdout)
        if search:
            contacts = [c for c in contacts if search.lower() in c.get("name", "").lower()]
        return {"success": True, "count": len(contacts), "contacts": contacts[:50]}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_take_photo(camera: str = "back", save_path: str = None) -> Dict[str, Any]:
    try:
        if not save_path:
            save_path = str(Path("/sdcard/DCIM/autonobot") / f"photo_{_timestamp()}.jpg")
        Path(save_path).parent.mkdir(parents=True, exist_ok=True)
        cam_id = "0" if camera == "back" else "1"
        r = _run(["termux-camera-photo", "-c", cam_id, save_path], timeout=20)
        if r.returncode == 0 and Path(save_path).exists():
            return {"success": True, "path": save_path, "camera": camera}
        return {"success": False, "error": r.stderr.strip() or "Camera failed"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_vibrate(duration_ms: int = 500) -> Dict[str, Any]:
    try:
        r = _run(["termux-vibrate", "-d", str(duration_ms)], timeout=8)
        return {"success": r.returncode == 0, "duration_ms": duration_ms}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_torch(on: bool = True) -> Dict[str, Any]:
    try:
        state = "on" if on else "off"
        r = _run(["termux-torch", state], timeout=8)
        return {"success": r.returncode == 0, "state": state}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_send_notification(title: str, content: str, sound: bool = False) -> Dict[str, Any]:
    try:
        cmd = ["termux-notification", "--title", title, "--content", content]
        if sound:
            cmd += ["--sound"]
        r = _run(cmd, timeout=10)
        return {"success": r.returncode == 0, "title": title}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_get_clipboard() -> Dict[str, Any]:
    try:
        r = _run(["termux-clipboard-get"], timeout=8)
        return {"success": True, "content": r.stdout.strip()}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_set_clipboard(text: str) -> Dict[str, Any]:
    try:
        r = _run(["termux-clipboard-set"], timeout=8, input_text=text)
        return {"success": r.returncode == 0, "text": text}
    except Exception as e:
        return {"success": False, "error": str(e)}


# ═══════════════════════════════════════════════════════════════════════════════
# LAYER 4: FILE SYSTEM / SHELL / HTTP
# ═══════════════════════════════════════════════════════════════════════════════

def handle_read_file(path: str) -> Dict[str, Any]:
    try:
        content = Path(path).read_text(encoding="utf-8")
        return {"success": True, "path": path, "content": content, "lines": content.count("\n") + 1}
    except FileNotFoundError:
        return {"success": False, "error": f"File not found: {path}"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_write_file(path: str, content: str, append: bool = False) -> Dict[str, Any]:
    try:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        if append:
            with p.open("a") as f:
                f.write(content)
        else:
            p.write_text(content, encoding="utf-8")
        return {"success": True, "path": path, "bytes": len(content)}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_list_files(path: str = "/sdcard", pattern: str = "*") -> Dict[str, Any]:
    try:
        p = Path(path)
        files = [str(f) for f in p.glob(pattern)]
        return {"success": True, "path": path, "count": len(files), "files": files[:100]}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_execute_shell(command: str, timeout: int = 30) -> Dict[str, Any]:
    try:
        r = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=timeout)
        return {"success": r.returncode == 0, "stdout": r.stdout.strip(),
                "stderr": r.stderr.strip(), "returncode": r.returncode}
    except subprocess.TimeoutExpired:
        return {"success": False, "error": f"Timed out after {timeout}s"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_http_get(url: str, headers: Dict = None, timeout: int = 15) -> Dict[str, Any]:
    try:
        import requests
        r = requests.get(url, headers=headers or {}, timeout=timeout)
        return {"success": True, "status_code": r.status_code, "content": r.text[:4000]}
    except Exception as e:
        return {"success": False, "error": str(e)}


def handle_http_post(url: str, data: Dict = None, json_body: Dict = None,
                     headers: Dict = None, timeout: int = 15) -> Dict[str, Any]:
    try:
        import requests
        r = requests.post(url, data=data, json=json_body, headers=headers or {}, timeout=timeout)
        return {"success": True, "status_code": r.status_code, "content": r.text[:4000]}
    except Exception as e:
        return {"success": False, "error": str(e)}


# ═══════════════════════════════════════════════════════════════════════════════
# DISPATCHER
# ═══════════════════════════════════════════════════════════════════════════════

HANDLERS = {
    # App control & intents
    "open_app":             handle_open_app,
    "list_apps":            handle_list_apps,
    "open_url":             handle_open_url,
    "share_text":           handle_share_text,
    "open_map":             handle_open_map_location,
    "set_alarm":            handle_set_alarm,
    "set_timer":            handle_set_timer,
    "create_event":         handle_create_calendar_event,
    "open_whatsapp":        handle_open_whatsapp_chat,
    # Communication (auto-routing: SMTP → intent → tasker)
    "send_email":           handle_send_email,
    "send_sms":             handle_send_sms,
    "read_sms":             handle_read_sms,
    "call_phone":           handle_call_phone,
    "get_contacts":         handle_get_contacts,
    # Screen autonomy (Tasker/AutoInput)
    "screen_tap":           handle_screen_tap,
    "screen_type":          handle_screen_type,
    "screen_swipe":         handle_screen_swipe,
    "screen_press_key":     handle_screen_press_key,
    "screen_click_text":    handle_screen_click_text,
    "screen_wait_and_click": handle_screen_wait_and_click,
    # Device info
    "get_device_info":      handle_get_device_info,
    "battery_status":       handle_battery_status,
    "wifi_info":            handle_wifi_info,
    "get_location":         handle_get_location,
    # Media / sensors
    "take_photo":           handle_take_photo,
    "vibrate":              handle_vibrate,
    "torch":                handle_torch,
    "send_notification":    handle_send_notification,
    # Clipboard
    "get_clipboard":        handle_get_clipboard,
    "set_clipboard":        handle_set_clipboard,
    # File system
    "read_file":            handle_read_file,
    "write_file":           handle_write_file,
    "list_files":           handle_list_files,
    # Shell
    "execute_shell":        handle_execute_shell,
    # HTTP
    "http_get":             handle_http_get,
    "http_post":            handle_http_post,
}

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: handlers.py <tool_name> [json_args]"}))
        sys.exit(1)
    tool = sys.argv[1]
    args = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
    if tool == "list_tools":
        print(json.dumps({"tools": sorted(HANDLERS.keys())}))
        sys.exit(0)
    fn = HANDLERS.get(tool)
    if not fn:
        print(json.dumps({"error": f"Unknown tool: {tool}", "available": sorted(HANDLERS.keys())}))
        sys.exit(1)
    print(json.dumps(fn(**args), indent=2, default=str))
