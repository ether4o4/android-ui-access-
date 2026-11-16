# ODCM 0.3.0 phone verification

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

## Companion chat and media

11. In BOX mode, confirm the composer is at the bottom and no activity log or
    log-expansion button is present. FULL has separate conversation and activity
    history. Resize, rotate, minimize, reopen: confirm draft and conversation
    survive and neither panel scrolls to the end while reading older text.
12. Tap the composer, type using the Samsung keyboard, close the keyboard with
    Back, and send a message. Confirm the composer stays visible, keyboard focus
    returns to the underlying app after send, and the text is delivered once.
13. Use the configured provider for a normal question. Confirm visible streamed
    text, readable final reply, and useful follow-up context. Test a missing key,
    offline endpoint, a stream interrupted halfway, Stop during streaming, and
    reopening the companion. No partial response may cause a phone action.
14. During a delayed phone decision or pending approval, send a correction.
    Confirm the old action is skipped and the correction is considered. Pause,
    send a message, and resume. Requests with tools unavailable should stay chat
    only and explain that Accessibility enables phone tasks.
15. Observe at least ten writing-loop cycles. Check for frame holds, black frames,
    decoder restarts during incoming tokens/logs, and keyboard/rotation pauses.
    The source changes camera framing; its boundary is intentionally blended.
    Verify animation stops on Pause, idle, screen-off, and closing the companion.
16. Confirm a source-linked vault answer from the new chat, including when
    Accessibility is disabled. Check that the selected local/cloud provider is
    still the one configured on the phone; no automatic model switching occurs.
