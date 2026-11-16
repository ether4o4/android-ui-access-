#!/data/data/com.termux/files/usr/bin/bash
# ═══════════════════════════════════════════════════════════════════
# AutoNoBot — One-Script Install for Termux (Samsung S24 Ultra)
# ═══════════════════════════════════════════════════════════════════
# Usage: chmod +x install.sh && ./install.sh
# ═══════════════════════════════════════════════════════════════════

set -e

R='\033[0;31m'
G='\033[0;32m'
Y='\033[1;33m'
B='\033[0;34m'
C='\033[0;36m'
N='\033[0m'

ok()   { echo -e "  ${G}✓${N} $1"; }
warn() { echo -e "  ${Y}⚠${N} $1"; }
err()  { echo -e "  ${R}✗${N} $1"; }
info() { echo -e "  ${C}ℹ${N} $1"; }

echo -e "${B}"
echo "  ╔═══════════════════════════════════╗"
echo "  ║     AutoNoBot v1.0 Installer      ║"
echo "  ║   Autonomous Phone Agent Setup     ║"
echo "  ╚═══════════════════════════════════╝"
echo -e "${N}"

# ── Detect environment ────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="$HOME/.autonobot"

echo -e "\n${Y}[1/8]${N} Prerequisites..."

# Check we're in Termux
if [ -d "/data/data/com.termux" ]; then
    ok "Running in Termux"
else
    warn "Not in Termux — some features may not work"
fi

# Install packages if missing
for pkg in python git curl; do
    if ! command -v $pkg &>/dev/null; then
        info "Installing $pkg..."
        pkg install -y $pkg 2>/dev/null || apt install -y $pkg 2>/dev/null || true
    fi
    if command -v $pkg &>/dev/null; then
        ok "$pkg"
    else
        err "$pkg not found"
    fi
done

# termux-api (optional but recommended)
if command -v termux-battery-status &>/dev/null; then
    ok "termux-api"
else
    warn "termux-api not installed"
    info "Fix: pkg install termux-api"
    info "Also install Termux:API app from F-Droid"
fi

# ── Create directories ───────────────────────────────────────────
echo -e "\n${Y}[2/8]${N} Creating directories..."

mkdir -p "$INSTALL_DIR"/{logs,skills}
ok "~/.autonobot/ structure created"

# ── Copy files ───────────────────────────────────────────────────
echo -e "\n${Y}[3/8]${N} Installing files..."

# Config (don't overwrite existing)
if [ ! -f "$INSTALL_DIR/config.yaml" ]; then
    cp "$SCRIPT_DIR/config.yaml" "$INSTALL_DIR/config.yaml"
    ok "config.yaml"
else
    ok "config.yaml (kept existing)"
fi

if [ ! -f "$INSTALL_DIR/safety_rules.json" ]; then
    cp "$SCRIPT_DIR/safety_rules.json" "$INSTALL_DIR/safety_rules.json"
    ok "safety_rules.json"
else
    ok "safety_rules.json (kept existing)"
fi

# Skills — always update
cp -r "$SCRIPT_DIR/skills/"* "$INSTALL_DIR/skills/" 2>/dev/null || true
SKILL_COUNT=$(find "$INSTALL_DIR/skills" -name "SKILL.md" | wc -l)
ok "${SKILL_COUNT} skills installed"

# ── Python environment ───────────────────────────────────────────
echo -e "\n${Y}[4/8]${N} Python environment..."

if [ -d "$HOME/autonobot-env" ]; then
    ok "Virtual environment exists"
else
    python -m venv "$HOME/autonobot-env"
    ok "Virtual environment created"
fi

source "$HOME/autonobot-env/bin/activate"

# Upgrade pip silently — ignore errors, not critical
pip install --upgrade pip setuptools wheel --quiet --no-warn-script-location 2>/dev/null || true
ok "pip ready"

# Install dependencies first (simpler, faster on Termux)
pip install requests pyyaml --quiet --no-warn-script-location 2>/dev/null
ok "dependencies installed (requests, pyyaml)"

# Install the package — try normal install, fallback to manual
cd "$SCRIPT_DIR"
if pip install . --quiet --no-warn-script-location 2>/dev/null; then
    ok "autonobot package installed"
