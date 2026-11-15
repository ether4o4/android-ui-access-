# Telegram Bot Setup

A one-command Telegram bot starter for **Termux, Linux, and Windows PowerShell**. The installer creates an isolated Python environment, collects only the AI providers you select, validates the complete configuration, and refuses to save invalid settings.

## Install

### Termux

Install Termux from F-Droid, then run:

```bash
pkg install -y curl
bash <(curl -fsSL https://raw.githubusercontent.com/ether4o4/Telegram-bot-setup/main/install-termux.sh)
```

### Linux

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/ether4o4/Telegram-bot-setup/main/install.sh)
```

### Windows PowerShell

```powershell
irm https://raw.githubusercontent.com/ether4o4/Telegram-bot-setup/main/install.ps1 | iex
```

Windows requires Git and Python 3.10 or newer. The installer reports exact download links if either is missing.

The setup asks for the required BotFather token, an optional allowed chat ID, and only the provider credentials you choose. Secrets are hidden while entered. Start the finished bot with:

```bash
cd ~/Telegram-bot-setup
./start.sh
```

On Windows:

```powershell
cd $HOME\Telegram-bot-setup
.\start.ps1
```

## Provider-aware install

Choose providers from the command line instead of the menu:

```bash
./install.sh --provider openai --provider groq --provider ollama
```

List every supported provider:

```bash
.venv/bin/python -m src.setup_env --list-providers
```

The provider registry currently covers OpenAI, Anthropic, xAI, Gemini, Skywork, OpenRouter, Groq, DeepSeek, Mistral, Together, Fireworks, Cohere, Perplexity, Hugging Face, Replicate, Stability, ElevenLabs, Runway, Fal.ai, AnythingLLM, Azure OpenAI, Ollama, LM Studio, and LocalAI.

## Validate or reconfigure

Validation never makes changes and never prints secret values:

```bash
.venv/bin/python -m src.setup_env --validate-only
```

Reconfigure interactively:

```bash
.venv/bin/python -m src.setup_env
```

Existing secrets remain in place when you press Enter. Before a valid replacement is saved, the old `.env` is copied to a timestamped backup. Unknown custom variables are preserved.

For unattended installs, export the required variables first:

```bash
export TELEGRAM_BOT_TOKEN='123456789:replace_with_real_botfather_token'
export OPENAI_API_KEY='replace_me'
bash install.sh --provider openai --non-interactive
```

See [Configuration](docs/CONFIGURATION.md) for every rule and [Troubleshooting](docs/TROUBLESHOOTING.md) for common Termux, Windows, token, and provider errors.

## Bot commands

| Command | Purpose |
|---|---|
| `/start` | Show that the bot is online |
| `/id` | Show the current Telegram chat ID |
| `/status` | Show configuration health |
| `/providers` | Show configured providers with masked credentials |
| `/help` | Show the command list |

If `TELEGRAM_CHAT_ID` is configured, commands from every other chat are ignored.

## Safety defaults

- `.env` and timestamped backups are excluded from Git.
- Invalid configuration is not written.
- Existing dirty checkouts are not pulled over by the installer.
- Telegram polling is used by default, so no public port is required.
- Provider selection and provider credentials must agree before startup.
