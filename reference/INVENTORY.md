# Reference inventory

Source material gathered from ether4o4's other repositories for the ODCM
(On-Device Control Module) bridge. Nothing in `reference/` is built or wired
into the app yet. These copies exist so we can decide what to reuse.

All copies are shallow snapshots of the default branch. Build outputs,
CI workflows and large native binaries were left out.

## What was copied

| Folder | From (commit) | What ODCM gets from it |
|---|---|---|
| `cclaw/` | Public cClaw (`520c382`) | **The closest existing match.** Kotlin Android agent with an AccessibilityService (read screen, tap, swipe, type, screenshot), a NotificationListenerService, a floating overlay bubble, voice (SpeechRecognizer + TextToSpeech), a wake word, Anthropic/OpenAI/Gemini/OpenRouter/local providers, an `AgentLoop` with stop/pause/resume, 3 autonomy levels (`AutonomyPolicy`), approvals, a skill registry, and encrypted key storage. |
| `open-mine/` | Open-Mine (`24f3344`) | Strict object format (`DRAFT→PROVEN` statuses, triggers, retrieval rules) for the ODCM Guide. Reviewed tool-proposal gate, encrypted provider credentials, OpenAI-compatible client with on-device llama.cpp, workspace import/export and backup, foreground runtime service, HUD visual identity (assets and fonts). The proot `.so` libraries and the llama-server binary were left out. |
| `autonobot/` | AutoNoBot (`bb52490`) | Telegram → AI plan → phone execution, built for the S24 Ultra. Smart routing: Android intent first, then Termux:API, then screen taps. System prompt and skill library. Python, Termux-based. |
| `datapipeline/` | DataPipeline (`00b1b16`) | **Claude/ChatGPT export importers.** Claude importer, SQLite/FTS5 schema, Android `ExportImporter`/`KnowledgeDb`, and social export parsers (Snapchat, Facebook, Instagram, Discord). This covers the "file my Claude and GPT data into notes" feature. |
| `telegram-bot-setup/` | Telegram-bot-setup (`121dcf0`) | Telegram bot with an allowed-chat-ID whitelist and multi-provider config. Useful if ODCM adds a bot channel next to notification reading. `.env.example` holds placeholders only. |
| `neversoft-agent/` | NeverSoft-Agent (`3fcbbe1`) | Completion loop (`plan → act → verify → recover`), durable task state, and resumable runs. These are patterns for the agent loop: Python, concept reference. |
| `autos/` | Autos (`b77481c`) | Architecture blueprint linking Open-Mine, local inference and phone automation on the S24 Ultra. Docs only. |
| `hand-commands/` | Hand-commands (`58e16d0`) | `HandAccessibilityService.kt` gesture dispatch (tap, scroll, Home, Back) and its manifest declaration. |
| `snippets/VoiceController.java` | Voodoo-Browser (`f5ad521`) | Another voice in/out implementation to compare with cClaw's `VoiceManager`. |
| `snippets/TextNotesProvider.kt` | Spotlight (`fde83a5`) | Searches the contents of Markdown and text notes in Obsidian-style vaults. A starting point for ODCM Guide retrieval. |

## Found but not copied

**Private repo, held back because this repo is public:**

- **IAT** (private): `MemoryVault` + `Embedder`, `ContextPruner` (keeps the model's context small), `SkillStore`, `ToolRegistry`, `AgentEngine`, `AgentService`, and remote/GGUF/LiteRT inference engines. Strong fit for the ODCM Guide memory and context handling. Copying it here would publish private code.

**Checked, little or nothing for ODCM:** launchers (ForTheWin11, NeverSoft-11,
4GAuteauOS, windowslauncher, neversoft-os), file explorers, media, games,
websites, ROM/OS manifests, browsers, GPU/LLM research, investigation tools,
React web agent hubs, linuland (Termux replica; its Termux:API has
notification/TTS/STT code, but cClaw covers this natively), and
shell-ai-scripts (a large script catalog). Empty repos: Fanta, tik,
POSH-release, BlackBerryFanta. Virtual-Device-Lab, ControlRoom, Cockpit and
Algo-Ai are web/React projects with nothing Android-control specific.

## License notes

- **cClaw** is the CellClaw project ("built by members of the Harvard, MIT, and
  Sundai.Club communities"). The copy has **no LICENSE file**, so the upstream
  license is unknown. Personal use is fine. Confirm the upstream license before
  publishing or redistributing an app built from it.
- **open-mine** includes Apache-2.0 MVE code and GPL/LGPL native notices.
  See `open-mine/third-party/`.
