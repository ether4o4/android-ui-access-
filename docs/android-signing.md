# Persistent APK signing

Old CI builds generated a different debug signing key on each fresh runner. Android rejects an in-place update signed with a different certificate, and uninstalling erases app data and Android Keystore keys. Restoring encrypted preferences alone cannot restore API keys after uninstall.

The rolling release now requires the GitHub Actions repository secret `ODCM_DEBUG_KEYSTORE_BASE64`. It must contain the base64-encoded persistent debug keystore with alias `androiddebugkey`, store password `android`, and key password `android`. Keep a secure private backup of that keystore outside ephemeral runners. Never commit it, print it in logs, put it in a public Actions cache, or upload it as a build artifact.

If the key used to sign the currently installed APK exists, use that exact key. Compare certificate SHA-256 with `apksigner verify --print-certs` on the installed/current APK and a new build before calling the update compatible. If the old runner key is lost, configuring a new persistent key cannot make it match the old install. Do not tell the user to uninstall or reset their configured app as a routine update step; resolve migration separately.

If the secret is absent, CI still tests and assembles, but does not upload main-branch APK artifacts or replace `android-latest`. Pull-request APKs use throwaway signing and are for isolated testing only, not upgrades to a configured app. The signing setup cannot be completed through the current GitHub connector because it does not support secret writes.
