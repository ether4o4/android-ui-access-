# ODCM — On-Device Control Module

## Download ODCM

**[Download the latest ODCM APK](https://github.com/ether4o4/android-ui-access-/releases/download/android-latest/ODCM-debug.apk)**

This is the permanent Android download link. Successful builds from `main`
replace the APK at this same address after tests and lint pass. Download this
single APK directly; there is no need to download or unpack the three-APK
GitHub Actions artifact.

Current app version: **0.3.1 (build 10)**, including companion chat and the
continuous working scene. Version 0.3.1 fixes the minimized bubble so tapping
opens chat and dragging moves it without accidentally opening it. These changes
are merged into `main`.

This download installs the `com.ether4o4.odcm.debug` package. Preview and Local
Preview are separate installations. CI signing keys can differ between builds;
if Android reports a signing conflict, preserve the installed app and its data.
Do not uninstall it to force an update.

## Companion and AI providers

The medium window has the animated scene, a separate conversation, and a text
composer anchored at the bottom. **Only the full window shows activity logs.**
Draft text and conversation survive minimizing or changing window size. The
keyboard is requested when the composer is tapped and released after sending;
the panel adjusts when the keyboard is visible.

Replies stream from the selected provider into chat. Follow-up messages sent
while a phone task is running are consumed at decision boundaries: a new message
invalidates a pending old action, including while approval is pending. Pause and
Stop remain available. Without Accessibility, text chat and retrieved vault
references work, but device-action tools are omitted. Completed chat is stored
privately with bounded history; only recent conversation is used for continuity.
Relevant conversation and vault excerpts go to the selected model provider.

The supplied writing video is converted into a silent 5.5-second animated WebP
with 132 frames at 24 fps and a blended boundary. It plays continuously while
working. One retained decoder avoids restarting on chat/log updates or a video
player seeking back to zero. It stops while idle, paused, screen-off, or closed.
The supplied resting image (or the user's chosen avatar) is the idle scene.
Talking and sleeping videos can be incorporated when supplied; live voice is
not part of this change. See [scene provenance](art/companion/README.md).

Select **AI provider**, enter its key when required, then tap **Save provider
settings**. Model, endpoint and encrypted keys stay separate for each provider;
existing Claude settings are preserved. Available presets are Anthropic, Groq,
OpenRouter Free, Gemini, Hugging Face, local Ollama and a custom OpenAI-compatible
endpoint. Both API protocols support sequential tool calls and cancellation.
Malformed or multiple actions are rejected before any device action runs.

- **Groq:** start with `openai/gpt-oss-20b` and a [Groq key](https://console.groq.com/keys). A free tier is available with account-specific limits.
- **OpenRouter:** `openrouter/free` uses eligible free tool-capable models. Create an [OpenRouter key](https://openrouter.ai/settings/keys); multi-step tasks consume several requests. ODCM does not switch to a paid model automatically.
- **Gemini:** the `gemini-4-hydrogen` preset has a free tier, subject to account availability and limits. Google's free-tier data terms may allow inputs to be used to improve products.
- **Hugging Face:** supported as an optional provider; free accounts currently have no included Inference Providers credits.
- **Ollama on this phone:** start the server in Termux (`phi` on the configured phone), select Local Ollama and model `phi4-mini`. The endpoint is `http://127.0.0.1:11434/v1/chat/completions`; no API key is required. Local model availability, speed and tool reliability depend on the installed model and device.

Remote agent requests include screen text and selected vault passages. Local
search and observation-only learning remain available without an API account.
Remote endpoints require HTTPS; cleartext HTTP is limited to the phone's loopback
addresses. Custom endpoints must implement chat completions with function tools.

Provider references: [Groq tool support](https://console.groq.com/docs/tool-use/overview),
[OpenRouter Free](https://openrouter.ai/openrouter/free),
[Gemini compatibility](https://aistudio.google.com/docs/openai),
[Gemini pricing](https://aistudio.google.com/docs/pricing),
[Hugging Face pricing](https://huggingface.co/docs/inference-providers/pricing),
[Ollama compatibility](https://docs.ollama.com/api/openai-compatibility).

Build this PC's installable variant with Java 17 and Android SDK 35:

```sh
./gradlew :app:testDebugUnitTest :app:lintLocalPreview :app:assembleLocalPreview
```

Keep the local signing key private and backed up. CI does not publish or replace
the locally signed rolling artifact; it must be built with the same signer.

An Android assistant with on-device screen control, an Obsidian reference vault,
and a growing guide to the controls it has actually seen. This branch adds the
vault and screen-key foundation to the existing ODCM application.

## Use it

1. Install the APK from **Download ODCM** above and enable its accessibility
   service in Android settings. Select its vault and AI provider settings;
   keep only one ODCM accessibility service active.
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
6. For autonomous tasks, select an AI provider and save its key if required, select autonomy and enter
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
and import are local; selected passages may be sent to the selected AI provider
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

The prior 0.2.1 build had on-phone checks for vault access/search and screen
learning. Those results do not verify the new 0.3.0 chat UI. New regression tests
cover streamed text/tool assembly, incomplete streams, cancellation, follow-up
messages invalidating actions, compact/full log visibility, and composer drafts.
Build, lint, and test reports are attached to the branch's GitHub Actions run.
Keyboard behavior, provider accounts, animation frame pacing, and state changes
still require the [phone checklist](docs/PHONE_CHECKLIST.md) on the target device.

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
