# ODCM: On-Device Control Module

**Status:** draft v0.1 · 2026-11-15 · target device Galaxy S24 Ultra
**Distribution:** closed source, proprietary, owned by ether4o4

ODCM is an Android agent. You give it a goal by voice, typing, or a Telegram
message, and it works the phone the way a person would: it reads the screen,
taps, types and swipes, while you watch and can stop, pause or take over at
any moment. Everything it learns about apps, workflows and you is stored in a
plain-Markdown Obsidian vault, the **ODCM Guide**, so it gets faster over time.

---

## 1. Ground rules

These apply to every line of code and every dependency.

1. **Closed-source safe.** Only permissive licenses (MIT, Apache-2.0, BSD).
   **No GPL/AGPL/LGPL code or binaries,** so nothing from nanoMuse, PalmClaw,
   proot, or GPL-licensed fonts. Each dependency is listed in §9 with its license.
2. **No proprietary code from others.** Oktobot is a feature reference only.
   No decompiled code, assets, text or branding.
3. **Idle means off.** With no task running, ODCM uses no CPU, no wakelock,
   no microphone, no network polling and no foreground service.
4. **Cheapest sense first.** Read the accessibility tree before taking a
   screenshot, use a saved flow before asking the model, and use an intent
   before tapping.
5. **Hard lines live in code, not prompts.** Stop, the blocked-apps list, the
   step limits, and keeping personal data out of other apps are enforced in
   Kotlin. The model cannot override them.
6. **Nothing ships red.** Each change must compile, pass its unit tests, and
   pass lint with zero errors before it is pushed.

---

## 2. Feature map

Oktobot's features (from its public README) and our own requirements, with
how ODCM implements each one. Sources are permissive repos already in
`reference/` or upstream libraries.

