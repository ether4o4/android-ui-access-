# Configuration

Run setup from the repository root:

```bash
.venv/bin/python -m src.setup_env
```

On Windows, replace `.venv/bin/python` with `.venv\Scripts\python.exe`.

## Required value

`TELEGRAM_BOT_TOKEN` is the only value required for the polling bot. Create a bot with Telegram's `@BotFather`, copy the token exactly, and keep it private. Setup checks the token's structure before saving it.

`TELEGRAM_CHAT_ID` is optional. Leave it blank for the first launch, send `/id` to the bot, then rerun setup and enter the returned integer. Once set, the bot ignores every other chat.

## AI selection

`AI_PROVIDERS` is a comma-separated list of provider slugs. `AI_PROVIDER` chooses the default from that list, and `AI_MODEL` optionally records the model to use.

```dotenv
AI_PROVIDERS=groq,ollama
AI_PROVIDER=groq
AI_MODEL=llama-3.3-70b-versatile
GROQ_API_KEY=replace_me
OLLAMA_URL=http://127.0.0.1:11434
```

Selecting a provider makes its required key or endpoint mandatory. Unselected providers may remain blank. Local endpoints must use a complete `http://` or `https://` URL.

Use `python -m src.setup_env --list-providers` for the canonical slugs. To add a provider, add one `ProviderSpec` in `src/providers.py`; the prompt, validation, generated `.env`, status output, and provider list all use the same registry.

## Optional GitHub values

- `GITHUB_TOKEN`: a fine-grained personal access token with only the permissions the bot needs.
- `GITHUB_USERNAME`: the account that owns or accesses the target repository.
- `GITHUB_REPO`: `owner/repository` format.

GitHub values are stored for future bot features; the starter commands do not mutate repositories.

## Webhook values

The starter runs in polling mode. `WEBHOOK_PORT`, `WEBHOOK_PATH`, `CLOUDFLARED_TOKEN`, and `PUBLIC_BASE_URL` are reserved for a future webhook deployment and may stay at their defaults or blank.

## Non-interactive setup

Environment variables override values loaded from `.env`. This makes unattended setup possible without putting credentials in command history:

```bash
export TELEGRAM_BOT_TOKEN='...'
export GROQ_API_KEY='...'
./install.sh --provider groq --non-interactive
```

The command exits nonzero and writes nothing if a required value is absent or malformed.

## Validation rules

- Bot token must have the standard numeric-ID and secret format.
- Chat ID must be an integer; Telegram group IDs can be negative.
- GitHub repository must use `owner/repository` format.
- Ports must be between 1 and 65535.
- Webhook paths must start with `/`.
- URLs must include `http://` or `https://` and a host.
- Default provider must be included in the selected provider list.
- Every selected provider must have all of its required values.
