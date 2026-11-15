#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────
# AutoNoBot fix script — run this if install.sh
# already ran but things aren't working
# ─────────────────────────────────────────────────

G='\033[0;32m'; Y='\033[1;33m'; C='\033[0;36m'; N='\033[0m'
ok()   { echo -e "  ${G}✓${N} $1"; }
info() { echo -e "  ${C}ℹ${N} $1"; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="$HOME/.autonobot"
MODEL="qwen2.5-coder:3b"

echo -e "\n${Y}AutoNoBot Fix Script${N}\n"

# 1. Fix model name everywhere
echo "[1/5] Fixing model name..."
sed -i "s|maites/qwen2.5[^\"]*|$MODEL|g" "$INSTALL_DIR/telegram_config.json" 2>/dev/null || true
sed -i "s|maites/qwen2.5[^\"]*|$MODEL|g" "$INSTALL_DIR/config.yaml" 2>/dev/null || true
ok "model name → $MODEL"

# 2. Pull model if missing
echo "[2/5] Checking model..."
if curl -s http://127.0.0.1:11434/api/tags 2>/dev/null | grep -q "qwen2.5-coder"; then
    ok "qwen2.5-coder:3b already pulled"
else
    info "Pulling $MODEL (1.9GB, takes a few minutes)..."
    ollama pull "$MODEL" && ok "Model pulled" || echo "  Run manually: ollama pull $MODEL"
fi

# 3. Recreate autonobot model
echo "[3/5] Rebuilding autonobot model..."
cp "$SCRIPT_DIR/Modelfile" "$INSTALL_DIR/Modelfile"
cd "$INSTALL_DIR"
ollama create autonobot -f Modelfile 2>/dev/null && ok "autonobot model created" || \
    info "Run manually: cd ~/.autonobot && ollama create autonobot -f Modelfile"

# 4. Reinstall Python package
echo "[4/5] Reinstalling package..."
source "$HOME/autonobot-env/bin/activate"
cd "$SCRIPT_DIR"
pip install . --quiet --no-warn-script-location 2>/dev/null && ok "package installed" || true

# Manual copy fallback
SITE_PKG="$HOME/autonobot-env/lib/python$(python -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')/site-packages"
cp -r "$SCRIPT_DIR/autonobot" "$SITE_PKG/autonobot" 2>/dev/null && ok "package copied to site-packages" || true

# 5. Fix aliases
echo "[5/5] Fixing aliases..."
SHELL_RC="$HOME/.bashrc"
[ -f "$HOME/.zshrc" ] && SHELL_RC="$HOME/.zshrc"

# Remove old broken aliases
sed -i '/autonobot-env/d; /bot-start/d; /bot-status/d; /bot-skills/d; /bot-logs/d; /AutoNoBot/d; /_bot()/d; /alias bot/d' "$SHELL_RC" 2>/dev/null || true

cat >> "$SHELL_RC" << ALIASES

# ── AutoNoBot ────────────────────────────────────
_bot() { source "\$HOME/autonobot-env/bin/activate" && "\$HOME/autonobot-env/bin/python" -m autonobot.cli "\$@"; }
alias bot='_bot'
alias bot-start='_bot start'
alias bot-status='_bot status'
alias bot-skills='_bot skills'
alias bot-logs='tail -f \$HOME/.autonobot/logs/bridge.log'
ALIASES
ok "aliases fixed"

echo ""
echo -e "${G}Done. Now run:${N}"
echo ""
echo "  source ~/.bashrc"
echo "  bot-status    ← check everything"
echo "  bot-start     ← launch the bot"
echo ""
