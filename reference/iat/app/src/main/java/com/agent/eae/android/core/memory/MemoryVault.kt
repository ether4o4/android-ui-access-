package com.agent.eae.android.core.memory

import com.agent.eae.android.data.EaeDatabase
import com.agent.eae.android.data.KvEntity
import com.agent.eae.android.data.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Layer 4. Brute-force cosine over Room instead of ObjectBox/HNSW: at the few thousand rows a
 * phone accumulates, a linear scan is faster than maintaining an index and costs no native deps.
 */
class MemoryVault(private val db: EaeDatabase, private var embedder: Embedder) {

    suspend fun remember(kind: String, text: String, source: String? = null): Long =
        withContext(Dispatchers.IO) {
            val vector = runCatching { embedder.embed(text) }.getOrNull()
            db.memoryDao().insert(
                MemoryEntity(
                    kind = kind,
                    text = text,
                    source = source,
                    createdAt = System.currentTimeMillis(),
                    embedding = vector?.let { encode(it) },
                ),
            )
        }

    suspend fun ingestDocument(
        text: String,
        source: String,
        chunkChars: Int = 900,
        overlapChars: Int = 150,
    ): Int {
        val chunks = chunk(text, chunkChars, overlapChars)
        for (piece in chunks) {
            remember("doc", piece, source)
        }
        return chunks.size
    }

    suspend fun recall(query: String, topK: Int = 3): List<Pair<MemoryEntity, Float>> =
        withContext(Dispatchers.IO) {
            val queryVec = runCatching { embedder.embed(query) }.getOrNull()
                ?: return@withContext emptyList()
            db.memoryDao().allEmbedded()
                .mapNotNull { row ->
                    val stored = row.embedding?.let { decode(it) } ?: return@mapNotNull null
                    // A model swap changes dim; stale vectors are skipped, not fatal.
                    if (stored.size != embedder.dim) return@mapNotNull null
                    row to cosine(queryVec, stored)
                }
                .sortedByDescending { it.second }
                .take(topK)
        }

    suspend fun recallAsContext(query: String, topK: Int = 3, maxChars: Int = 1200): String {
        val hits = recall(query, topK)
        if (hits.isEmpty()) return ""
        val block = buildString {
            appendLine("Relevant stored context:")
            for ((row, score) in hits) {
                val line = "- (%.2f) %s".format(score, row.text.replace('\n', ' '))
                appendLine(line)
            }
        }.trim()
        return if (block.length > maxChars) block.take(maxChars) + "…" else block
    }

    fun swapEmbedder(newEmbedder: Embedder) {
        val old = embedder
        embedder = newEmbedder
        if (old !== newEmbedder) old.close()
    }

    suspend fun kvPut(key: String, value: String) = withContext(Dispatchers.IO) {
        db.kvDao().put(KvEntity(key, value, System.currentTimeMillis()))
    }

    suspend fun kvGet(key: String): String? = withContext(Dispatchers.IO) {
        db.kvDao().get(key)?.value
    }

    private fun chunk(text: String, chunkChars: Int, overlapChars: Int): List<String> {
        val clean = text.trim()
        if (clean.length <= chunkChars) return if (clean.isEmpty()) emptyList() else listOf(clean)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            val hardEnd = (start + chunkChars).coerceAtMost(clean.length)
            var end = hardEnd
            if (hardEnd < clean.length) {
                val window = clean.substring(start, hardEnd)
                val breakAt = maxOf(
                    window.lastIndexOf("\n\n"),
                    window.lastIndexOf(". "),
                    window.lastIndexOf("\n"),
                )
                // Only honour a boundary in the back half; a break too early makes degenerate chunks.
                if (breakAt > chunkChars / 2) end = start + breakAt + 1
            }
            chunks += clean.substring(start, end).trim()
            if (end >= clean.length) break
            start = (end - overlapChars).coerceAtLeast(start + 1)
        }
        return chunks.filter { it.isNotBlank() }
    }

    private fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    private fun decode(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat(it * 4) }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
