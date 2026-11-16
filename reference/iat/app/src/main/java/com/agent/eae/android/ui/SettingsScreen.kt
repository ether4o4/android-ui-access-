package com.agent.eae.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.agent.eae.android.core.EngineConfig
import com.agent.eae.android.core.EngineKind
import com.agent.eae.android.core.memory.EmbedderFiles
import com.agent.eae.android.core.orchestrator.AgentEngine
import com.agent.eae.android.core.schedule.Scheduler
import com.agent.eae.android.data.ScheduleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by AgentEngine.config.collectAsState()

    fun push(mutate: (EngineConfig) -> EngineConfig) {
        AgentEngine.updateConfig(mutate(AgentEngine.config.value))
    }

    var baseUrl by remember { mutableStateOf(cfg.baseUrl) }
    var modelName by remember { mutableStateOf(cfg.modelName) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var modelPath by remember { mutableStateOf(cfg.modelPath) }
    var modelImportNote by remember { mutableStateOf<String?>(null) }
    var importingModel by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    var embedderReady by remember { mutableStateOf<Boolean?>(null) }
    var downloadPct by remember { mutableStateOf(-1) }
    var downloadNote by remember { mutableStateOf<String?>(null) }

    var toolCount by remember { mutableStateOf<Int?>(null) }
    var memoryCount by remember { mutableStateOf<Int?>(null) }

    var secretNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var newSecretName by remember { mutableStateOf("") }
    var newSecretValue by remember { mutableStateOf("") }
    var schedules by remember { mutableStateOf<List<ScheduleEntity>>(emptyList()) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importingModel = true
                modelImportNote = "Copying model into IAT…"
                runCatching {
                    withContext(Dispatchers.IO) {
                        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
                        val target = File(modelDir, "selected-model.litertlm")
                        val partial = File(modelDir, "selected-model.litertlm.part")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            partial.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Android could not open the selected file.")
                        if (target.exists() && !target.delete()) {
                            error("Could not replace the previous model.")
                        }
                        if (!partial.renameTo(target)) {
                            partial.copyTo(target, overwrite = true)
                            partial.delete()
                        }
                        target.absolutePath
                    }
                }.onSuccess { installedPath ->
                    modelPath = installedPath
                    push { it.copy(modelPath = installedPath) }
                    modelImportNote = "Model ready: $installedPath"
                }.onFailure { error ->
                    modelImportNote = "Model import failed: ${error.message ?: error.javaClass.simpleName}"
                }
                importingModel = false
            }
        }
    }

    LaunchedEffect(Unit) {
        embedderReady = withContext(Dispatchers.IO) { EmbedderFiles.available(context) }
        toolCount = AgentEngine.toolCount(context)
        memoryCount = AgentEngine.memoryCount(context)
        secretNames = AgentEngine.secretStore(context).names()
        schedules = Scheduler.list(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionTitle("Inference engine")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineKind.values().forEach { kind ->
                    FilterChip(
                        selected = cfg.kind == kind,
                        onClick = { push { it.copy(kind = kind) } },
                        label = { Text(labelOf(kind)) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            when (cfg.kind) {
                EngineKind.REMOTE_OPENAI_COMPATIBLE -> {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            push { c -> c.copy(baseUrl = it) }
                        },
                        label = { Text("Base URL") },
                        supportingText = { Text("OpenAI-compatible /v1 endpoint. 10.0.2.2 reaches the emulator host.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = {
                            modelName = it
                            push { c -> c.copy(modelName = it) }
                        },
                        label = { Text("Model name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            push { c -> c.copy(apiKey = it) }
                        },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = if (showKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    imageVector = if (showKey) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (showKey) "Hide key" else "Show key"
                                )
                            }
                        },
                        supportingText = { Text("Leave empty for local servers such as Ollama or llama.cpp.") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                EngineKind.ON_DEVICE_LITERT -> {
                    OutlinedTextField(
                        value = modelPath,
                        onValueChange = {
                            modelPath = it
                            push { c -> c.copy(modelPath = it) }
                        },
                        label = { Text("Model path") },
                        placeholder = { Text("/data/local/tmp/llm/gemma.litertlm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { modelPicker.launch(arrayOf("*/*")) },
                        enabled = !importingModel
                    ) {
                        Text(if (importingModel) "Copying model…" else "Choose .litertlm file")
                    }
                    modelImportNote?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Note(
                        "A multi-gigabyte weight file cannot ship inside an APK, so nothing is bundled. " +
                            "Copy a LiteRT-LM .litertlm bundle (e.g. Gemma E2B from the Hugging Face LiteRT " +
                            "community) onto the device — adb push, or the Files app into the app's own " +
                            "storage — and point this path at it. Runs on GPU when available, else CPU."
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Budgets")
            StepperSlider(
                label = "Max steps",
                display = cfg.maxSteps.toString(),
                value = cfg.maxSteps.toFloat().coerceIn(1f, 12f),
                range = 1f..12f,
                steps = 10,
                onChange = { v -> push { it.copy(maxSteps = v.roundToInt()) } }
            )
            StepperSlider(
                label = "Max prompt chars",
                display = cfg.maxPromptChars.toString(),
                value = cfg.maxPromptChars.toFloat().coerceIn(1024f, 16384f),
                range = 1024f..16384f,
                steps = 29,
                onChange = { v -> push { it.copy(maxPromptChars = v.roundToInt()) } }
            )
            StepperSlider(
                label = "Sandbox timeout",
                display = String.format(Locale.US, "%.1f s", cfg.sandboxTimeoutMs / 1000f),
                value = cfg.sandboxTimeoutMs.toFloat().coerceIn(2000f, 30000f),
                range = 2000f..30000f,
                steps = 27,
                onChange = { v -> push { it.copy(sandboxTimeoutMs = v.roundToInt().toLong()) } }
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Embeddings")
            Text(
                text = when (embedderReady) {
                    true -> "MiniLM is installed — memory recall uses real vectors."
                    false -> "MiniLM is not installed."
                    null -> "Checking…"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Note(
                "Until the MiniLM model and tokenizer are present, recall falls back to a lexical " +
                    "embedder (hashed term overlap). It works offline and costs no tokens, but ranks " +
                    "paraphrases worse than the neural model."
            )
            Spacer(Modifier.height(8.dp))
            if (downloadPct >= 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(modifier = Modifier.width(160.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("$downloadPct%", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            downloadNote = null
                            downloadPct = 0
                            val ok = withContext(Dispatchers.IO) {
                                EmbedderFiles.download(context) { p -> downloadPct = p }
                            }
                            downloadPct = -1
                            embedderReady = withContext(Dispatchers.IO) { EmbedderFiles.available(context) }
                            if (ok) {
                                withContext(Dispatchers.IO) { AgentEngine.refreshEmbedder(context) }
                            }
                            downloadNote = if (ok) {
                                "Download complete — semantic memory is active."
                            } else {
                                "Download failed — staying on the lexical fallback."
                            }
                        }
                    },
                    enabled = embedderReady != true
                ) {
                    Text(if (embedderReady == true) "Installed" else "Download MiniLM")
                }
            }
            downloadNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Secrets")
            Note(
                "API keys for the http_request tool. The model only ever sees {{secret:NAME}} " +
                    "placeholders; values are injected after it decides to call, and never enter " +
                    "the prompt. Stored in the app-private database."
            )
            Spacer(Modifier.height(8.dp))
            secretNames.forEach { name ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "{{secret:$name}}",
                        style = LogMono,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        scope.launch {
                            AgentEngine.secretStore(context).delete(name)
                            secretNames = AgentEngine.secretStore(context).names()
                        }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete $name")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newSecretName,
                    onValueChange = { newSecretName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(0.4f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = newSecretValue,
                    onValueChange = { newSecretValue = it },
                    label = { Text("Value") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(0.6f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        AgentEngine.secretStore(context).put(newSecretName, newSecretValue)
                        secretNames = AgentEngine.secretStore(context).names()
                        newSecretName = ""
                        newSecretValue = ""
                    }
                },
                enabled = newSecretName.isNotBlank() && newSecretValue.isNotBlank()
            ) {
                Text("Add secret")
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Scheduled tasks")
            if (schedules.isEmpty()) {
                Note("No scheduled tasks. The agent creates them with the schedule_task tool.")
            } else {
                schedules.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (row.intervalMinutes > 0L) {
                                    "every ${row.intervalMinutes} min"
                                } else {
                                    "once, " + DateFormat.getDateTimeInstance(
                                        DateFormat.SHORT,
                                        DateFormat.SHORT
                                    ).format(Date(row.nextRunAt))
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                Scheduler.cancel(context, row.id)
                                schedules = Scheduler.list(context)
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Cancel ${row.name}")
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Diagnostics")
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    DiagRow("Registered tools", toolCount?.toString() ?: "…")
                    DiagRow("Memory rows", memoryCount?.toString() ?: "…")
                    DiagRow("Engine", labelOf(cfg.kind))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StepperSlider(
    label: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                text = display,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun DiagRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp)
        )
        Text(text = value, style = LogMono, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun labelOf(kind: EngineKind): String = when (kind) {
    EngineKind.ON_DEVICE_LITERT -> "On-device"
    EngineKind.REMOTE_OPENAI_COMPATIBLE -> "Remote API"
}
