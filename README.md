# ODCM — On-Device Control Module


## Download ODCM

**[Download the latest ODCM APK](https://github.com/ether4o4/android-ui-access-/releases/download/android-latest/ODCM-debug.apk)**

This is the permanent Android download link. Successful builds from `main`
replace the APK at this same address after tests and lint pass. Download this
single APK directly; there is no need to download or unpack the three-APK
GitHub Actions artifact.

Current app version: **0.3.4 (build 13)**. The avatar fills the companion panel
at every size. Tap **▾** for conversation and typing, **▴** to hide it, and **🎤**
for a spoken turn. Drag **⠿** to move and **◢** to resize; position and size persist.

Voice capture runs in a visible Android microphone dialog and requests microphone
permission the first time. On-device recognition is preferred when available;
otherwise Android's selected recognizer is used with an offline preference (that
preference is not a guarantee: the system recognizer may use a network). Only a
final transcript is submitted, once per recording. Cancel/backgrounding stops
capture. Tap the mic again for the next turn; this is not always-on listening.

Final replies to voice requests use the selected Android text-to-speech engine
and its offline voice. Choose your installed Kokoro/Heart TTS engine in **Android
voice settings** from ODCM first; standalone WAV samples and model files are not
Android TTS engines. ODCM does not require a voice endpoint or key and does not
bundle Kokoro. A network-only or unavailable voice produces a setup notice in the
chat drawer. Typed-only requests stay silent. **■** stops the task and speech;
closing the companion or turning the screen off also stops speech playback.

This download installs the `com.ether4o4.odcm.debug` package. Preview and Local
Preview are separate installations. CI signing keys can differ between builds;
if Android reports a signing conflict, preserve the installed app and its data.
Do not uninstall it to force an update.

## Request economy and provider limits

- Select **Z.ai · GLM Flash** and save a standard Z.ai API key to try
  `glm-4.7-flash`. The preset uses the general API endpoint. Free pricing does not
  remove account quotas or guarantee availability. Existing keys are preserved.
- Economy mode defaults on. If the saved Gemini model is Hydrogen, ordinary
  requests use `gemini-2.5-flash-lite`; `/deep your request` explicitly uses
  Hydrogen and a larger output budget. Other custom model selections are kept.
  No cross-provider or paid fallback happens automatically.
- Clear conversational prompts use a small tool-free request even when
  accessibility is enabled. Ambiguous prompts keep phone tools. `/chat` forces
  conversation; `/task` forces phone control; `/deep` increases effort/budget.
- Exact Home, Back, Recents, Notifications, Quick Settings and unambiguous
  `Open <installed app>` requests use no model. They still run through the same
  policy, approval, fresh-screen checks and action verification as AI tasks.
- Only the latest screen is retained. After three recent tool exchanges, older
  exchanges become a small outcome ledger. The original goal and follow-ups
  remain intact. `read_task_history` can recover the full removed tool results
  during that task. The app keeps new conversation messages in a private local
  `conversation.jsonl`; only a selected recent/relevant window goes to the model.
- Streaming and non-streaming calls respect Retry-After (seconds/date) and Gemini
  retry-delay metadata. Transient rejections get at most two retries; waits over
  a minute and exhausted quotas stop with an explanation. Stop cancels waits.
  In-process cooldowns also cover new requests to the same endpoint/model/key.
  Interrupted streams are never blindly replayed. The app cannot replenish an
  account's daily quota or credits.
- **Last task usage** shows provider-reported input/output, reported reasoning,
  cached tokens, request/retry count and outcome. Reports are also kept in private
  `usage.jsonl`. Cost estimates use published standard text rates checked on
  2026-11-16; they are not invoices, account-tier detection, or guarantees. Missing
  usage reports (including rejected/interrupted calls) are explicitly counted.

## Companion positioning

The scene fills the panel with a crop focused on the character, so its edges may be cropped in a tall
or wide panel. It is never removed just because the panel is small. Translucent
icon controls remain over the scene; status, conversation, activity logs and the
composer are hidden inside the drawer until explicitly opened. Logs appear only
in expanded mode with the drawer open and enough vertical room.

Drag **⠿** to move. Drag **◢** horizontally, vertically or diagonally to resize
with the top-left corner fixed. Screen bounds and minimum usable dimensions
apply. Geometry is saved separately by mode and orientation. Opening/closing the
drawer keeps the same window geometry and draft. Keyboard accommodation is
temporary and restores the saved bounds afterward. The avatar stays behind the
drawer while typing. Physical Galaxy keyboard/microphone/selected TTS-engine
verification remains necessary in addition to automated layout and lifecycle tests.

Sources for the provider integration:
- https://docs.z.ai/guides/overview/pricing
- https://docs.z.ai/guides/capabilities/thinking-mode
- https://ai.google.dev/gemini-api/docs/openai
- https://console.groq.com/docs/reasoning

## Companion and AI providers

The avatar is the full background in both compact and expanded modes. Conversation
is a dropdown drawer with a composer at its bottom; expanded mode additionally
offers activity logs inside that drawer. Draft text and conversation survive
minimizing, opening/closing the drawer, and resizing.

Replies stream from the selected provider into chat. Follow-up messages sent
while a phone task is running are consumed at decision boundaries: a new message
invalidates a pending old action, including while approval is pending. Pause and
Stop remain available. Without Accessibility, text chat and retrieved vault
references work, but device-action tools are omitted. Completed chat is stored
privately, with a bounded display window and a full local archive of new messages; only selected recent/relevant conversation is sent for continuity.
Relevant conversation and vault excerpts go to the selected model provider.

The supplied writing video is converted into a silent 5.5-second animated WebP
with 132 frames at 24 fps and a blended boundary. It plays continuously while
working. One retained decoder avoids restarting on chat/log updates or a video
player seeking back to zero. Idle and paused states play the supplied sleeping
scene as a silent 19.9-second loop; a chosen custom avatar still overrides idle.
Both scenes run entirely on Android without an API key or network requests.
Playback stops when the screen is off, the microphone dialog temporarily replaces
the overlay, or the overlay is closed. A request error returns to idle without stopping the
sleeping scene. Talking footage is not integrated; voice replies use the Android TTS engine.
See [scene provenance](art/companion/README.md).

Select **AI provider**, enter its key when required, then tap **Save provider
settings**. Model, endpoint and encrypted keys stay separate for each provider;
existing Claude settings are preserved. Available presets are Anthropic, Groq,
Z.ai GLM Flash, OpenRouter Free, Gemini, Hugging Face, local Ollama and a custom OpenAI-compatible
endpoint. Both API protocols support sequential tool calls and cancellation.
Malformed or multiple actions are rejected before any device action runs.

- **Groq:** start with `openai/gpt-oss-20b` and a [Groq key](https://console.groq.com/keys). A free tier is available with account-specific limits.
- **OpenRouter:** `openrouter/free` uses eligible free tool-capable models. Create an [OpenRouter key](https://openrouter.ai/settings/keys); multi-step tasks consume several requests. ODCM does not switch to a paid model automatically.
- **Gemini:** defaults to `gemini-2.5-flash-lite`; `/deep` selects `gemini-4-hydrogen`. Free-tier access is subject to account availability and limits. Google's free-tier data terms may allow inputs to be used to improve products.
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