| # | Feature | How ODCM does it | Source to reuse | Battery cost |
|---|---|---|---|---|
| F1 | **Screen control** (read, tap, type, swipe, scroll, back/home) | AccessibilityService **in the app's own process** (no cross-process broadcasts). The screen is read as a compact numbered element list. | claw-use-android (MIT) `/screen` + `/act` logic, cClaw gestures (MIT) | Low; nothing runs while idle |
| F2 | **Live overlay**: tap marker, status bubble, **✕ Stop**, **⏸ Pause**, **Takeover**, **▶ Resume** | `TYPE_ACCESSIBILITY_OVERLAY` window (no overlay permission needed), drawn with plain Views rather than Compose. Stop works directly in code and cancels the task at once. | cClaw `OverlayService` (MIT), rewritten | Very low; only drawn during a task |
| F3 | **Takeover / teach mode** | While paused, record which elements you tap through accessibility events, pair them with your narration, and write the result as a DRAFT button map or flow in the Guide. | New | Low, only while recording |
| F4 | **Learned flows**: replay known sequences with no model calls | A flow is a list of `{wait for element → act}` steps run on the device. A failure falls back to the model loop. | claw-use-android `flows.md` (MIT) | Very low |
| F5 | **Agent loop** (look → decide → act, step limit, recovery) | Kotlin coroutines, one task at a time, streaming replies. Stop/Pause/Resume are state changes, not threads being killed. | cClaw `AgentLoop` (MIT), NeverSoft-Agent verify/recover pattern | Model calls dominate; see §5 |
| F6 | **Models: Claude API first, more providers later** | Anthropic Messages API with prompt caching, plus OpenAI-compatible and local endpoints later. | Anthropic Java SDK (Apache-2.0) or cClaw `AnthropicProvider` (MIT) | Network only |
| F7 | **Telegram inbox trigger** | NotificationListenerService watches only a whitelisted chat and sender, with an optional passphrase. The reply goes out through the notification's reply action. | cClaw `CellClawNotificationListener` (MIT) | ~0; the system wakes ODCM |
| F8 | **Live voice conversation** while it works | Push-to-talk on the overlay. Speech-to-text runs only while you speak, and text-to-speech plays the narration. On-device engine first. | Android SpeechRecognizer / TTS, sherpa-onnx (Apache-2.0) | Medium, but only while talking |
| F9 | **Wake word** (optional) | sherpa-onnx keyword spotting. **Off by default**; can be limited to while charging. | sherpa-onnx (Apache-2.0) | Constant microphone use, so opt-in only |
| F10 | **3 autonomy levels + notes** | Supervised, Balanced or Full, plus a Rules note. Sensitive action classes (send, delete, pay, settings) are gated in code at each level. | cClaw `AutonomyPolicy` (MIT), Open Mine reviewed-tool gate (own code) | ~0 |
| F11 | **ODCM Guide** (Obsidian vault) | Markdown files with YAML frontmatter using Open Mine object fields. Keyword/tag index first; embeddings later if needed. | Open Mine object format (own), IAT `ContextPruner` / `MemoryVault` (own) | Low |
| F12 | **Memory review step** (Oktobot-style) | Agent-written notes start as `DRAFT` and appear in a review inbox. Only after approval or repeated success do they become `VERIFIED`/`PROVEN`. | Open Mine statuses (own) | ~0 |
| F13 | **Claude / ChatGPT data import** | Import the export ZIP, keep the raw archive untouched, distill it in batches into `Me/` notes. Shows a cost estimate before running. | DataPipeline importers (own) | One-off |
| F14 | **Hidden agent screen** (agent works while you keep using the phone) | A VirtualDisplay launched through Shizuku, with apps started on that display and gestures routed to it by `displayId`. **Experimental:** Phase 4, must be proven on the S24 Ultra. | Shizuku-API (MIT) | Medium while active |
| F15 | **Skills** (`SKILL.md`) | Load Claude-format skill folders from the vault or app storage. | cClaw `SkillParser` (MIT) | ~0 |
| F16 | **MCP servers** | MCP client for remote and local servers. | MCP Kotlin SDK (MIT → Apache-2.0) | Network only |
| F17 | **Import / export / backup** | Workspace backup and restore. The vault itself is plain files. | Open Mine `WorkspaceBackup` (own) | ~0 |
| F18 | **Scheduled tasks** | WorkManager one-off and periodic jobs, created by you, never on its own. | AndroidX WorkManager (Apache-2.0) | Run only when due |
| — | Image generation, video calls, Linux sandbox, AI browser | **Deferred.** Not needed for the bridge, and each adds weight. Revisit after v1. | | |

---

## 3. Architecture

```
            BRANCHES ↑  ODCM Guide (vault): Apps/ · Flows/ · Workflows/ · Me/ · Rules/ · Log/
                    ⇅   GuideStore (index + read/write, DRAFT→PROVEN)
              TRUNK │  TaskRunner (one task at a time, coroutines)
                    │    ├─ Planner: flow match → model loop fallback
                    │    ├─ Policy: autonomy level + Rules + hard lines (code)
                    │    └─ ModelClient: Claude (prompt-cached, streaming)
                    │  Controls: Stop · Pause · Takeover · Resume
            ROOTS ↓  ControlService (AccessibilityService, same process)
                       ├─ ScreenReader  (tree → compact element list)
                       ├─ Actor         (tap/type/swipe/global actions)
                       ├─ Overlay       (accessibility overlay window)
                       └─ Recorder      (teach mode)
                     InboxListener (NotificationListenerService → Telegram)
                     Voice (STT/TTS on demand) · Shizuku (optional, F14)
```

**Process model:** a single process. The accessibility service and the
agent share memory directly, so each tap is a function call, not a broadcast.

**Lifecycle:**
1. Idle: nothing runs. The accessibility and notification services are bound
   by the system but do no work while idle.
2. A trigger arrives (Telegram notification, overlay tap, app UI, or a due
   scheduled job), and a task starts.