else
    warn "pip install failed — using manual install"
    # Manual fallback: copy package to venv site-packages + create CLI wrapper
    SITE_PKG="$HOME/autonobot-env/lib/python$(python -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')/site-packages"
    cp -r "$SCRIPT_DIR/autonobot" "$SITE_PKG/autonobot"
    # Create autonobot CLI script
    cat > "$HOME/autonobot-env/bin/autonobot" << 'CLISCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
source "$HOME/autonobot-env/bin/activate"
python -m autonobot.cli "$@"
CLISCRIPT
    chmod +x "$HOME/autonobot-env/bin/autonobot"
    ok "autonobot CLI installed (manual)"
fi

# Always verify the CLI works
if "$HOME/autonobot-env/bin/autonobot" --help >/dev/null 2>&1 || \
   "$HOME/autonobot-env/bin/python" -m autonobot.cli 2>/dev/null | grep -q "Usage"; then
    ok "autonobot CLI verified"
else
    # Last resort: direct wrapper script
    cat > "$HOME/bin/autonobot" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
source "$HOME/autonobot-env/bin/activate"
python $SCRIPT_DIR/autonobot/cli.py "\$@"
WRAPPER
    mkdir -p "$HOME/bin"
    chmod +x "$HOME/bin/autonobot"
    ok "autonobot CLI installed (wrapper)"
fi

# ── Telegram config ──────────────────────────────────────────────
echo -e "\n${Y}[5/8]${N} Telegram configuration..."

TG_CFG="$INSTALL_DIR/telegram_config.json"

if [ -f "$TG_CFG" ]; then
    # Check if token is set
    TOKEN=$(python -c "import json; print(json.load(open('$TG_CFG')).get('bot_token',''))" 2>/dev/null || echo "")
    if [ -n "$TOKEN" ] && [ "$TOKEN" != "PASTE_YOUR_BOT_TOKEN_HERE" ]; then
        ok "Telegram already configured (token …${TOKEN: -6})"
    else
        warn "Token not set in telegram_config.json"
    fi
else
    echo ""
    echo -e "  ${C}Telegram Bot Setup${N}"
    echo "  Get token: message @BotFather → /newbot"
    echo "  Get user ID: message @userinfobot"
    echo ""
    read -p "  Bot token (Enter to skip): " BOT_TOKEN
    read -p "  Your user ID (Enter to skip): " USER_ID

    if [ -n "$BOT_TOKEN" ]; then
        cat > "$TG_CFG" << TGEOF
{
  "bot_token": "$BOT_TOKEN",
  "allowed_user_ids": ["${USER_ID:-YOUR_USER_ID}"],
  "ollama_url": "http://127.0.0.1:11434",
  "ollama_model": "qwen2.5-coder:3b"
}
TGEOF
        ok "Telegram config saved"
    else
        cat > "$TG_CFG" << TGEOF
{
  "bot_token": "PASTE_YOUR_BOT_TOKEN_HERE",
  "allowed_user_ids": ["PASTE_YOUR_TELEGRAM_USER_ID"],
  "ollama_url": "http://127.0.0.1:11434",
  "ollama_model": "qwen2.5-coder:3b"
}
TGEOF
        warn "Skipped — edit ~/.autonobot/telegram_config.json later"
    fi
fi

# ── Ollama + Model ───────────────────────────────────────────────
echo -e "\n${Y}[6/8]${N} Ollama + Model setup..."

MODEL_NAME="qwen2.5-coder:3b"
CUSTOM_MODEL="autonobot"

if curl -s --max-time 3 http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
    ok "Ollama is running"

    # Check if base model exists
    if curl -s http://127.0.0.1:11434/api/tags | grep -q "qwen2.5"; then
        ok "Qwen base model found"
    else
        info "Pulling Qwen model (this may take a few minutes)..."
        ollama pull "$MODEL_NAME" 2>/dev/null && ok "Model pulled" || warn "Pull failed — run manually: ollama pull $MODEL_NAME"
    fi

    # Create custom model with baked-in system prompt via Modelfile
    if curl -s http://127.0.0.1:11434/api/tags | grep -q "autonobot"; then
        ok "autonobot custom model exists"
    else
        info "Creating autonobot model with baked-in system prompt..."
        cp "$SCRIPT_DIR/Modelfile" "$INSTALL_DIR/Modelfile"
        cd "$INSTALL_DIR"
        ollama create autonobot -f Modelfile 2>/dev/null && ok "autonobot model created" || warn "Model creation failed — run manually: cd ~/.autonobot && ollama create autonobot -f Modelfile"
        cd "$SCRIPT_DIR"
    fi
