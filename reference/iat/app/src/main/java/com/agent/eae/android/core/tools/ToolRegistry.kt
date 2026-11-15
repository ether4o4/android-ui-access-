package com.agent.eae.android.core.tools

import com.agent.eae.android.core.ToolSpec
import com.agent.eae.android.data.EaeDatabase
import com.agent.eae.android.data.ToolEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Layer 3. The whole catalogue lives in Room; only hydrated schemas are ever rendered into the
 * prompt. `search_tools` is the single tool the model starts with.
 */
class ToolRegistry(private val db: EaeDatabase) {
    private val hydrated = LinkedHashMap<String, ToolSpec>()

    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        // Unconditional upsert so catalogue additions reach upgraded installs; skill rows survive.
        db.toolDao().upsertAll(CATALOG.map { it.toEntity() })
        if (hydrated.isEmpty()) {
            hydrate(SEARCH_TOOLS)
        }
    }

    suspend fun catalogSize(): Int = withContext(Dispatchers.IO) { db.toolDao().count() }

    fun hydratedNames(): List<String> = hydrated.keys.toList()

    suspend fun withheldCount(): Int = (catalogSize() - hydrated.size).coerceAtLeast(0)

    suspend fun hydrate(name: String): ToolSpec? = withContext(Dispatchers.IO) {
        hydrated[name] ?: db.toolDao().byName(name)?.toSpec()?.also { hydrated[name] = it }
    }

    suspend fun isHydrated(name: String): Boolean = hydrated.containsKey(name)

    suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) {
        db.toolDao().byName(name) != null
    }

    /** Hydrates the best matches and returns one compact line per tool for the model to read. */
    suspend fun searchTools(query: String, limit: Int = 3): String {
        val terms = query.lowercase()
            .split(Regex("[^a-z0-9+_.]+"))
            .filter { it.length > 1 }
            .ifEmpty { listOf(query.lowercase().trim()) }

        val hits = LinkedHashMap<String, ToolEntity>()
        withContext(Dispatchers.IO) {
            for (term in terms) {
                if (hits.size >= limit) break
                for (hit in db.toolDao().search(term, limit)) {
                    if (hit.name != SEARCH_TOOLS) hits.putIfAbsent(hit.name, hit)
                }
            }
        }
        if (hits.isEmpty()) {
            return "No tool matched \"$query\". Available categories: " +
                CATALOG.map { it.category }.distinct().joinToString(", ") +
                ". Try one of those words, or answer with final_response."
        }
        val chosen = hits.values.take(limit)
        chosen.forEach { hydrated[it.name] = it.toSpec() }
        return chosen.joinToString("\n") { "${it.name}: ${it.description}" }
    }

    fun activeSchemasJson(): String {
        val array = JSONArray()
        hydrated.values.forEach { array.put(JSONObject(it.schemaJson)) }
        return array.toString()
    }

    private fun ToolSpec.toEntity() = ToolEntity(
        name = name,
        description = description,
        keywords = keywords.joinToString(","),
        schemaJson = schemaJson,
        category = category,
    )

    private fun ToolEntity.toSpec() = ToolSpec(
        name = name,
        description = description,
        keywords = keywords.split(",").filter { it.isNotBlank() },
        schemaJson = schemaJson,
        category = category,
    )

    companion object {
        const val SEARCH_TOOLS = "search_tools"
        const val EXECUTE_PYTHON = "execute_python"
        const val LIST_WORKSPACE = "list_workspace"
        const val READ_WORKSPACE_FILE = "read_workspace_file"
        const val MEMORY_SEARCH = "memory_search"
        const val MEMORY_WRITE = "memory_write"
        const val DEVICE_STATE = "device_state"
        const val NOTIFY = "post_notification"
        const val OPEN_URL = "open_url"
        const val SHARE_TEXT = "share_text"
        const val LAUNCH_APP = "launch_app"
        const val HTTP_REQUEST = "http_request"
        const val SCHEDULE_TASK = "schedule_task"
        const val LIST_SCHEDULES = "list_schedules"
        const val CANCEL_SCHEDULE = "cancel_schedule"
        const val SPAWN_SUBAGENT = "spawn_subagent"
        const val SAVE_SKILL = "save_skill"
        const val RUN_SKILL = "run_skill"
        const val MEMORY_INGEST = "memory_ingest"

        private fun schema(name: String, description: String, props: JSONObject, required: List<String>): String =
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", JSONArray(required)),
                )
                .toString()

        private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

        private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)

        val CATALOG: List<ToolSpec> = listOf(
            ToolSpec(
                name = SEARCH_TOOLS,
                description = "Find a tool by keyword. Always the first step when you need a capability.",
                keywords = listOf("tool", "search", "find", "capability"),
                schemaJson = schema(
                    SEARCH_TOOLS,
                    "Search the device tool catalogue by keyword.",
                    JSONObject().put("query", str("What you need to do, e.g. 'python csv' or 'battery'.")),
                    listOf("query"),
                ),
                category = "meta",
            ),
            ToolSpec(
                name = EXECUTE_PYTHON,
                description = "Run Python 3 in the app's private workspace. Use for any maths, parsing, or file work.",
                keywords = listOf("python", "script", "code", "csv", "json", "maths", "math", "calculate", "data", "parse", "file", "write"),
                schemaJson = schema(
                    EXECUTE_PYTHON,
                    "Execute a short Python script. print() what you need to see; cwd is the workspace.",
                    JSONObject().put("code", str("The Python source to run.")),
                    listOf("code"),
                ),
                category = "sandbox",
            ),
            ToolSpec(
                name = LIST_WORKSPACE,
                description = "List files the agent has created in its private workspace.",
                keywords = listOf("list", "files", "workspace", "ls", "directory"),
                schemaJson = schema(LIST_WORKSPACE, "List workspace files.", JSONObject(), emptyList()),
                category = "sandbox",
            ),
            ToolSpec(
                name = READ_WORKSPACE_FILE,
                description = "Read back part of a workspace file that a script wrote.",
                keywords = listOf("read", "open", "file", "cat", "workspace"),
                schemaJson = schema(
                    READ_WORKSPACE_FILE,
                    "Read up to a few thousand characters of a workspace file.",
                    JSONObject()
                        .put("name", str("File name inside the workspace."))
                        .put("max_chars", int("Optional cap, default 2000.")),
                    listOf("name"),
                ),
                category = "sandbox",
            ),
            ToolSpec(
                name = MEMORY_SEARCH,
                description = "Semantic search over stored notes and ingested documents.",
                keywords = listOf("memory", "recall", "remember", "notes", "document", "rag", "search"),
                schemaJson = schema(
                    MEMORY_SEARCH,
                    "Retrieve the most relevant stored snippets.",
                    JSONObject()
                        .put("query", str("What to look for."))
                        .put("top_k", int("How many snippets, default 3.")),
                    listOf("query"),
                ),
                category = "memory",
            ),
            ToolSpec(
                name = MEMORY_WRITE,
                description = "Save a durable fact or preference for future sessions.",
                keywords = listOf("memory", "save", "store", "remember", "note", "fact"),
                schemaJson = schema(
                    MEMORY_WRITE,
                    "Store a short fact in the on-device vault.",
                    JSONObject().put("text", str("The fact to store, one or two sentences.")),
                    listOf("text"),
                ),
                category = "memory",
            ),
            ToolSpec(
                name = DEVICE_STATE,
                description = "Read battery level, charging state, thermal status, network type and free storage.",
                keywords = listOf("battery", "device", "thermal", "temperature", "storage", "disk", "wifi", "network", "state", "system"),
                schemaJson = schema(DEVICE_STATE, "Query current device state.", JSONObject(), emptyList()),
                category = "device",
            ),
            ToolSpec(
                name = NOTIFY,
                description = "Post an Android notification to get the user's attention.",
                keywords = listOf("notify", "notification", "alert", "remind", "tell"),
                schemaJson = schema(
                    NOTIFY,
                    "Post a notification.",
                    JSONObject()
                        .put("title", str("Short title."))
                        .put("body", str("Notification body.")),
                    listOf("title", "body"),
                ),
                category = "device",
            ),
            ToolSpec(
                name = OPEN_URL,
                description = "Open an http or https URL in the user's browser.",
                keywords = listOf("open", "url", "link", "browser", "web", "site"),
                schemaJson = schema(
                    OPEN_URL,
                    "Open a web URL. Only http and https are permitted.",
                    JSONObject().put("url", str("Absolute http(s) URL.")),
                    listOf("url"),
                ),
                category = "intent",
            ),
            ToolSpec(
                name = SHARE_TEXT,
                description = "Hand text to Android's share sheet so the user can send it anywhere.",
                keywords = listOf("share", "send", "text", "sheet", "export"),
                schemaJson = schema(
                    SHARE_TEXT,
                    "Open the system share sheet with some text.",
                    JSONObject().put("text", str("Text to share.")),
                    listOf("text"),
                ),
                category = "intent",
            ),
            ToolSpec(
                name = LAUNCH_APP,
                description = "Launch an installed app by its package name.",
                keywords = listOf("launch", "open", "app", "package", "start"),
                schemaJson = schema(
                    LAUNCH_APP,
                    "Launch an installed app.",
                    JSONObject().put("package", str("Android package name, e.g. com.android.settings.")),
                    listOf("package"),
                ),
                category = "intent",
            ),
            ToolSpec(
                name = HTTP_REQUEST,
                description = "Call any web API or webhook. Use {{secret:NAME}} placeholders for stored API keys.",
                keywords = listOf("http", "api", "fetch", "request", "web", "get", "post", "webhook", "integration", "rest", "call"),
                schemaJson = schema(
                    HTTP_REQUEST,
                    "Make an HTTP request. Secrets like {{secret:GITHUB_TOKEN}} are injected after you " +
                        "emit the call, so never ask for or guess raw key values.",
                    JSONObject()
                        .put("url", str("Absolute http(s) URL. May contain {{secret:NAME}}."))
                        .put("method", str("GET, POST, PUT, PATCH or DELETE. Default GET."))
                        .put("headers", JSONObject().put("type", "object").put("description", "Header map. Values may contain {{secret:NAME}}."))
                        .put("body", str("Request body for POST/PUT/PATCH.")),
                    listOf("url"),
                ),
                category = "integration",
            ),
            ToolSpec(
                name = SCHEDULE_TASK,
                description = "Run an agent task later, once or on a repeating interval, with the result as a notification.",
                keywords = listOf("schedule", "later", "cron", "recurring", "remind", "reminder", "timer", "daily", "hourly", "repeat"),
                schemaJson = schema(
                    SCHEDULE_TASK,
                    "Schedule a future agent run. Repeating intervals have a 15-minute floor.",
                    JSONObject()
                        .put("name", str("Short label for the task."))
                        .put("prompt", str("The task the future agent run should perform."))
                        .put("delay_minutes", int("Minutes until the first run. Default 1."))
                        .put("repeat_minutes", int("Repeat interval in minutes; 0 or absent = run once.")),
                    listOf("name", "prompt"),
                ),
                category = "schedule",
            ),
            ToolSpec(
                name = LIST_SCHEDULES,
                description = "List the scheduled agent tasks.",
                keywords = listOf("schedule", "list", "pending", "tasks", "timers"),
                schemaJson = schema(LIST_SCHEDULES, "List scheduled tasks.", JSONObject(), emptyList()),
                category = "schedule",
            ),
            ToolSpec(
                name = CANCEL_SCHEDULE,
                description = "Cancel a scheduled agent task by id.",
                keywords = listOf("schedule", "cancel", "delete", "remove", "stop"),
                schemaJson = schema(
                    CANCEL_SCHEDULE,
                    "Cancel a scheduled task.",
                    JSONObject().put("id", str("Schedule id from list_schedules.")),
                    listOf("id"),
                ),
                category = "schedule",
            ),
            ToolSpec(
                name = SPAWN_SUBAGENT,
                description = "Delegate a focused sub-task to a child agent with a fresh context window; only its final answer returns.",
                keywords = listOf("subagent", "delegate", "agent", "spawn", "research", "subtask", "focus"),
                schemaJson = schema(
                    SPAWN_SUBAGENT,
                    "Run a sub-task in an isolated child agent. Use for work whose intermediate output " +
                        "would flood your context. State the task fully; the child shares tools and memory " +
                        "but none of this conversation.",
                    JSONObject().put("task", str("Complete, self-contained instructions for the child agent.")),
                    listOf("task"),
                ),
                category = "meta",
            ),
            ToolSpec(
                name = SAVE_SKILL,
                description = "Save a reusable named procedure (instructions plus optional Python) as a new tool.",
                keywords = listOf("skill", "save", "create", "procedure", "reusable", "workflow", "teach", "define"),
                schemaJson = schema(
                    SAVE_SKILL,
                    "Store a skill. It becomes discoverable via search_tools as skill:<name>.",
                    JSONObject()
                        .put("name", str("Short skill name, e.g. daily-summary."))
                        .put("description", str("One sentence: what the skill does and when to use it."))
                        .put("instructions", str("Steps to follow when the skill runs."))
                        .put("python", str("Optional Python source executed in the sandbox when the skill runs; INPUT holds the input string.")),
                    listOf("name", "description", "instructions"),
                ),
                category = "skill",
            ),
            ToolSpec(
                name = RUN_SKILL,
                description = "Run a saved skill by name.",
                keywords = listOf("skill", "run", "execute", "use", "apply"),
                schemaJson = schema(
                    RUN_SKILL,
                    "Run a saved skill. Python-backed skills execute in the sandbox; instruction-only " +
                        "skills return their steps for you to follow.",
                    JSONObject()
                        .put("name", str("The skill name."))
                        .put("input", str("Optional input string, available to skill Python as INPUT.")),
                    listOf("name"),
                ),
                category = "skill",
            ),
            ToolSpec(
                name = MEMORY_INGEST,
                description = "Chunk and embed a longer document into the memory vault for later semantic recall.",
                keywords = listOf("ingest", "document", "rag", "chunk", "store", "text", "knowledge", "import"),
                schemaJson = schema(
                    MEMORY_INGEST,
                    "Ingest a document into memory. Later runs can recall the relevant paragraphs " +
                        "without the document ever entering the prompt whole.",
                    JSONObject()
                        .put("text", str("The document text."))
                        .put("source", str("Optional label, e.g. a filename or URL.")),
                    listOf("text"),
                ),
                category = "memory",
            ),
        )
    }
}
