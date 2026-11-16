# Troubleshooting

## Check configuration first

```bash
.venv/bin/python -m src.setup_env --validate-only
```

This reports field names and corrective actions without printing credentials.

## Bot token rejected

Copy the complete token from Telegram's `@BotFather`. Do not include quotes, spaces, the bot username, or the `/start` command. If the token was posted publicly, revoke it with BotFather and create a replacement.

## Bot starts but ignores commands

If `TELEGRAM_CHAT_ID` is set, only that exact chat can use commands. Group chat IDs are usually negative. Temporarily clear the value, restart, send `/id`, and save the returned value.

## Termux package errors

Use the current F-Droid Termux release. In Termux, run `termux-change-repo`, choose a working main repository, then retry the installer. The installer deliberately does not run a full `pkg upgrade`.

## Python virtual environment fails on Linux

Install the operating system's venv package, usually `python3-venv`, remove the incomplete `.venv`, and rerun `./install.sh`.

## PowerShell blocks start.ps1

Run the script for the current process without changing the machine-wide policy:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\start.ps1
```

## Existing checkout is not updated

The installer will not pull when the checkout has uncommitted changes. Review with `git status`, commit or stash your work, then rerun the installer. It does not delete local work.

## Provider marked incomplete

The slug in `AI_PROVIDERS` activates validation for that provider. Add every required field or remove the slug. Run `python -m src.setup_env --list-providers` to check spelling.

## Local provider cannot connect

Ollama, LM Studio, AnythingLLM, and LocalAI must already be running. On another device, replace `127.0.0.1` with that device's reachable LAN address and confirm its firewall allows the port. An endpoint URL alone checks configuration shape; it does not prove the server is online.
