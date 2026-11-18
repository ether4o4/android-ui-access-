#!/usr/bin/env bash
# The only production signing path. Never fall back to a runner-generated key.
set -euo pipefail
set +x
umask 077
root=$(cd "$(dirname "$0")/.." && pwd)
expected=$(tr -d '[:space:]' < "$root/signing/certificate.sha256")
if [[ ! "$expected" =~ ^[a-f0-9]{64}$ ]]; then
  echo '::error::Permanent signing is not enrolled. Run tools/setup-signing.sh once.'
  exit 1
fi
if [[ -z "${ODCM_DEBUG_KEYSTORE_BASE64:-}" ]]; then
  echo '::error::ODCM_DEBUG_KEYSTORE_BASE64 is missing. Release stopped; no replacement key will be generated.'
  exit 1
fi
destination=${ODCM_SIGNING_OUTPUT:-$HOME/.android/debug.keystore}
mkdir -p "$(dirname "$destination")"
temporary=$(mktemp "${destination}.XXXXXX")
certificate=$(mktemp)
trap 'rm -f "$temporary" "$certificate"' EXIT
printf '%s' "$ODCM_DEBUG_KEYSTORE_BASE64" | base64 --decode > "$temporary"
keytool -exportcert -keystore "$temporary" -storepass android -alias androiddebugkey -file "$certificate" >/dev/null 2>&1
actual=$(sha256sum "$certificate" | cut -d ' ' -f1)
if [[ "$actual" != "$expected" ]]; then
  echo '::error::Signing certificate differs from the permanent fingerprint. Release stopped.'
  exit 1
fi
mv "$temporary" "$destination"
chmod 600 "$destination"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then printf 'ready=true\n' >> "$GITHUB_OUTPUT"; fi
echo "Permanent signing certificate verified: $actual"