else
    warn "Ollama not running at 127.0.0.1:11434"
    info "Start it: ollama serve"
    info "Then run: ollama pull $MODEL_NAME"
    info "Then run: cd ~/.autonobot && ollama create autonobot -f Modelfile"
fi

# Copy Modelfile regardless
cp "$SCRIPT_DIR/Modelfile" "$INSTALL_DIR/Modelfile" 2>/dev/null || true

# ── Tasker check ──────────────────────────────────────────────────
echo -e "\n${Y}[7/8]${N} Tasker + AutoInput check..."

TASKER_PKG="net.dinglisch.android.taskerm"
AUTOINPUT_PKG="com.joaomgcd.autoinput"

if pm list packages 2>/dev/null | grep -q "$TASKER_PKG"; then
    ok "Tasker installed"
else
    warn "Tasker not detected"
    info "Install from Play Store for full screen autonomy"
fi

if pm list packages 2>/dev/null | grep -q "$AUTOINPUT_PKG"; then
    ok "AutoInput installed"
else
    warn "AutoInput not detected"
    info "Install from Play Store (Tasker plugin by joaomgcd)"
fi

# Create tasker command directory
mkdir -p "$INSTALL_DIR"
info "See TASKER_SETUP.md for one-time Tasker configuration"

# ── Shell aliases ────────────────────────────────────────────────
echo -e "\n${Y}[8/8]${N} Shell shortcuts..."

SHELL_RC="$HOME/.bashrc"
[ -f "$HOME/.zshrc" ] && SHELL_RC="$HOME/.zshrc"

if ! grep -q "autonobot-env" "$SHELL_RC" 2>/dev/null; then
    # Write aliases — use explicit path so they work regardless of install method
    AUTONOBOT_BIN="$HOME/autonobot-env/bin/autonobot"
    cat >> "$SHELL_RC" << ALIASES

# ── AutoNoBot ────────────────────────────────────
_bot() { source "\$HOME/autonobot-env/bin/activate" && "\$HOME/autonobot-env/bin/python" -m autonobot.cli "\$@"; }
alias bot='_bot'
alias bot-start='_bot start'
alias bot-status='_bot status'
alias bot-skills='_bot skills'
alias bot-logs='tail -f \$HOME/.autonobot/logs/bridge.log'
ALIASES
    ok "Aliases added: bot, bot-start, bot-status, bot-skills, bot-logs"
else
    ok "Aliases already set"
fi

# ── Done ─────────────────────────────────────────────────────────
echo ""
echo -e "${G}  ╔═══════════════════════════════════╗"
echo "  ║        Install Complete!           ║"
echo -e "  ╚═══════════════════════════════════╝${N}"
echo ""
echo -e "  ${C}Installed:${N}"
echo "    • autonobot CLI"
echo "    • ${SKILL_COUNT} AI skills"
echo "    • Safety system"
echo "    • Telegram bridge"
echo ""
echo -e "  ${C}Quick start:${N}"
echo ""
echo "    source ~/.bashrc"
echo ""
echo "    # If you haven't set Telegram token yet:"
echo "    nano ~/.autonobot/telegram_config.json"
echo ""
echo "    # Start the bot:"
echo "    bot-start"
echo ""
echo "    # Or run setup wizard:"
echo "    bot setup"
echo ""
echo -e "  ${C}Send your Telegram bot a message:${N}"
echo "    'open spotify'"
echo "    'take a selfie'"
echo "    'what is my battery level'"
echo ""
echo -e "  ${Y}Logs:${N} bot-logs"
echo -e "  ${Y}Status:${N} bot-status"
echo ""
