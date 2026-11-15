package com.agent.eae.android.core.tools

import com.agent.eae.android.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Base64

/** GitHub REST helper backed by the optional {{secret:GITHUB_TOKEN}} setting. */
class GitHubTool(private val secrets: SecretStore) {

    suspend fun run(name: String, args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val req = when (name) {
            "github_get_repo" -> request(
                method = "GET",
                path = "/repos/${repo(args)}",
            )

            "github_list_contents" -> request(
                method = "GET",
                path = "/repos/${repo(args)}/contents/${args.optString("path").trim().trimStart('/')}",
                query = refQuery(args),
            )

            "github_get_file" -> request(
                method = "GET",
                path = "/repos/${repo(args)}/contents/${required(args, "path")}",
                query = refQuery(args),
                decodeContentFile = true,
            )

            "github_search_repos" -> request(
                method = "GET",
                path = "/search/repositories",
                query = mapOf(
                    "q" to required(args, "query"),
                    "per_page" to args.optInt("limit", 10).coerceIn(1, 25).toString(),
                ),
            )

            "github_list_issues" -> request(
                method = "GET",
                path = "/repos/${repo(args)}/issues",
                query = mapOf(
                    "state" to args.optString("state", "open").ifBlank { "open" },
                    "per_page" to args.optInt("limit", 10).coerceIn(1, 30).toString(),
                ),
            )

            "github_create_issue" -> request(
                method = "POST",
                path = "/repos/${repo(args)}/issues",
                body = JSONObject()
                    .put("title", required(args, "title"))
                    .put("body", args.optString("body"))
                    .toString(),
            )

            "github_api" -> request(
                method = args.optString("method", "GET").uppercase().ifBlank { "GET" },
                path = required(args, "path"),
                body = args.optString("body"),
            )

            else -> return@withContext ToolResult("Unknown GitHub tool: $name", "", false)
        }
        req
    }

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String = "",
        decodeContentFile: Boolean = false,
    ): ToolResult {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val qs = query
            .filterValues { it.isNotBlank() }
            .entries
            .joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val url = URL("https://api.github.com$cleanPath" + if (qs.isBlank()) "" else "?$qs")
        val token = secrets.get("GITHUB_TOKEN").orEmpty()
        val conn = url.openConnection() as HttpURLConnection
        val started = System.currentTimeMillis()
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "IAT-M-EAE")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (method in BODY_METHODS && body.isNotBlank()) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val reason = conn.responseMessage ?: ""
            val response = readCapped(if (status in 200..399) conn.inputStream else conn.errorStream)
            val elapsed = System.currentTimeMillis() - started
            val processed = if (decodeContentFile && status in 200..299) decodeFileResponse(response) else response
            val summary = summarize(cleanPath, status, reason, elapsed, processed, token.isNotBlank())
            ToolResult(summary = summary, full = processed, ok = status in 200..299)
        } catch (e: Exception) {
            ToolResult("GitHub request failed: ${e.message ?: e.javaClass.simpleName}", e.stackTraceToString(), false)
        } finally {
            conn.disconnect()
        }
    }

    private fun summarize(path: String, status: Int, reason: String, elapsed: Long, body: String, authed: Boolean): String {
        val head = when {
            body.trim().startsWith("[") -> summarizeArray(body)
            body.trim().startsWith("{") -> summarizeObject(body)
            else -> body.take(SUMMARY_CHARS)
        }
        return buildString {
            append("GitHub $status $reason (${elapsed}ms) $path")
            if (authed) append("\n(auth: GITHUB_TOKEN)")
            if (head.isNotBlank()) append("\n").append(head.take(SUMMARY_CHARS))
            if (body.length > SUMMARY_CHARS) append("\n...[truncated]")
        }
    }

    private fun summarizeArray(body: String): String = runCatching {
        val array = JSONArray(body)
        if (array.length() == 0) return "[]"
        (0 until array.length().coerceAtMost(8)).joinToString("\n") { i ->
            val obj = array.optJSONObject(i)
            if (obj != null) {
                val name = obj.optString("name").ifBlank { obj.optString("full_name") }.ifBlank { obj.optString("title") }
                val type = obj.optString("type").ifBlank { obj.optString("state") }
                listOf(name, type).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { obj.toString().take(160) }
            } else {
                array.optString(i).take(160)
            }
        }
    }.getOrDefault(body.take(SUMMARY_CHARS))

    private fun summarizeObject(body: String): String = runCatching {
        val obj = JSONObject(body)
        if (obj.has("content") && obj.optString("encoding") == "base64") {
            return@runCatching decodeFileResponse(body).take(SUMMARY_CHARS)
        }
        val keys = listOf("full_name", "name", "description", "default_branch", "html_url", "message", "title", "state")
        keys.mapNotNull { key -> obj.optString(key).takeIf { it.isNotBlank() }?.let { "$key: $it" } }
            .joinToString("\n")
            .ifBlank { obj.toString().take(SUMMARY_CHARS) }
    }.getOrDefault(body.take(SUMMARY_CHARS))

    private fun decodeFileResponse(body: String): String = runCatching {
        val obj = JSONObject(body)
        val encoded = obj.optString("content").replace("\n", "")
        if (encoded.isBlank()) return@runCatching body
        val text = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        "${obj.optString("path")} (${obj.optString("sha").take(12)})\n\n$text"
    }.getOrDefault(body)

    private fun readCapped(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8_192)
            while (buffer.size() < MAX_BODY_BYTES) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read.coerceAtMost(MAX_BODY_BYTES - buffer.size()))
            }
            buffer.toString("UTF-8")
        }
    }

    private fun repo(args: JSONObject): String = required(args, "repo").trim().trim('/')

    private fun refQuery(args: JSONObject): Map<String, String> =
        args.optString("ref").takeIf { it.isNotBlank() }?.let { mapOf("ref" to it) } ?: emptyMap()

    private fun required(args: JSONObject, name: String): String {
        val value = args.optString(name).trim()
        if (value.isBlank()) throw IllegalArgumentException("Missing required arg: $name")
        return value
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        const val MAX_BODY_BYTES = 256 * 1024
        const val SUMMARY_CHARS = 1200
    }
}
