# ODCM 0.2.0 phone verification

Build checks are necessary but do not replace these checks on the S24 Ultra.
Use a preview installed alongside the old build if signing certificates differ;
never uninstall an existing app merely to work around an update-signature error.

1. Enable the new build's accessibility service. Keep only the build being tested
   active. Open its vault page and choose your Obsidian folder.
2. Search a unique sentence from an ordinary note without frontmatter. Open the
   result and original source. Add, edit, rename and delete a test note in
   Obsidian, then search again. Confirm there are no stale deleted results.
3. Restart the app and phone. Confirm the chosen folder remains readable.
   Revoke folder access and confirm the app reports it rather than returning
   cached content as current.
4. Import a small sanitized GPT export and Claude export twice. Confirm roles,
   dates and alternative branches are retained, repeat imports add no duplicates,
   and the archived original's SHA-256 matches the input. Try a malformed export
   and a read-only vault; confirm the error is shown and existing notes survive.
5. Start Learn while I browse. Visit home pages, drawer, notification shade,
   quick settings, Settings, an app and a browser site. Confirm observed control
   bounds, IDs and centers appear in ODCM/Apps. Verify exposed clock/navigation
   elements are present; unexposed elements must not be guessed.
6. Return to the same screen. Rotate the phone, change font/display scaling,
   move launcher icons, scroll, and visit another site. Confirm maps are separated
   or revised appropriately. Check that editable text/passwords are absent.
7. Run a simple supervised task. Move to a different screen while approval is
   pending. Confirm stale positions are rejected. Stop during a slow model call;
   ensure there is no subsequent tap. Pause/resume and repeat.
8. Ask a question answered by a vault note; confirm the source path is cited.
   Read a long note through multiple passages. Check embedded PDF text, a scanned
   PDF, an image and audio file; unsupported content must be labeled filename only.
9. Stop learning and tasks. Confirm the task notification and bar disappear,
   and no screen collection, network polling or microphone runs while idle.
10. Run a 20-minute task/battery check before calling the build device-verified.
