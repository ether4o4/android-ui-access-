package com.agent.eae.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.agent.eae.android.core.LogKind
import com.agent.eae.android.core.LogLine
import com.agent.eae.android.core.RunState
import com.agent.eae.android.core.TokenLedger
import com.agent.eae.android.core.orchestrator.AgentEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ExampleTasks = listOf(
    "List the files in my sandbox and summarise what is there",
    "Compute the 40th Fibonacci number with Python",
    "What did we talk about last time?",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val logs by AgentEngine.logs.collectAsState()
    val state by AgentEngine.state.collectAsState()
    val ledger by AgentEngine.ledger.collectAsState()
    val isRunning by AgentEngine.isRunning.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // The foreground service posts a progress notification; denial is non-fatal.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IAT · M-EAE") },
                actions = {
                    IconButton(onClick = { AgentEngine.clearLogs() }, enabled = logs.isNotEmpty()) {
                        Icon(Icons.Filled.ClearAll, contentDescription = "Clear transcript")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            InputBar(
                value = input,
                onValueChange = { input = it },
                isRunning = isRunning,
                onSend = {
                    val query = input.trim()
                    if (query.isNotEmpty()) {
                        AgentEngine.submit(context, query)
                        input = ""
                    }
                },
                onStop = { AgentEngine.cancel() },
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StatusHeader(state = state, ledger = ledger, isRunning = isRunning)
            if (logs.isEmpty()) {
                EmptyState(
                    onPick = { input = it },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(items = logs) { line ->
                        LogRow(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(state: RunState, ledger: TokenLedger, isRunning: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RunStateChip(state)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "sent ${compact(ledger.promptCharsSent)} chars",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "pruned ${compact(ledger.charsPruned)} chars · " +
                "${compact(ledger.charsOffloadedToFiles)} chars to files · " +
                "${ledger.schemasWithheld} schemas withheld",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "~${compact(ledger.estTokensSaved())} tokens saved (est.)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        if (isRunning) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RunStateChip(state: RunState) {
    val container = when (state) {
        RunState.IDLE -> MaterialTheme.colorScheme.surfaceVariant
        RunState.DONE -> MaterialTheme.colorScheme.primaryContainer
        RunState.ERROR -> MaterialTheme.colorScheme.errorContainer
        RunState.PAUSED_RESOURCE -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when (state) {
        RunState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        RunState.DONE -> MaterialTheme.colorScheme.onPrimaryContainer
        RunState.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        RunState.PAUSED_RESOURCE -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            text = state.name.lowercase(Locale.US).replace('_', ' '),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val accent = accentFor(line.kind)
    val mono = line.kind == LogKind.TOOL || line.kind == LogKind.ERROR
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = labelFor(line.kind),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = clockOf(line.at),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        if (mono) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = accent.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(accent)
                    )
                    Text(
                        text = line.text,
                        style = LogMono,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        } else {
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (line.kind == LogKind.FINAL) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (line.kind == LogKind.FINAL) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isRunning: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = !isRunning,
                placeholder = { Text(if (isRunning) "Working…" else "Give the agent a task") },
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(8.dp))
            if (isRunning) {
                FilledIconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop run")
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank(),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send task")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Nothing has run yet",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Every thought, tool call and byte of sandbox output shows up here — " +
                "including the context the platform kept out of the model.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Try one of these",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        ExampleTasks.forEach { task ->
            SuggestionChip(
                onClick = { onPick(task) },
                label = { Text(task, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun accentFor(kind: LogKind): Color = when (kind) {
    LogKind.USER -> MaterialTheme.colorScheme.primary
    LogKind.THOUGHT -> MaterialTheme.colorScheme.tertiary
    LogKind.ACTION -> MaterialTheme.colorScheme.secondary
    LogKind.TOOL -> MaterialTheme.colorScheme.onSurfaceVariant
    LogKind.FINAL -> MaterialTheme.colorScheme.primary
    LogKind.SYSTEM -> MaterialTheme.colorScheme.outline
    LogKind.ERROR -> MaterialTheme.colorScheme.error
    LogKind.STATE -> MaterialTheme.colorScheme.outline
}

private fun labelFor(kind: LogKind): String = when (kind) {
    LogKind.USER -> "YOU"
    LogKind.THOUGHT -> "THINK"
    LogKind.ACTION -> "ACT"
    LogKind.TOOL -> "TOOL"
    LogKind.FINAL -> "ANSWER"
    LogKind.SYSTEM -> "SYS"
    LogKind.ERROR -> "ERROR"
    LogKind.STATE -> "STATE"
}

private fun clockOf(at: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

private fun compact(n: Int): String = when {
    n < 1000 -> n.toString()
    n < 100_000 -> String.format(Locale.US, "%.1fk", n / 1000f)
    else -> "${n / 1000}k"
}
