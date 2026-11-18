# Permanent ODCM signing

Main releases require the GitHub Actions secret `ODCM_DEBUG_KEYSTORE_BASE64`
and the public SHA-256 fingerprint in `signing/certificate.sha256`. Missing,
unconfigured, malformed, or mismatched signing material stops the release.
The final APK certificate must match the pinned fingerprint before upload.
There are no one-off publishing workflows that bypass these checks.

## One-time enrollment

On a trusted computer or Termux with GitHub CLI and a Java JDK installed:

```sh
bash tools/setup-signing.sh
```

The helper signs into GitHub if required, creates one persistent keystore outside
the checkout, stores it as an encrypted repository secret, pins its public
certificate fingerprint, and starts the main build. Re-running it reuses the key;
it refuses to replace an enrolled certificate. The local backup is
`$HOME/.local/share/odcm-signing/debug.keystore` (override `ODCM_SIGNING_DIR`).
Keep a secure second copy. Do not commit, log, cache publicly, or upload private
signing material as an Actions artifact. The fixed Android debug alias/password
are compatibility parameters; secrecy comes from protecting the private file.

An existing matching key can be imported with `ODCM_EXISTING_KEYSTORE=/path/to/key`.
This helper expects alias `androiddebugkey`, store/key password `android`.
Do not substitute an unrelated local debug key for a lost CI key.

The 0.3.5/0.3.6 releases used ephemeral runner keys. Those keys cannot be
reconstructed from their APK certificates. A new permanent key does not make
an update compatible with those installs. After the one-time migration to the
permanently signed build, subsequent same-package updates preserve app data.
Do not advise uninstalling the working app until that new build is published.
