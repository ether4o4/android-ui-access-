#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="${REPO_URL:-https://github.com/ether4o4/Telegram-bot-setup.git}"
APP_DIR="${APP_DIR:-$HOME/Telegram-bot-setup}"
SKIP_SYSTEM_UPDATE=0
NON_INTERACTIVE=0
RECONFIGURE=0
SETUP_ARGS=()

usage() {
  cat <<'EOF'
Telegram-bot-setup installer

Usage: install.sh [options]
  --app-dir PATH          Install location (default: ~/Telegram-bot-setup)
  --repo-url URL          Alternate Git repository
  --provider NAME         Configure one provider; repeat or use commas
  --all-providers         Configure every supported provider
  --reconfigure           Re-run configuration when .env already exists
  --non-interactive       Read all values from environment variables
  --skip-system-update    Skip package index refresh
  -h, --help              Show this help

Example:
  ./install.sh --provider openai --provider groq
EOF
}

while (($#)); do
  case "$1" in
    --app-dir) APP_DIR="${2:?--app-dir requires a path}"; shift 2 ;;
    --repo-url) REPO_URL="${2:?--repo-url requires a URL}"; shift 2 ;;
    --provider) SETUP_ARGS+=(--provider "${2:?--provider requires a name}"); shift 2 ;;
    --all-providers) SETUP_ARGS+=(--all-providers); shift ;;
    --reconfigure) RECONFIGURE=1; shift ;;
    --non-interactive) NON_INTERACTIVE=1; SETUP_ARGS+=(--non-interactive); shift ;;
    --skip-system-update) SKIP_SYSTEM_UPDATE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "[error] Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

info() { printf '[+] %s\n' "$*"; }
warn() { printf '[!] %s\n' "$*" >&2; }
die() { printf '[error] %s\n' "$*" >&2; exit 1; }

install_dependencies() {
  if command -v pkg >/dev/null 2>&1 && [[ -n "${PREFIX:-}" ]]; then
    info "Preparing Termux packages"
    ((SKIP_SYSTEM_UPDATE)) || pkg update -y
    pkg install -y python git curl
  elif ! command -v python3 >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1; then
    if command -v apt-get >/dev/null 2>&1; then
      local elevate=()
      [[ "$(id -u)" == 0 ]] || elevate=(sudo)
      info "Installing Python, Git, and virtual environment support"
      ((SKIP_SYSTEM_UPDATE)) || "${elevate[@]}" apt-get update
      "${elevate[@]}" apt-get install -y python3 python3-venv git curl
    else
      die "Python 3.10+ and Git are required. Install them, then run this command again."
    fi
  fi
}

python_command() {
  if command -v python >/dev/null 2>&1; then printf '%s' python; else printf '%s' python3; fi
}

sync_source() {
  if [[ -d "$APP_DIR/.git" ]]; then
    if [[ -n "$(git -C "$APP_DIR" status --porcelain)" ]]; then
      warn "Existing checkout has uncommitted changes; leaving source untouched."
    else
      info "Updating existing checkout"
      git -C "$APP_DIR" pull --ff-only
    fi
  elif [[ -e "$APP_DIR" ]]; then
    die "$APP_DIR exists but is not a Git checkout. Move it or choose --app-dir."
  else
    info "Cloning into $APP_DIR"
    git clone --depth 1 "$REPO_URL" "$APP_DIR"
  fi
}

main() {
  info "Telegram-bot-setup one-shot installer"
  install_dependencies
  sync_source
  cd "$APP_DIR"

  local py
  py="$(python_command)"
  "$py" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' || \
    die "Python 3.10 or newer is required."

  info "Creating isolated Python environment"
  "$py" -m venv .venv
  # shellcheck disable=SC1091
  source .venv/bin/activate
  python -m pip install --upgrade pip
  python -m pip install -r requirements.txt

  if [[ ! -f .env || $RECONFIGURE -eq 1 || ${#SETUP_ARGS[@]} -gt 0 ]]; then
    info "Configuring Telegram and AI providers"
    python -m src.setup_env "${SETUP_ARGS[@]}"
  elif ! python -m src.setup_env --validate-only; then
    if ((NON_INTERACTIVE)); then
      die "Existing configuration is invalid. Fix .env or rerun without --non-interactive."
    fi
    warn "Existing configuration needs attention; opening the setup assistant."
    python -m src.setup_env
  fi

  python -m src.setup_env --validate-only
  chmod +x install.sh install-termux.sh start.sh update.sh
  info "Install complete"
  printf '    Start: cd %q && ./start.sh\n' "$APP_DIR"
  printf '    Check: cd %q && .venv/bin/python -m src.setup_env --validate-only\n' "$APP_DIR"
}

main
