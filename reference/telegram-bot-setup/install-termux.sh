#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

# Compatibility entrypoint for the original Termux install URL.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || true)"
if [[ -n "$SCRIPT_DIR" && -f "$SCRIPT_DIR/install.sh" ]]; then
  exec bash "$SCRIPT_DIR/install.sh" "$@"
fi

command -v curl >/dev/null 2>&1 || pkg install -y curl
tmp_file="$(mktemp "${TMPDIR:-/tmp}/telegram-bot-install.XXXXXX")"
trap 'rm -f "$tmp_file"' EXIT
curl -fsSL "https://raw.githubusercontent.com/ether4o4/Telegram-bot-setup/main/install.sh" -o "$tmp_file"
bash "$tmp_file" "$@"
