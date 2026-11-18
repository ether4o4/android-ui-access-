#!/usr/bin/env bash
# Run once on a trusted computer/phone. The private backup stays outside the repo.
set -euo pipefail
set +x
umask 077
repo=ether4o4/android-ui-access-
for command in gh keytool base64 sha256sum; do
  command -v "$command" >/dev/null || { echo "Missing $command. Install GitHub CLI and a Java JDK first."; exit 1; }
done
gh auth status --hostname github.com >/dev/null 2>&1 || gh auth login --hostname github.com --git-protocol https --web

signing_dir=${ODCM_SIGNING_DIR:-$HOME/.local/share/odcm-signing}
mkdir -p "$signing_dir"
chmod 700 "$signing_dir"
keystore="$signing_dir/debug.keystore"
metadata=$(gh api "repos/$repo/contents/signing/certificate.sha256?ref=main" --jq '[.sha,.content] | join("\n")')
blob_sha=${metadata%%$'\n'*}
expected=$(printf '%s' "${metadata#*$'\n'}" | base64 --decode | tr -d '[:space:]')
secret_names=$(gh secret list --repo "$repo" --json name --jq '.[].name')

if [[ ! -f "$keystore" ]]; then
  if [[ -n "${ODCM_EXISTING_KEYSTORE:-}" ]]; then
    cp "$ODCM_EXISTING_KEYSTORE" "$keystore"
  elif [[ "$expected" != UNCONFIGURED || "$secret_names" == *ODCM_DEBUG_KEYSTORE_BASE64* ]]; then
    echo 'Signing is already enrolled remotely. Restore its private keystore backup; refusing to generate a different key.'
    exit 1
  else
    keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -alias androiddebugkey \
      -keyalg RSA -keysize 3072 -validity 10000 -storepass android -keypass android \
      -dname 'CN=ODCM, OU=Android, O=ODCM, C=US' >/dev/null 2>&1
  fi
fi
chmod 600 "$keystore"
certificate=$(mktemp)
trap 'rm -f "$certificate"' EXIT
keytool -exportcert -keystore "$keystore" -storepass android -alias androiddebugkey -file "$certificate" >/dev/null 2>&1
actual=$(sha256sum "$certificate" | cut -d ' ' -f1)
if [[ "$expected" != UNCONFIGURED && "$expected" != "$actual" ]]; then
  echo 'Local key does not match the permanent signing certificate. Nothing uploaded.'
  exit 1
fi
# Secret bytes travel through stdin, never arguments, repository files or log output.
base64 < "$keystore" | gh secret set ODCM_DEBUG_KEYSTORE_BASE64 --repo "$repo"
if [[ "$expected" == UNCONFIGURED ]]; then
  gh api --method PUT "repos/$repo/contents/signing/certificate.sha256" \
    -f message='Pin permanent ODCM signing certificate' -f branch=main -f sha="$blob_sha" \
    -f content="$(printf '%s\n' "$actual" | base64 | tr -d '\n')" >/dev/null
else
  gh workflow run build.yml --repo "$repo" --ref main
fi
echo 'Permanent signing configured. Future main builds must use this exact certificate.'
echo "Private backup: $keystore"
echo 'Keep a secure second copy of that file. Never upload it to a public repository or build artifact.'
echo "Public certificate SHA-256: $actual"
