# AutoNoBot

Autonomous AI phone agent. Samsung S24 Ultra + Termux + Ollama + Telegram.

Send plain English → AI plans → phone executes.

## Install

```bash
git clone https://github.com/ether4o4/AutoNoBot.git
cd AutoNoBot
chmod +x install.sh
./install.sh
```

One script. Pulls the model, bakes the system prompt in, installs 23 skills, sets up everything.

## What You Can Say

```
"open gmail and send john@example.com saying I'll be late"
"take a selfie"
"set an alarm for 7:30am"
"send a whatsapp to +1234567890 saying on my way"
"what's my battery level"
"turn on flashlight"
"open spotify"
"text mom saying I love you"
```

Just talk to it normally. It figures out the tools.

## Requirements

- **Termux** (F-Droid version)
- **Termux:API** app (F-Droid) + `pkg install termux-api`
- **Ollama** running with model pulled
- **Telegram bot token** from @BotFather

### For Full Screen Autonomy (optional but recommended)
- **Tasker** (Play Store, $3.49)
- **AutoInput** (Play Store, Tasker plugin)
- **Termux:Tasker** (F-Droid)
- See [TASKER_SETUP.md](TASKER_SETUP.md) for 5-minute setup

## How It Works

```
You (Telegram) → Bot Bridge → Ollama (Qwen Q4_K_M) → JSON Plan → Handlers → Phone

Three execution layers:
  1. Android Intents     — open apps, compose emails, set alarms (no screen touch needed)
  2. Termux:API          — SMS, camera, clipboard, contacts, GPS, notifications
  3. Tasker + AutoInput  — tap buttons, type text, swipe (Accessibility Service bypasses Knox)
```

### Smart Routing

When you say "send john an email":
1. **SMTP** — sends directly, no screen interaction (if email_config.json is set up)
2. **Gmail Intent** — opens Gmail pre-filled with to/subject/body
3. **AutoInput** — auto-taps the Send button

It tries the most autonomous method first, falls back gracefully.

## Custom Model (Modelfile)

The install script creates a custom Ollama model called `autonobot` with:
- System prompt baked into the model weights
- ChatML template with proper `<|im_start|>` / `<|im_end|>` tokens
- Temperature 0.3 for reliable JSON tool-calling
- Stop token configured

```bash
# Manual creation if needed:
cd ~/.autonobot
ollama create autonobot -f Modelfile
```

## After Install

```bash
source ~/.bashrc

# Set Telegram token + user ID:
nano ~/.autonobot/telegram_config.json

# Start:
bot-start
```

## 36 Tools

| Category | Tools |
|----------|-------|
| **App Control** | open_app, list_apps, open_url, share_text, open_map |
| **Scheduling** | set_alarm, set_timer, create_event |
| **Communication** | send_email, send_sms, read_sms, call_phone, get_contacts, open_whatsapp |
| **Screen Autonomy** | screen_tap, screen_type, screen_swipe, screen_press_key, screen_click_text, screen_wait_and_click |
| **Device** | get_device_info, battery_status, wifi_info, get_location |
| **Media** | take_photo, vibrate, torch, send_notification |
| **Clipboard** | get_clipboard, set_clipboard |
| **Files** | read_file, write_file, list_files |
| **Shell** | execute_shell |
| **HTTP** | http_get, http_post |

## 23 Skills

**Development:** Software Engineer, Frontend Engineer, DevOps, Code Reviewer, NestJS, Solution Architect

**Quality:** QA Engineer, Product Manager, Technical Writer, SRE

**Intelligence:** Data Scientist, OSINT Researcher, AI Hunter Pro

**Creative:** Music Producer, Songwriting & Composition, Video Producer

**Automation:** N8N Workflow, AI Agent Tools, Claw Sync, AI Prompt Generator, Adaptive Reasoning

**General:** Polymath, Security Engineer

## Safety

Auto-executes safe tasks. Blocks and warns on:
- File deletion
- Financial/crypto operations
- System/root commands
- Account deletion
- Mass communication (10+ recipients)

## Commands

| Alias | What |
|-------|------|
| `bot-start` | Start Telegram bridge |
| `bot-status` | Check config & deps |
| `bot-skills` | List installed skills |
| `bot-logs` | Tail bridge logs |
| `bot setup` | Interactive setup wizard |

## Config Files

```
~/.autonobot/
├── telegram_config.json    # Bot token + user ID
├── config.yaml             # LLM + safety settings
├── safety_rules.json       # Dangerous operation patterns
├── email_config.json       # Optional: SMTP for autonomous email
├── Modelfile               # Ollama model definition
├── tasker_cmd.json         # Tasker bridge (auto-managed)
├── skills/                 # 23 AI skills
└── logs/bridge.log
```

## License

MIT
