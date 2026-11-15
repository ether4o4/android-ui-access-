package com.agent.eae.android.core.tools

import com.agent.eae.android.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * Outbound-only HTTP surface for webhooks and third-party integrations. Secrets are substituted
 * into the request after the model has produced the call, and the full response is offloaded to a
 * log file so only a short head ever enters model context. Redirects follow HttpURLConnection's
 * default behaviour, which never crosses protocols (http <-> https).
 */
class HttpTool(private val secrets: SecretStore, private val logDir: File) {

    suspend fun request(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val rawUrl = args.optString("url").trim()
        if (rawUrl.isBlank()) {
            return@withContext refuse("Missing required arg: url")
        }
        val method = args.optString("method", "GET").trim().uppercase().ifBlank { "GET" }
        if (method !in METHODS) {
            return@withContext refuse("Unsupported method: $method. Use one of ${METHODS.joinToString(", ")}.")
        }
        val timeoutMs = args.optLong("timeout_ms", 20_000L).coerceIn(2_000L, 60_000L).toInt()

        val usedNames = LinkedHashSet<String>()
        val (url, urlUsed) = secrets.substitute(rawUrl)
        usedNames += urlUsed

        val parsed = try {
            URL(url)
        } catch (e: MalformedURLException) {
            return@withContext refuse("Malformed URL: $rawUrl")
        }
        val protocol = parsed.protocol.lowercase()
        if (protocol != "http" && protocol != "https") {
            return@withContext refuse("Only http and https URLs are allowed, got scheme: $protocol")
        }

        val requestHeaders = LinkedHashMap<String, String>()
        args.optJSONObject("headers")?.let { json ->
            for (key in json.keys()) {
                val (value, headerUsed) = secrets.substitute(json.optString(key))
                requestHeaders[key] = value
                usedNames += headerUsed
            }
        }
        val (body, bodyUsed) = secrets.substitute(args.optString("body"))
        usedNames += bodyUsed

        val startedAt = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            connection = parsed.openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            for ((key, value) in requestHeaders) {
                connection.setRequestProperty(key, value)
            }
            if (method in BODY_METHODS && body.isNotBlank()) {
                if (requestHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    connection.setRequestProperty("Content-Type", "application/json")
                }
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val reason = connection.responseMessage ?: ""
            val elapsed = System.currentTimeMillis() - startedAt
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val (responseBody, capped) = readCapped(stream)

            val full = buildString {
                appendLine("$method $rawUrl")
                if (requestHeaders.isNotEmpty()) {
                    appendLine("request headers: ${requestHeaders.keys.joinToString(", ")}")
                }
                appendLine("HTTP $status $reason (${elapsed}ms)")
                connection.headerFields.forEach { (key, values) ->
                    if (key != null) appendLine("$key: ${values.joinToString(", ")}")
                }
                appendLine()
                append(responseBody)
                if (capped) append("\n[body truncated at $MAX_BODY_BYTES bytes]")
            }
            val logFile = writeLog(full)

            val summary = buildString {
                append("[$status $reason ${elapsed}ms]")
                val head = responseBody.take(SUMMARY_CHARS)
                if (head.isNotBlank()) {
                    append('\n')
                    append(head)
                }
                if (responseBody.length > SUMMARY_CHARS || capped) {
                    append("\n…[truncated, full response in ${logFile?.name ?: "log"}]")
                }
                if (usedNames.isNotEmpty()) {
                    append("\n(secrets used: ${usedNames.joinToString(", ")})")
                }
            }
            ToolResult(
                summary = summary,
                full = full,
                ok = status in 200..299,
                logPath = logFile?.absolutePath,
            )
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startedAt
            val message = redact(e.message ?: e.javaClass.simpleName, usedNames)
            ToolResult(
                summary = "[error ${elapsed}ms] $message",
                full = "$method $rawUrl\n${redact(e.stackTraceToString(), usedNames)}",
                ok = false,
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun readCapped(stream: InputStream?): Pair<String, Boolean> {
        if (stream == null) return "" to false
        return stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8_192)
            var truncated = false
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                val room = MAX_BODY_BYTES - buffer.size()
                if (read >= room) {
                    buffer.write(chunk, 0, room)
                    truncated = read > room || input.read() != -1
                    break
                }
                buffer.write(chunk, 0, read)
            }
            buffer.toString("UTF-8") to truncated
        }
    }

    private suspend fun redact(text: String, used: Collection<String>): String {
        var out = text
        for (name in used) {
            val value = secrets.get(name) ?: continue
            if (value.isNotEmpty()) out = out.replace(value, "{{secret:$name}}")
        }
        return out
    }

    private fun writeLog(text: String): File? =
        runCatching {
            File(logDir, "http-${System.currentTimeMillis()}.log").apply { writeText(text) }
        }.getOrNull()

    private fun refuse(reason: String): ToolResult = ToolResult(summary = reason, full = reason, ok = false)

    private companion object {
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val MAX_BODY_BYTES = 256 * 1024
        const val SUMMARY_CHARS = 400
    }
}
