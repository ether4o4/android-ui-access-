package com.ether4o4.odcm.agent

import okhttp3.Headers
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

internal class ProviderHttpException(val code: Int, val waitMs: Long, val quota: Boolean) : Exception()

internal object ProviderCooldown {
    private val until = ConcurrentHashMap<String, Long>()
    fun remaining(key: String, now: Long = System.currentTimeMillis()) = ((until[key] ?: 0) - now).coerceAtLeast(0)
    fun record(key: String, millis: Long) { until[key] = System.currentTimeMillis() + millis }
    fun parse(code: Int, headers: Headers, body: String, now: Long = System.currentTimeMillis()): ProviderHttpException {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val kind = error?.optString("code").orEmpty() + " " + error?.optString("type").orEmpty()
        val detailText = error?.optJSONArray("details")?.toString().orEmpty().lowercase()
        val message = error?.optString("message").orEmpty().lowercase()
        val quota = detailText.contains("requestsperday") || detailText.contains("tokensperday") ||
            message.contains("limit: 0") || message.contains("daily quota") || kind.contains("insufficient_quota") || kind.contains("billing_hard_limit") ||
            kind.contains("credit_balance") || kind.contains("quota_exhausted")
        val retry = headers["Retry-After"]?.let { raw -> raw.toDoubleOrNull()?.times(1000)?.toLong()
            ?: runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now }.getOrNull() }
        val details = error?.optJSONArray("details")
        val delay = details?.let { a -> (0 until a.length()).mapNotNull { n ->
            a.optJSONObject(n)?.optString("retryDelay")?.removeSuffix("s")?.toDoubleOrNull()?.times(1000)?.toLong()
        }.maxOrNull() }
        return ProviderHttpException(code, (retry ?: delay ?: if (code == 429) 30_000L else 2000L).coerceIn(1000, 86_400_000), quota)
    }
}
