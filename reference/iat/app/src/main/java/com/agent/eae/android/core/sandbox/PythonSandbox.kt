package com.agent.eae.android.core.sandbox

import android.content.Context
import com.agent.eae.android.core.ToolResult
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Layer 2. Runs agent-written Python inside the app's private storage through Chaquopy.
 *
 * Two independent deadlines apply: Python's own trace-based watchdog, and an outer coroutine
 * timeout here. The outer one cannot kill a stuck interpreter thread, so a run that blows through
 * both leaves the sandbox marked busy rather than corrupting a later run.
 */
class PythonSandbox(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Mutex()

    val workspace: File = File(appContext.filesDir, "workspace").apply { mkdirs() }
    private val logDir: File = File(appContext.filesDir, "logs").apply { mkdirs() }

    private fun module(): PyObject {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(appContext))
        }
        return Python.getInstance().getModule("eae_sandbox")
    }

    suspend fun execute(code: String, timeoutMs: Long): ToolResult {
        if (!lock.tryLock()) {
            return ToolResult(
                summary = "Sandbox is busy with an earlier script that has not returned. Try again shortly.",
                full = "",
                ok = false,
            )
        }
        try {
            val raw = withTimeoutOrNull(timeoutMs + GRACE_MS) {
                withContext(Dispatchers.IO) {
                    module().callAttr("run", code, workspace.absolutePath, timeoutMs).toString()
                }
            } ?: return ToolResult(
                summary = "Script did not return within ${timeoutMs}ms and was abandoned. " +
                    "Split the work into smaller steps.",
                full = "",
                ok = false,
            )
            return summarise(raw, code)
        } catch (e: Exception) {
            return ToolResult(
                summary = "Sandbox failed to start: ${e.message ?: e.javaClass.simpleName}",
                full = e.stackTraceToString(),
                ok = false,
            )
        } finally {
            lock.unlock()
        }
    }

    private fun summarise(raw: String, code: String): ToolResult {
        val json = JSONObject(raw)
        val ok = json.optBoolean("ok", false)
        val stdout = json.optString("stdout", "")
        val stderr = json.optString("stderr", "")
        val elapsed = json.optInt("elapsed_ms", -1)

        val full = buildString {
            appendLine("--- script ---")
            appendLine(code)
            appendLine("--- stdout ---")
            appendLine(stdout)
            if (stderr.isNotBlank()) {
                appendLine("--- stderr ---")
                appendLine(stderr)
            }
        }

        val logFile = writeLog(full)
        // Layer 5 depends on this: only the head of the output may reach the prompt.
        val head = if (ok) stdout.ifBlank { "(no stdout)" } else stderr.ifBlank { "(no error text)" }
        val trimmed = head.trim().let {
            if (it.length > SUMMARY_CHARS) {
                it.take(SUMMARY_CHARS) + "\n…[truncated, full output in ${logFile?.name ?: "log"}]"
            } else {
                it
            }
        }
        val status = if (ok) "ok" else "error"
        return ToolResult(
            summary = "[$status ${elapsed}ms] $trimmed",
            full = full,
            ok = ok,
            logPath = logFile?.absolutePath,
        )
    }

    private fun writeLog(text: String): File? =
        runCatching {
            File(logDir, "tool-${System.currentTimeMillis()}.log").apply { writeText(text) }
        }.getOrNull()

    suspend fun listWorkspace(): String = withContext(Dispatchers.IO) {
        runCatching { module().callAttr("listdir", workspace.absolutePath).toString() }
            .getOrElse { JSONArray().toString() }
    }

    suspend fun readWorkspaceFile(name: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        runCatching { module().callAttr("read_text", workspace.absolutePath, name, maxChars).toString() }
            .getOrElse { JSONObject().put("ok", false).put("error", it.message).toString() }
    }

    private companion object {
        const val SUMMARY_CHARS = 300
        const val GRACE_MS = 1_500L
    }
}