3. Only during the task: a short-lived foreground service with
   `foregroundServiceType="specialUse"` and the ongoing "ODCM is working"
   notification with a Stop action. No wakelock unless the screen is off and
   a step is in flight, capped at 60 s per step.
4. When the task finishes or is stopped, the service stops itself and
   everything is released.

---

## 4. The model loop

Each step:

1. **Observe:** `ScreenReader` produces at most about 120 elements:
   `[n] role "text" (state) @bounds`, with invisible and duplicate nodes
   dropped. A screenshot is attached **only** when the model asks for one or
   the tree is empty (games, canvases, FLAG_SECURE screens give no tree).
2. **Recall:** `GuideStore` returns the top 3–5 matching notes for the
   current app and screen, plus the active task.
3. **Decide:** one model call returning **one** tool call: `tap(n)`,
   `type(n, text)`, `swipe(dir)`, `back()`, `home()`, `open_app(pkg)`,
   `wait(ms)`, `screenshot()`, `ask_user(q)`, `reply(text)` or `done(summary)`.
4. **Gate:** `Policy` checks the action against the level, the Rules and the
   hard lines. If it needs approval, the overlay or Telegram asks you, and
   **no answer counts as no.**
5. **Act** through `Actor`, then wait until the screen settles (an
   accessibility event plus a 150 ms quiet period) instead of a fixed sleep.
6. **Learn:** a success records the element identity and coordinates into
   the app's button map as `DRAFT`, or bumps its confidence.

**Limits, enforced in code:** a maximum of 40 steps per task, 3 consecutive
failed actions, a 10-minute wall clock, and an optional per-task cost limit.

**Prompt-injection rule:** screen text is passed to the model as *data* in a
delimited block and never as instructions. `Me/` notes are never typed into
another app unless the current task explicitly asked for it.

---

## 5. Performance and battery budget

| Item | Target |
|---|---|
| Idle drain attributable to ODCM | ≈ 0 (no wakelocks, alarms or polling) |
| Screen read (tree → element list) | < 30 ms on the S24 Ultra |
| Tap/type dispatch | < 50 ms |
| Overlay frame cost | No dropped frames; plain Views, updated only on state change |
| Flow replay step | No model call; 100 ms element polling, only while a flow runs |
| Model step latency | Dominated by the API; reduced with prompt caching, compact trees, streaming |
| APK size | < 15 MB for v1 without voice models (arm64-only, R8, resource shrinking) |
| Memory | < 120 MB resident during a task |

**How we keep it cool:**
- No proot or embedded Linux, no always-on TFLite or microphone, and no
  heartbeat polling. These were the causes of cClaw's heat.
- One OkHttp client with HTTP/2 and connection reuse.
- Prompt caching for the system prompt, tools and Guide context.
- arm64-v8a only, R8 full mode, and baseline profiles for startup.
- Every background job goes through WorkManager with constraints.

These are **targets**. Real numbers come from on-device measurement
(Battery Historian / `dumpsys batterystats`, Perfetto) in the phone test
checklist for each release.

---

## 6. ODCM Guide (vault) format

```
ODCM Guide/
├─ Apps/<App>/<Screen>.md     button maps (one note per screen)
├─ Flows/<name>.md            replayable step lists (no model calls)
├─ Workflows/<name>.md        higher-level recipes ("check Amazon emails")
├─ Me/                        profile, projects, people, preferences (user-approved)
├─ Rules/My rules.md          autonomy level + your notes
├─ Review/                    DRAFT inbox awaiting approval
├─ Archive/                   raw imports, untouched
└─ Log/YYYY-MM-DD.md          what it did
```

A note carries Open Mine object fields in its frontmatter:

```markdown
---
object_id: app.gmail.inbox.compose
object_type: SKILL
object_status: VERIFIED        # DRAFT → TESTED → VERIFIED → PROVEN
index_triggers: [compose, new email]
app: com.google.android.gm
screen: Inbox
element_id: com.google.android.gm:id/compose_button
element_text: Compose
last_coords: [980, 2150]
display: { w: 1440, h: 3120, density: 3.5, font_scale: 1.0 }
seen: 2026-11-15
successes: 4
---
Starts a new email. Bottom-right floating button.
```

