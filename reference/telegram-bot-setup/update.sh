#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

git pull --ff-only

if [ -f ".venv/bin/activate" ]; then
  source .venv/bin/activate
fi

python -m pip install --upgrade pip wheel setuptools
python -m pip install -r requirements.txt

echo "Update complete. Run ./start.sh to launch."
