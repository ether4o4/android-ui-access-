# ODCM — On-Device Control Module

An Android assistant with on-device screen control, an Obsidian reference vault,
and a growing guide to the controls it has actually seen. This branch adds the
vault and screen-key foundation to the existing ODCM application.

## Use it

1. Install the APK and enable its accessibility service in Android settings.
   The delivered **ODCM Preview** uses `com.ether4o4.odcm.preview` so it installs
   alongside the previous debug build despite a different signing certificate.
   Select its vault and API key separately; keep only one ODCM service active.
2. Open **Obsidian vault · search and import** and select the same folder you
   use in Obsidian. Search works locally without an API key.
3. Add notes or documents anywhere beneath that folder. Search and agent tasks
   refresh the index for additions, edits, renames and deletions. Use **Rebuild
   index** when a sync provider does not report file modifications reliably.
4. Use **Import GPT / Claude export** for a ZIP containing `conversations.json`,
   or select conversation JSON directly. Converted Markdown goes in `Imports/`;
   an unchanged, checksum-named original goes in `Archive/`. Duplicate imports
   skip identical conversations. Conversion never calls a model.
5. Start **Learn while I browse · screen key**, then visit the home screen,
   app drawer, notification shade, quick settings, apps and websites. Stop or
   pause using the control bar. This observation-only session lasts up to
   15 minutes and needs no API key.
6. For autonomous tasks, save your Claude API key, select autonomy and enter
   a task. Screen learning also runs automatically during every agent task.

## The growing screen key

The guide starts with reliable Android system actions: Home, Back, Recents,
Notifications and Quick settings. Device-specific positions for the clock,
launcher icons, drawer and app/site controls come from observations, not guesses.

On first observation, ODCM records every exposed visible control (up to 512),
including its label/ID, role, pixel bounds, center and normalized center. It
records app/version, surface, phone model, display size, rotation, density and
font scale. Password values and editable field contents are not written.
Blocked apps are not read or mapped.

Maps are saved in `ODCM/Apps/<package>/` in the selected vault. The Android
foundation is `ODCM/Android/Foundation.md`. An internal copy allows learning
before you choose a vault; it is published when learning next runs with one
selected. Existing vault files are never overwritten. Changed layouts produce
new revision files, preserving older observations.

On return visits, the saved map is loaded and checked against the live layout.
Known screens use a compact control list; `read_screen` retrieves omitted text.
`read_screen_map` reads the complete coordinate key. `tap_known` resolves a
stable key against the current tree. Ambiguous, missing or changed targets fail
without tapping a stale position. Ordinary indexed taps/types are also remapped
against fresh observations, including after approval or pause.

This is a guide to **observed visible accessibility elements**, not a claim to
have mapped every Android screen in advance. Scroll and visit new screens to
extend it. Some custom canvases, games and protected screens expose no readable
controls. Those require additional observation/teaching support; this version
does not invent their layouts or perform screenshot-based control.

## Vault coverage

| Files | Search coverage |
|---|---|
| Markdown, plain text, JSON/JSONL, Canvas, CSV/TSV, YAML/XML/HTML, common code/log/subtitle files | Full extracted text and filename |
| DOCX, PPTX, XLSX, ODT | Extracted text and filename; formatting is not reproduced |
| PDF on Android 15+ | Embedded text and filename; scanned pages need OCR, which is not implemented |
| PDF on older Android, images, audio, video, archives and other formats | Filename only, explicitly labeled |

Search includes subfolders and does not require custom frontmatter. `.obsidian`,
other dotfolders/files and trash/configuration are excluded. A coverage screen
lists files without extracted text and read errors. Bounds: 50,000 files, 64
folder levels, 8 million extracted characters per file; 128 MB per PDF/expanded
Office document, 2,000 PDF pages; 1 GB per conversation export/expanded ZIP and
16 million characters per conversation. Oversize files are reported, not silently
presented as fully read. The original archive retains attachments and unsupported
fields; imports preserve conversation IDs, message roles, times and GPT branches.
Unsupported export schemas are reported for inspection.

The agent has `search_vault` and paged `read_vault` tools and receives a few
matching passages at task start. Source paths accompany retrieval results. Indexing
and import are local; selected passages may be sent to the configured Claude API
when running an agent task. Historical content is reference data, not permission
or executable instructions. Entering text in another app after reading the vault
requires a per-task approval for that app.

## Reliability and validation

Stop cancels an in-flight API request. Pause is checked again before acting.
Approvals expire after 90 seconds; tasks have a 10-minute wall-clock limit and
bounded step/failure counts. A dispatched tap is not treated as success; only an observed, uniquely identified
control state change is learned as a successful button action. Installed-app
awareness and unambiguous package resolution from the latest development branch
are preserved. Plain-text model answers are handled, and parallel tool
requests are disabled/rejected before acting.

Run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:lintPreview :app:assemblePreview
```

Tests cover real Android SQLite FTS via Robolectric, additions/edits/deletions,
vault switching, Unicode/paging, import schemas/branches, coordinate revisions,
ambiguous/stale controls and network cancellation. Test-only dependencies are
Robolectric (MIT), MockWebServer (Apache-2.0), JUnit (EPL-1.0), and org.json
(public domain); none are packaged into the APK. No new runtime library was added.

On-device verification is still required for Samsung's file picker, permission
persistence, live app layouts, PDFs, accessibility services, battery behavior
and real Claude tasks. An API key and an attached/authorized phone were not
available in the build environment. See [the phone checklist](docs/PHONE_CHECKLIST.md).

## Scope and references

ODCM remains the foundation. The supplied JARVIS Windows/macOS installers and
[the creator's release post](https://www.patreon.com/JarvisV1/posts/download-now-j-r-174340596)
were reference material. No proprietary executable code, prompts, branding,
assets or desktop services are copied into this application. The applicable
patterns are a persistent memory vault, clear operation status and recoverable
failures; this Android implementation is independent.

The broader [ODCM spec](docs/ODCM_SPEC.md) also describes future work. Voice,
screenshot fallback, verified flow replay, AI distillation, hidden displays,
MCP, wake-word listening and scheduled execution are **not completed by this
change**. These are not represented as working features in the new UI.
