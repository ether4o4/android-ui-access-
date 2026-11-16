# IAT — Internet And Things

> **The simple idea:** the LLM thinks. **Context** tells it what we are doing. **Knowledge** gives it what it needs to know. **Storage** remembers everything. **Tools** let it do things.

IAT is a personal, local-first agent environment built as a **Mobile External Agentic Environment (M-EAE)**. It gives small local LLMs an external platform for tools, memory, code execution and context management so the model spends its limited context on reasoning instead of baggage.

[![Android build](https://github.com/ether4o4/IAT/actions/workflows/android.yml/badge.svg)](https://github.com/ether4o4/IAT/actions/workflows/android.yml)

**[⬇ Download the latest APK](https://github.com/ether4o4/IAT/releases/latest/download/IAT.apk)** (debug-signed, arm64, sideload only)

---

# 🧠 IAT Explained Like You're Five

Imagine a little robot with a small backpack.

The robot can think, but its backpack is small. If we put every book, conversation, instruction and tool into the backpack, the robot gets slow and confused.

So IAT keeps the big stuff outside the model and gives the robot only what it needs right now.

```text
                         IAT
                          │
              ┌───────────┼───────────┐
              ▼           ▼           ▼
          CONTEXT      KNOWLEDGE    STORAGE
              │           │           │
              └───────────┼───────────┘
                          ▼
                         LLM
                          │
                          ▼
                        TOOLS
```

**LLM = Think**

**Context = Focus** — what are we doing right now?

**Knowledge = Know** — what information do we need?

**Storage = Remember** — where does everything stay?

**Tools = Do** — how do we actually accomplish the task?

The core sentence is:

> **The LLM thinks. Context decides what it should think about. Knowledge supplies what it needs to know. Storage remembers everything. Tools let it act.**

---

# 1. LLM — The Thinker

The LLM is the reasoning engine, not the entire system.

It decides what the user wants, what information is missing, what should be retrieved, which tool should run, what happens next and when the task is complete.

The goal is not simply `BIGGER MODEL = BETTER`.

The goal is:

```text
BETTER SYSTEM + RIGHT MODEL + RIGHT CONTEXT = BETTER AGENT
```

A small model can therefore be useful when IAT handles retrieval, memory, tools, context selection, output management and execution around it.

---

# 2. CONTEXT — What Are We Doing Right Now?

Context is more than chat history. It is the **current operating brief**.

Example:

```text
Project: IAT
Goal: Build local-first agent infrastructure
Mode: Engineering
Behavior:
  - concise
  - direct
  - casual
  - proactive
  - challenge bad assumptions
Active conversations:
  - IAT architecture
  - Local LLM research
  - ESP32 experiment
Active skills:
  - coding-brief
  - architecture-brief
Needed capabilities:
  - filesystem
  - shell
  - Git
  - GitHub
  - local model
  - retrieval
```

The user can choose which projects, conversations, knowledge areas and skills are active.

Context is the **conductor**. It determines what matters now, how the model should behave, which skills apply, which tools are useful and which knowledge areas should wake up.

A future UI could look like:

```text
CONTEXT

☑ IAT project
☑ Android
☑ Kotlin
☑ Local LLM research
☑ Current conversation
☐ Music
☐ Writing
☐ Other projects
```

---

# 3. KNOWLEDGE — What Do We Know?

Knowledge is the huge library. It can eventually contain gigabytes or terabytes without forcing all of it into the active model context.

```text
KNOWLEDGE/
├── programming/
│   ├── kotlin/
│   ├── python/
│   ├── javascript/
│   ├── rust/
│   └── c/
├── android/
│   ├── compose/
│   ├── jetpack/
│   ├── adb/
│   └── services/
├── ai/
│   ├── llm/
│   ├── agents/
│   ├── tool-calling/
│   ├── quantization/
│   ├── training/
│   └── inference/
├── writing/
│   ├── songwriting/
│   ├── hooks/
│   └── structure/
└── projects/
    ├── IAT/
    ├── NeverSoft/
    └── POSH/
```

The model does not read the whole library. Context points the knowledge router toward the relevant area:

```text
USER TASK
   ↓
CURRENT CONTEXT
   ↓
KNOWLEDGE ROUTER
   ↓
RELEVANT KNOWLEDGE
   ↓
ACTIVE CONTEXT
   ↓
LLM
```

Knowledge can be hierarchical, for example `AI → Agents → Local Agents → Tool Calling → JSON Action Protocols → relevant documents`.

---

# 4. STORAGE — Where Does Everything Live?

Storage is long-term memory. It can preserve conversations, documents, source code, project files, tool results, model outputs, prompts, skills, summaries, decisions, metadata, embeddings, indexes and artifacts.

```text
STORAGE/
├── context/
├── knowledge/
├── projects/
├── conversations/
├── skills/
├── artifacts/
└── indexes/
```

The model can **forget its active context without losing the work**. The work stays stored and can be retrieved later.

```text
USER → ACTIVE CONTEXT → LLM → RESULT → STORAGE → INDEX → FUTURE RETRIEVAL
```

---

# 5. TOOLS — How Do We Actually Do It?

The model should not waste context explaining work that a tool can perform.

IAT can expose tools for files, shell commands, Git, GitHub, Python, HTTP, scheduling, memory, documents, sub-agents, local model endpoints and Android intents.

Tool schemas are discovered on demand instead of putting every tool definition into every prompt.

---

# 🎯 Skills — How Should We Work?

Skills describe **how to operate**. Knowledge describes **what is known**. Context determines **what we are doing now**.

```text
skills/
├── coding-brief
├── writing-brief
├── data-brief
├── research-brief
├── debugging-brief
└── architecture-brief
```

Example coding brief:

```text
CODING BRIEF
Goal: Modify software safely.
Priorities:
1. Understand the existing architecture.
2. Inspect before modifying.
3. Prefer minimal changes.
4. Run tests.
5. Verify behavior.
Typical tools: filesystem, shell, git, github, documentation
```

A writing brief can instead prioritize voice, structure and references. The active project context can activate the appropriate skill and use it to determine tools and knowledge.

---

# ⚡ Context Activation Pipeline

```text
                         USER
                          │
                          ▼
                    PROJECT / GOAL
                          │
                          ▼
                    ┌───────────┐
                    │  CONTEXT  │
                    │ project   │
                    │ objective │
                    │ behavior  │
                    │ skills    │
                    └─────┬─────┘
                          │
                 determines requirements
                          │
             ┌────────────┼────────────┐
             ▼            ▼            ▼
         KNOWLEDGE      TOOLS        MEMORY
             │            │            │
             └────────────┼────────────┘
                          ▼
                   ACTIVE CONTEXT
                          │
                          ▼
                         LLM
                          │
                    reasoning/action
                          │
                          ▼
                        RESULT
                          │
                          ▼
                       STORAGE
                          │
                          └──────► future retrieval
```

**Active** is the important word. Most of the system can stay inactive. Only what the current task needs reaches the LLM.

---

# 🗂️ Conversations Are Context Objects

A conversation does not have to be one giant transcript. It can be activated, deactivated, searched, summarized or attached to a project.

```text
CONTEXT
├── PROJECTS
│   ├── ☑ IAT
│   ├── ☐ NeverSoft
│   └── ☐ POSH
├── KNOWLEDGE
│   ├── ☑ Kotlin
│   ├── ☑ Android
│   ├── ☑ Agents
│   └── ☐ Music
├── CONVERSATIONS
│   ├── ☑ IAT architecture
│   ├── ☑ Local agent CLI
│   └── ☐ Old experiments
└── SKILLS
    ├── ☑ coding-brief
    └── ☑ architecture-brief
```

This is intended to make IAT feel more like a **knowledge workspace** than a normal chatbot.

---

# 🧠 The Librarian Idea

A conventional approach says: make the model larger, put more facts into its weights and give it a huge context window.

IAT says:

```text
Keep the model focused.
Keep knowledge outside it.
Retrieve what matters.
Give the model the right context.
Let tools do the heavy lifting.
Store the result for later.
```

> **Don't give the robot the whole library. Give the robot a librarian.**

The model becomes the thinker and indexer while the surrounding system handles information that does not need to be active every second.

---

# 🧠 20B Models, Specialized Knowledge and MoE

A future IAT model could be specialized for routing, retrieval, reasoning, tool use, planning, verification and context management.

Its weights provide learned behavior while external knowledge provides changing information. Framework documentation can change without requiring the entire model to be retrained.

```text
2 TB knowledge store
        ↓
   router/retrieval
        ↓
20 relevant documents
        ↓
active context
```

For neural weights, the closest established architecture is **Mixture-of-Experts (MoE)**, where a router selects specialized expert blocks instead of activating every parameter for every token.

```text
                     MODEL
                       │
                  ROUTER / GATE
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
      Coding       Writing      Android
      expert        expert       expert
```

IAT does not require an MoE model today. The design principle comes first:

> **Activate only what the current task needs.**

External knowledge is the easiest place to implement that principle. Model architecture can evolve later.

---

# 🔬 ESP32 and Tiny Hardware Direction

The ESP32 experiment that inspired this direction describes a roughly 28.9M-parameter language model running locally on highly constrained hardware through aggressive compression, careful memory allocation and a lightweight `llama2.c` inference engine.

The important lesson is not simply "an ESP32 can run an LLM."

It is:

> **The model, memory layout, inference engine and hardware can be designed together.**

On tiny hardware, every byte matters. Parameters can be compressed, slower memory can hold less time-sensitive data, and faster memory can be reserved for immediate work. TinyML systems likewise treat memory, compute and energy as first-order constraints. citeturn0search0

For IAT this suggests:

```text
                    IAT
                     │
              Agent Protocol
                     │
       ┌─────────────┼─────────────┐
       ▼             ▼             ▼
   Microcontroller   Phone       PC / VPS
       │             │             │
   tiny model      1–4B+       larger model
       │             │             │
       └─────────────┼─────────────┘
                     │
                   Tools
```

The same agent concepts could eventually scale from an inexpensive microcontroller to a phone to a full development machine.

---

# 💻 Local Agent CLI

IAT now includes a terminal-side local agent runtime under `iat-cli/`.

It speaks the OpenAI-compatible API exposed by local servers such as Ollama and implements:

```text
local LLM
   ↓
JSON action
   ↓
tool
   ↓
observation
   ↓
LLM
   ↓
final response
```

Example:

```bash
ollama pull qwen2.5:3b
python iat-cli/iat.py --model qwen2.5:3b "inspect this repository and explain it"
```

For coding work:

```bash
python iat-cli/iat.py --unsafe "inspect the repo, find the problem, fix it, and test your changes"
```

The CLI is dependency-free Python and does not require an API key when using a local Ollama-compatible server.

The first version provides workspace file inspection/read/write, safe Git inspection, optional shell execution, interactive mode, one-shot mode and step budgeting. `--unsafe` is required for writes and arbitrary shell execution.

The long-term goal is for Android and terminal runtimes to share the same action/tool protocol instead of becoming two separate agent systems.

---

# 🏗️ Current Android Architecture

| Layer | Role | Implementation |
| :-- | :-- | :-- |
| 6 UI | Live reasoning steps, terminal logs, token-savings ledger | Jetpack Compose, Material You (`ui/`) |
| 5 Orchestration | FSM run loop, context pruner, thermal/battery guard | `core/orchestrator/` + foreground service (`service/`) |
| 4 Memory vault | RAG over notes/documents, KV state | Room + ONNX MiniLM embeddings (`core/memory/`) |
| 3 Tool registry | `search_tools` meta-tool, on-demand schema hydration, intent bridge | Room catalogue (`core/tools/`) |
| 2 Sandbox | Python execution in app-private workspace, hard timeouts | Chaquopy (`core/sandbox/`, `python/eae_sandbox.py`) |
| 1 Inference | Strict-JSON decisions | LiteRT-LM `.litertlm` (on-device, GPU/CPU) or any OpenAI-compatible server (`core/inference/`) |

The model starts every run seeing **one** tool: `search_tools(query)`. Tool schemas hydrate only when matched. Tool output over roughly 300 characters is written to a log file and only the head reaches the model. Failed attempts can be pruned after a later step succeeds.

## Using it

1. Install the APK and open **Settings**.
2. Pick **Remote** for an OpenAI-compatible endpoint such as Ollama (`http://<pc-ip>:11434/v1`), llama.cpp, LM Studio or vLLM.
3. Or choose **On-device** and provide a LiteRT-LM `.litertlm` bundle.
4. Optionally download the MiniLM embedding model for semantic memory.
5. Give IAT a task and let the agent decide which tools it needs.

---

# 🧰 The Long Tail

Everything below bolts onto the tool registry:

- **Integrations / secrets** — secret placeholders can be substituted after the model emits a call so keys do not enter the prompt.
- **Scheduler** — schedule prompts later or repeatedly.
- **Sub-agents** — delegate focused work to child loops with fresh contexts.
- **Skills** — save named procedures and make them discoverable through the tool catalogue.
- **Document memory** — ingest long text, chunk it, index it and retrieve only relevant sections.

---

# 📐 Core Principle

```text
LLM       = Think
Context   = Focus
Knowledge = Know
Storage   = Remember
Tools     = Do
```

---

# 🚀 Where This Can Go

The intended direction is not merely a chatbot that runs locally. IAT is intended to become **local-first agent infrastructure** where model size and total system knowledge are no longer the same thing.

```text
                         IAT
                          │
                  ┌───────┴───────┐
                  │ FAST ROUTER   │
                  │ small local   │
                  │ model / agent │
                  └───────┬───────┘
                          │
          ┌───────────────┼────────────────┐
          ▼               ▼                ▼
       CONTEXT         KNOWLEDGE         TOOLS
          │               │                │
          └───────────────┼────────────────┘
                          ▼
                    ACTIVE CONTEXT
                          │
                          ▼
                         LLM
                          │
                          ▼
                    RESULT / ACTION
                          │
                          ▼
                       STORAGE
```

The model does not need to carry the entire world inside its weights.

**IAT carries the world around the model.**

---

## Design deviations from the original M-EAE spec

- **Vector search** is brute-force cosine over Room rather than ObjectBox/HNSWLib — at phone-scale row counts a linear scan keeps the implementation simple and avoids another native dependency.
- **LiteRT-LM** does not expose the grammar-constrained decoding hook needed for strict on-device structured output, so JSON is enforced by prompt + parse/repair retry. Remote engines can use `response_format: json_object`.
- **Intents** are allow-listed rather than a generic intent bridge, preventing arbitrary intent composition against installed apps.

## Repo layout

- `app/` — Android application (Kotlin, Compose, Chaquopy)
- `iat-cli/` — dependency-free local terminal agent
- `reference/eae_desktop.py` — runnable desktop reference of the agent layers

## Building

CI (GitHub Actions) builds and verifies the APK on every push and publishes it to the rolling `android-latest` release. Locally: JDK 17 + Android SDK 34, then `gradle assembleDebug`.