**Coordinate rule:** coordinates are only a fast path. The element is
re-checked by id or text before every tap, and the note updates itself when
the layout moves.

**Storage:** the vault folder is chosen once through the Storage Access
Framework, so it works with Obsidian on the phone and with your sync tool of
choice. No broad storage permission is needed.

---

## 7. Security

- Accessibility commands are reachable **only inside the app process**.
  There are no exported receivers, no local HTTP port by default, and an
  optional debug bridge that is off by default and requires a token
  (claw-use pattern).
- API keys live in Android Keystore-backed encrypted storage.
- Telegram trigger: sender and chat whitelist, an optional passphrase, and
  rate limiting.
- Blocked apps (banking, password managers, and any you add) are never
  opened or operated. Password fields are never typed into; those are
  handed to you.
- Minimal permissions: accessibility, notification access, microphone (only
  for voice), post notifications, and Shizuku only for F14.
- Cleartext traffic is disabled.

---

## 8. Phases

| Phase | Delivers | Exit criteria |
|---|---|---|
| **0 · Skeleton** | New Gradle project (package and brand TBD), CI build + unit tests + lint, license notices screen | Clean build, 0 lint errors |
| **1 · Hands** | ControlService: ScreenReader, Actor, overlay with Stop | A debug screen can list elements and tap one on the S24 Ultra |
| **2 · Brain** | Claude client, TaskRunner loop, Policy with 3 levels and Rules, Pause/Resume | "Open Settings and turn on Bluetooth" works end to end |
| **3 · Inbox + voice** | Telegram trigger and reply, push-to-talk, narration | A Telegram message on the phone starts a task and gets a reply |
| **4 · Guide** | Vault store, button-map learning, flows, review inbox, Takeover teach mode | A taught flow replays with zero model calls |
| **5 · Import** | Claude/ChatGPT export import → `Me/` with a cost estimate | Distilled notes appear in Review |
| **6 · Extras** | Hidden agent screen (Shizuku), skills, MCP, scheduled tasks, wake word | Each one proven on the device |

Each phase ends with an APK and a phone test checklist (install, permissions,
the feature, a 20-minute heat/battery check).

---

## 9. Dependencies and licenses

Only these are planned. Adding one requires a license check first.

| Library | Use | License |
|---|---|---|
| AndroidX (core, activity, lifecycle, work, security-crypto) | Platform | Apache-2.0 |
| Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization | Language | Apache-2.0 |
| OkHttp | HTTP | Apache-2.0 |
| Anthropic Java SDK *(or a thin own client)* | Claude API | Apache-2.0 |
| Shizuku-API | F14 hidden screen | MIT |
| sherpa-onnx | On-device STT/TTS/wake word | Apache-2.0 |
| MCP Kotlin SDK | F16 | MIT → Apache-2.0 |
| Code adapted from cClaw, claw-use-android | F1–F5, F7, F10, F15 | MIT (notices kept) |
| Code from ether4o4's own repos (Open Mine, IAT, DataPipeline) | F11–F13, F17 | Owner's own code |

**Excluded:** nanoMuse (GPL-3.0), PalmClaw (AGPL), proot/talloc binaries
(GPL/LGPL), Oktobot (proprietary).

The app shows an **Open-source licenses** screen and bundles each MIT and
Apache notice, as those licenses require.

---

## 10. Open decisions

1. **App name, brand, and package name** (for example, `com.<you>.<app>`).
2. **Repository visibility:** this repo is **public**. A closed-source app
   needs it set to private in GitHub Settings. It also holds a copy of the
   private IAT code in `reference/iat/`.
3. **Default model:** Claude, with a configurable model per task type.
4. **Minimum Android version:** proposed Android 11 (API 30), needed for
   screenshots from accessibility and per-display gestures.
