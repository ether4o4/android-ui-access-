package com.agent.eae.android.core.orchestrator

import android.content.Context
import com.agent.eae.android.core.AgentAction
import com.agent.eae.android.core.AgentException
import com.agent.eae.android.core.EngineConfig
import com.agent.eae.android.core.EngineKind
import com.agent.eae.android.core.LogKind
import com.agent.eae.android.core.LogLine
import com.agent.eae.android.core.Role
import com.agent.eae.android.core.RunState
import com.agent.eae.android.core.TokenLedger
import com.agent.eae.android.core.ToolResult
import com.agent.eae.android.core.Turn
import com.agent.eae.android.core.inference.EngineFactory
import com.agent.eae.android.core.inference.InferenceEngine
import com.agent.eae.android.core.inference.JsonActions
import com.agent.eae.android.core.memory.EmbedderFactory
import com.agent.eae.android.core.memory.MemoryVault
import com.agent.eae.android.core.sandbox.PythonSandbox
import com.agent.eae.android.core.schedule.Scheduler
import com.agent.eae.android.core.tools.DeviceTools
import com.agent.eae.android.core.tools.HttpTool
import com.agent.eae.android.core.tools.SecretStore
import com.agent.eae.android.core.tools.SkillStore
import com.agent.eae.android.core.tools.ToolRegistry
import com.agent.eae.android.data.EaeDatabase
import com.agent.eae.android.service.AgentService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Layer 5. Owns the run loop, the transcript, and every decision about what is allowed to reach the
 * model. The UI observes this; it never talks to the layers underneath directly.
 *
 * The same loop serves three callers: interactive runs (submit), scheduled headless runs
 * (runHeadless, invoked by AgentWorker), and child agents (spawn_subagent dispatch). Children
 * borrow the parent's inference engine — loading a second multi-GB on-device model would OOM the
 * phone — but get a fresh transcript, which is the whole point: their intermediate noise never
 * enters the parent's context window.
 */
object AgentEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _state = MutableStateFlow(RunState.IDLE)
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _ledger = MutableStateFlow(TokenLedger())
    val ledger: StateFlow<TokenLedger> = _ledger.asStateFlow()

    private val _config = MutableStateFlow(EngineConfig())
    val config: StateFlow<EngineConfig> = _config.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var appContext: Context? = null
    private var db: EaeDatabase? = null
    private var registry: ToolRegistry? = null
    private var sandbox: PythonSandbox? = null
    private var device: DeviceTools? = null
    private var vault: MemoryVault? = null
    private var secrets: SecretStore? = null
    private var http: HttpTool? = null
    private var skills: SkillStore? = null
    private var runJob: Job? = null

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val database = EaeDatabase.get(app)
        db = database
        registry = ToolRegistry(database)
        sandbox = PythonSandbox(app)
        device = DeviceTools(app)
        vault = MemoryVault(database, EmbedderFactory.create(app))
        secrets = SecretStore(database)
        http = HttpTool(SecretStore(database), File(app.filesDir, "logs").apply { mkdirs() })
        skills = SkillStore(database)

        scope.launch {
            registry?.ensureSeeded()
            loadConfig(database)
        }
    }

    fun updateConfig(cfg: EngineConfig) {
        _config.value = cfg
        val database = db ?: return
        scope.launch { persistConfig(database, cfg) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        _ledger.value = TokenLedger()
    }

    fun cancel() {
        runJob?.cancel()
    }

    /** Called after the MiniLM download finishes so semantic memory starts without an app restart. */
    fun refreshEmbedder(context: Context) {
        init(context)
        vault?.swapEmbedder(EmbedderFactory.create(context.applicationContext))
    }

    fun secretStore(context: Context): SecretStore {
        init(context)
        return checkNotNull(secrets)
    }

    suspend fun toolCount(context: Context): Int {
        init(context)
        return registry?.catalogSize() ?: 0
    }

    suspend fun memoryCount(context: Context): Int {
        init(context)
        val database = db ?: return 0
        return withContext(Dispatchers.IO) { database.memoryDao().count() }
    }

    fun submit(context: Context, query: String) {
        init(context)
        if (_isRunning.value) return
        val app = appContext ?: return
        AgentService.start(app)
        runJob = scope.launch {
            _isRunning.value = true
            try {
                runLoop(
                    query = query,
                    depth = 0,
                    emit = ::log,
                    setState = { _state.value = it },
                    borrowedEngine = null,
                )
            } catch (e: CancellationException) {
                log(LogKind.SYSTEM, "Run cancelled.")
                _state.value = RunState.IDLE
            } catch (e: Exception) {
                log(LogKind.ERROR, e.message ?: e.javaClass.simpleName)
                _state.value = RunState.ERROR
            } finally {
                _isRunning.value = false
                AgentService.stop(app)
            }
        }
    }

    /**
     * Runs a complete task with no UI attached and returns the final answer. Used by AgentWorker
     * for scheduled tasks. Never throws; failures come back as a readable string.
     */
    suspend fun runHeadless(context: Context, query: String): String {
        init(context)
        return try {
            runLoop(
                query = query,
                depth = 0,
                emit = { _, _ -> },
                setState = { },
                borrowedEngine = null,
            )
        } catch (e: Exception) {
            "Task failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun runLoop(
        query: String,
        depth: Int,
        emit: (LogKind, String) -> Unit,
        setState: (RunState) -> Unit,
        borrowedEngine: InferenceEngine?,
    ): String {
        val cfg = _config.value
        val reg = registry ?: throw AgentException("Tool registry unavailable.")
        val box = sandbox ?: throw AgentException("Sandbox unavailable.")
        val memory = vault ?: throw AgentException("Memory vault unavailable.")

        reg.ensureSeeded()
        if (depth == 0) clearRunLedger()
        emit(LogKind.USER, query)

        val pruner = ContextPruner(cfg.maxPromptChars)
        val transcript = mutableListOf(Turn(Role.USER, query))
        val ownsEngine = borrowedEngine == null
        var engine: InferenceEngine? = borrowedEngine

        try {
            if (engine == null) {
                engine = EngineFactory.create(requireContext(), cfg)
                emit(LogKind.SYSTEM, "Inference: ${engine.label}")
            }

            // RAG happens before the first generation so a long document never enters the prompt.
            val recalled = memory.recallAsContext(query, topK = 3, maxChars = 900)
            if (recalled.isNotBlank()) {
                emit(LogKind.SYSTEM, "Recalled ${recalled.length} chars of memory as context.")
            }

            var step = 1
            while (step <= cfg.maxSteps) {
                awaitResourceHeadroom(emit, setState)
                setState(RunState.PLANNING)
                emit(LogKind.STATE, "Step $step of ${cfg.maxSteps}")

                pruner.markResolvedFailures(transcript)
                val pruned = pruner.prune(transcript)
                val systemPrompt = JsonActions.buildSystemPrompt(
                    activeSchemasJson = reg.activeSchemasJson(),
                    memoryContext = recalled,
                    workspaceNote = "Workspace: ${box.workspace.absolutePath}",
                )
                noteLedger(
                    promptChars = systemPrompt.length + pruned.turns.sumOf { it.content.length },
                    pruned = pruned.charsRemoved,
                    withheld = reg.withheldCount(),
                )

                val raw = engine.generate(systemPrompt, pruned.turns)
                var action = JsonActions.parse(raw)
                if (action == null) {
                    emit(LogKind.ERROR, "Model output was not usable JSON; asking it to repair.")
                    transcript += Turn(Role.USER, JsonActions.repairPrompt(raw), step, prunable = true)
                    val retry = engine.generate(systemPrompt, pruner.prune(transcript).turns)
                    action = JsonActions.parse(retry)
                    if (action == null) {
                        val answer = rawTextFallback(retry).ifBlank { rawTextFallback(raw) }
                        setState(RunState.DONE)
                        emit(LogKind.SYSTEM, "Model did not return agent JSON; showing raw response.")
                        emit(LogKind.FINAL, answer)
                        if (depth == 0) {
                            memory.remember("turn", "Task: $query\nAnswer: $answer", source = "session")
                        }
                        return answer
                    }
                }

                if (action.thought.isNotBlank()) emit(LogKind.THOUGHT, action.thought)

                if (action.action == AgentAction.FINAL) {
                    val answer = action.args.optString("response").ifBlank {
                        action.args.optString("text").ifBlank { "(the model returned an empty answer)" }
                    }
                    setState(RunState.DONE)
                    emit(LogKind.FINAL, answer)
                    if (depth == 0) {
                        memory.remember("turn", "Task: $query\nAnswer: $answer", source = "session")
                    }
                    return answer
                }

                setState(RunState.ACTING)
                emit(LogKind.ACTION, "${action.action} ${compactArgs(action.args)}")

                val result = dispatch(action, cfg, reg, box, memory, engine, depth, emit)

                setState(RunState.OBSERVING)
                emit(if (result.ok) LogKind.TOOL else LogKind.ERROR, result.summary)
                result.logPath?.let { noteOffload(result.full.length - result.summary.length) }

                transcript += Turn(Role.ASSISTANT, raw, step)
                transcript += Turn(Role.TOOL, result.summary, step)

                setState(RunState.PRUNING)
                step++
            }

            setState(RunState.DONE)
            val exhausted = "Step budget of ${cfg.maxSteps} exhausted without a final answer. " +
                "Raise Max steps in Settings, or restate the task more narrowly."
            emit(LogKind.FINAL, exhausted)
            return exhausted
        } finally {
            if (ownsEngine) engine?.close()
        }
    }

    private suspend fun dispatch(
        action: AgentAction,
        cfg: EngineConfig,
        reg: ToolRegistry,
        box: PythonSandbox,
        memory: MemoryVault,
        engine: InferenceEngine,
        depth: Int,
        emit: (LogKind, String) -> Unit,
    ): ToolResult {
        val dev = device ?: return ToolResult("Device tools unavailable.", "", false)
        val args = action.args
        return when (action.action) {
            ToolRegistry.SEARCH_TOOLS -> {
                val found = reg.searchTools(args.optString("query"))
                ToolResult(found, found, true)
            }

            ToolRegistry.EXECUTE_PYTHON -> {
                val code = args.optString("code")
                if (code.isBlank()) {
                    ToolResult("No code supplied. Pass the script in args.code.", "", false)
                } else {
                    box.execute(code, cfg.sandboxTimeoutMs)
                }
            }

            ToolRegistry.LIST_WORKSPACE -> {
                val listing = box.listWorkspace()
                ToolResult(listing.take(600), listing, true)
            }

            ToolRegistry.READ_WORKSPACE_FILE -> {
                val name = args.optString("name")
                val cap = args.optInt("max_chars", 2000).coerceIn(200, 8000)
                val body = box.readWorkspaceFile(name, cap)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val ok = json?.optBoolean("ok") ?: false
                val text = if (ok) json?.optString("text").orEmpty() else json?.optString("error").orEmpty()
                ToolResult(text.take(600), text, ok)
            }

            ToolRegistry.MEMORY_SEARCH -> {
                val hits = memory.recall(args.optString("query"), args.optInt("top_k", 3).coerceIn(1, 8))
                if (hits.isEmpty()) {
                    ToolResult("Nothing relevant stored yet.", "", true)
                } else {
                    val text = hits.joinToString("\n") { (row, score) ->
                        "(${"%.2f".format(score)}) ${row.text.take(240)}"
                    }
                    ToolResult(text.take(900), text, true)
                }
            }

            ToolRegistry.MEMORY_WRITE -> {
                val text = args.optString("text")
                if (text.isBlank()) {
                    ToolResult("Nothing to store: args.text was empty.", "", false)
                } else {
                    memory.remember("fact", text, source = "agent")
                    ToolResult("Stored.", text, true)
                }
            }

            ToolRegistry.MEMORY_INGEST -> {
                val text = args.optString("text")
                if (text.isBlank()) {
                    ToolResult("Nothing to ingest: args.text was empty.", "", false)
                } else {
                    val source = args.optString("source").ifBlank { "ingested" }
                    val chunks = memory.ingestDocument(text, source)
                    ToolResult("Ingested $chunks chunks from \"$source\" into memory.", "", true)
                }
            }

            ToolRegistry.HTTP_REQUEST -> {
                val tool = http ?: return ToolResult("HTTP tool unavailable.", "", false)
                tool.request(args)
            }

            ToolRegistry.SCHEDULE_TASK -> {
                val name = args.optString("name")
                val prompt = args.optString("prompt")
                if (name.isBlank() || prompt.isBlank()) {
                    ToolResult("schedule_task needs both args.name and args.prompt.", "", false)
                } else {
                    val delay = args.optLong("delay_minutes", 1L).coerceIn(0L, 525_600L)
                    val repeat = args.optLong("repeat_minutes", 0L).coerceIn(0L, 525_600L)
                    val id = Scheduler.schedule(requireContext(), name, prompt, delay, repeat)
                    val repeatNote = if (repeat > 0L) {
                        " repeating every ${repeat.coerceAtLeast(15L)} min (15-min floor)"
                    } else {
                        ""
                    }
                    ToolResult("Scheduled \"$name\" (id $id): first run in $delay min$repeatNote.", "", true)
                }
            }

            ToolRegistry.LIST_SCHEDULES -> {
                val rows = Scheduler.list(requireContext())
                if (rows.isEmpty()) {
                    ToolResult("No scheduled tasks.", "", true)
                } else {
                    val text = rows.joinToString("\n") { row ->
                        val cadence = if (row.intervalMinutes > 0L) "every ${row.intervalMinutes} min" else "once"
                        "${row.id.take(8)}… \"${row.name}\" $cadence, next around ${row.nextRunAt}"
                    }
                    ToolResult(text.take(900), text, true)
                }
            }

            ToolRegistry.CANCEL_SCHEDULE -> {
                val id = resolveScheduleId(args.optString("id"))
                if (id == null) {
                    ToolResult("No schedule matches that id. Use list_schedules first.", "", false)
                } else {
                    val removed = Scheduler.cancel(requireContext(), id)
                    ToolResult(if (removed) "Cancelled $id." else "Nothing cancelled for $id.", "", removed)
                }
            }

            ToolRegistry.SPAWN_SUBAGENT -> {
                if (depth >= MAX_SUBAGENT_DEPTH) {
                    ToolResult(
                        "Refused: sub-agents cannot spawn further sub-agents. Do this step yourself.",
                        "",
                        false,
                    )
                } else {
                    val task = args.optString("task")
                    if (task.isBlank()) {
                        ToolResult("spawn_subagent needs args.task.", "", false)
                    } else {
                        emit(LogKind.SYSTEM, "Spawning sub-agent (depth ${depth + 1}).")
                        val answer = runLoop(
                            query = task,
                            depth = depth + 1,
                            emit = { kind, text -> emit(kind, "⤷ $text") },
                            setState = { },
                            borrowedEngine = engine,
                        )
                        ToolResult("Sub-agent finished: ${answer.take(600)}", answer, true)
                    }
                }
            }

            ToolRegistry.SAVE_SKILL -> {
                val store = skills ?: return ToolResult("Skill store unavailable.", "", false)
                val message = store.save(
                    name = args.optString("name"),
                    description = args.optString("description"),
                    instructions = args.optString("instructions"),
                    python = args.optString("python").ifBlank { null },
                )
                ToolResult(message, message, message.startsWith("Saved"))
            }

            ToolRegistry.RUN_SKILL -> runSkill(args.optString("name"), args.optString("input"), cfg, box)

            ToolRegistry.DEVICE_STATE -> dev.deviceState()
            ToolRegistry.NOTIFY -> dev.postNotification(args.optString("title"), args.optString("body"))
            ToolRegistry.OPEN_URL -> dev.openUrl(args.optString("url"))
            ToolRegistry.SHARE_TEXT -> dev.shareText(args.optString("text"))
            ToolRegistry.LAUNCH_APP -> dev.launchApp(args.optString("package"))

            else -> {
                if (action.action.startsWith("skill:")) {
                    runSkill(action.action.removePrefix("skill:"), args.optString("input"), cfg, box)
                } else {
                    val known = reg.exists(action.action)
                    val hint = if (known) {
                        "Tool ${action.action} exists but has no handler in this build."
                    } else {
                        "No such tool: ${action.action}. Call search_tools first."
                    }
                    ToolResult(hint, hint, false)
                }
            }
        }
    }

    private suspend fun runSkill(
        name: String,
        input: String,
        cfg: EngineConfig,
        box: PythonSandbox,
    ): ToolResult {
        val store = skills ?: return ToolResult("Skill store unavailable.", "", false)
        if (name.isBlank()) return ToolResult("run_skill needs args.name.", "", false)
        val skill = store.get(name)
            ?: return ToolResult("No skill named \"$name\". Find skills via search_tools.", "", false)
        val python = skill.python
        return if (python != null) {
            val prelude = "INPUT = " + JSONObject.quote(input) + "\n"
            box.execute(prelude + python, cfg.sandboxTimeoutMs)
        } else {
            val text = "Follow these steps for skill \"${skill.name}\":\n${skill.instructions}"
            ToolResult(text.take(900), text, true)
        }
    }

    /** Accepts the truncated ids shown by list_schedules ("a1b2c3d4…") as well as full ids. */
    private suspend fun resolveScheduleId(raw: String): String? {
        val id = raw.trim().trimEnd('…', '.')
        if (id.isBlank()) return null
        val rows = Scheduler.list(requireContext())
        return rows.firstOrNull { it.id == id }?.id
            ?: rows.firstOrNull { it.id.startsWith(id) }?.id
    }

    /** Layer 5 resource guard: heat and a dying battery pause the loop rather than cooking the phone. */
    private suspend fun awaitResourceHeadroom(
        emit: (LogKind, String) -> Unit,
        setState: (RunState) -> Unit,
    ) {
        val dev = device ?: return
        var waited = 0L
        while (true) {
            val state = runCatching { JSONObject(dev.deviceState().summary) }.getOrNull() ?: return
            val battery = state.optInt("battery_percent", 100)
            val charging = state.optBoolean("charging", false)
            val thermal = state.optString("thermal_status", "none")
            val hotEnough = thermal in setOf("severe", "critical", "emergency", "shutdown")
            val flat = battery in 1..14 && !charging
            if (!hotEnough && !flat) return

            if (waited >= MAX_RESOURCE_WAIT_MS) {
                throw AgentException(
                    if (hotEnough) {
                        "Paused and then stopped: device thermal status is $thermal. Let it cool down."
                    } else {
                        "Paused and then stopped: battery is at $battery% and not charging."
                    },
                )
            }
            if (waited == 0L) {
                setState(RunState.PAUSED_RESOURCE)
                emit(
                    LogKind.SYSTEM,
                    if (hotEnough) "Paused: thermal status $thermal." else "Paused: battery at $battery%.",
                )
            }
            delay(RESOURCE_POLL_MS)
            waited += RESOURCE_POLL_MS
        }
    }

    private fun rawTextFallback(raw: String): String =
        raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .ifBlank { "(the model returned an empty answer)" }

    private fun compactArgs(args: JSONObject): String =
        args.toString().let { if (it.length > 160) it.take(160) + "…" else it }

    private fun requireContext(): Context = appContext ?: throw AgentException("Engine not initialised.")

    private fun clearRunLedger() {
        _ledger.value = TokenLedger()
    }

    private fun noteLedger(promptChars: Int, pruned: Int, withheld: Int) {
        _ledger.value = _ledger.value.copy(
            promptCharsSent = promptChars,
            charsPruned = _ledger.value.charsPruned + pruned,
            schemasWithheld = withheld,
        )
    }

    private fun noteOffload(chars: Int) {
        if (chars <= 0) return
        _ledger.value = _ledger.value.copy(
            charsOffloadedToFiles = _ledger.value.charsOffloadedToFiles + chars,
        )
    }

    private fun log(kind: LogKind, text: String) {
        _logs.value = _logs.value + LogLine(kind, text, System.currentTimeMillis())
    }

    private suspend fun loadConfig(database: EaeDatabase) {
        val stored = withContext(Dispatchers.IO) { database.kvDao().get(CONFIG_KEY)?.value } ?: return
        val json = runCatching { JSONObject(stored) }.getOrNull() ?: return
        val default = EngineConfig()
        _config.value = EngineConfig(
            kind = runCatching { EngineKind.valueOf(json.optString("kind")) }.getOrDefault(default.kind),
            modelPath = json.optString("modelPath", default.modelPath),
            baseUrl = json.optString("baseUrl", default.baseUrl),
            apiKey = json.optString("apiKey", default.apiKey),
            modelName = json.optString("modelName", default.modelName),
            maxPromptChars = json.optInt("maxPromptChars", default.maxPromptChars),
            maxSteps = json.optInt("maxSteps", default.maxSteps),
            sandboxTimeoutMs = json.optLong("sandboxTimeoutMs", default.sandboxTimeoutMs),
        )
    }

    private suspend fun persistConfig(database: EaeDatabase, cfg: EngineConfig) {
        val json = JSONObject()
            .put("kind", cfg.kind.name)
            .put("modelPath", cfg.modelPath)
            .put("baseUrl", cfg.baseUrl)
            .put("apiKey", cfg.apiKey)
            .put("modelName", cfg.modelName)
            .put("maxPromptChars", cfg.maxPromptChars)
            .put("maxSteps", cfg.maxSteps)
            .put("sandboxTimeoutMs", cfg.sandboxTimeoutMs)
        withContext(Dispatchers.IO) {
            database.kvDao().put(
                com.agent.eae.android.data.KvEntity(CONFIG_KEY, json.toString(), System.currentTimeMillis()),
            )
        }
    }

    private const val CONFIG_KEY = "engine_config"
    private const val RESOURCE_POLL_MS = 5_000L
    private const val MAX_RESOURCE_WAIT_MS = 60_000L
    private const val MAX_SUBAGENT_DEPTH = 1
}
