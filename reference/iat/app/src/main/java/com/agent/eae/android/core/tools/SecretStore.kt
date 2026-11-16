package com.agent.eae.android.core.tools

import com.agent.eae.android.data.EaeDatabase
import com.agent.eae.android.data.KvEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device secret vault backing the `{{secret:NAME}}` placeholder scheme. The model only ever
 * sees placeholders; real values are injected into outbound requests after inference, so secrets
 * never enter model context or transcripts. Values live in the app-private Room database, which is
 * sandboxed by Android's per-app storage isolation but not additionally encrypted.
 */
class SecretStore(private val db: EaeDatabase) {

    suspend fun put(name: String, value: String) {
        withContext(Dispatchers.IO) {
            db.kvDao().put(KvEntity(PREFIX + normalise(name), value, System.currentTimeMillis()))
        }
    }

    suspend fun get(name: String): String? = withContext(Dispatchers.IO) {
        db.kvDao().get(PREFIX + normalise(name))?.value
    }

    suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        db.kvDao().all()
            .map { it.key }
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX) }
            .sorted()
    }

    suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        db.kvDao().delete(PREFIX + normalise(name)) > 0
    }

    /**
     * Replaces every `{{secret:NAME}}` placeholder in [text] with its stored value and returns the
     * substituted text plus the deduped list of secret names used. Unknown names are left as-is
     * and are not reported as used.
     */
    suspend fun substitute(text: String): Pair<String, List<String>> {
        if (!text.contains("{{")) return text to emptyList()
        val stored: Map<String, String> = withContext(Dispatchers.IO) {
            db.kvDao().all()
                .filter { it.key.startsWith(PREFIX) }
                .associate { it.key.removePrefix(PREFIX) to it.value }
        }
        val used = LinkedHashSet<String>()
        val replaced = PLACEHOLDER.replace(text) { match ->
            val name = normalise(match.groupValues[1])
            val value = stored[name]
            if (value != null) {
                used.add(name)
                value
            } else {
                match.value
            }
        }
        return replaced to used.toList()
    }

    private fun normalise(name: String): String = name.trim().uppercase().replace(NON_NAME, "_")

    private companion object {
        const val PREFIX = "secret_"
        val PLACEHOLDER = Regex("""\{\{\s*secret\s*:\s*([^{}]*?)\s*\}\}""", RegexOption.IGNORE_CASE)
        val NON_NAME = Regex("[^A-Z0-9_]")
    }
}
