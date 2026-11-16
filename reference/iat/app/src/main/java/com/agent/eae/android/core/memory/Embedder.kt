package com.agent.eae.android.core.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.agent.eae.android.core.AgentException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.sqrt

/** Turns text into a fixed-width unit vector. Implementations are not required to be thread-safe. */
interface Embedder {
    val label: String

    val dim: Int

    suspend fun embed(text: String): FloatArray

    fun close()
}

/**
 * Dependency-free LEXICAL fallback: bag of unigrams plus character 3-grams hashed into [dim]
 * buckets. It matches on shared spelling, NOT on meaning — "car" and "automobile" score ~0.
 * It exists so the memory layer works on first launch before any model is downloaded.
 */
class HashingEmbedder(override val dim: Int = 256) : Embedder {
    init {
        require(dim > 0) { "dim must be positive" }
    }

    override val label: String = "hashing-lexical-fallback-v1/$dim"

    override suspend fun embed(text: String): FloatArray {
        val counts = HashMap<Int, Int>()
        for (token in SPLIT.split(text.lowercase())) {
            if (token.isEmpty()) continue
            bump(counts, fnv1a("1:$token"))
            val padded = "#$token#"
            if (padded.length >= 3) {
                for (i in 0..padded.length - 3) {
                    bump(counts, fnv1a("3:" + padded.substring(i, i + 3)))
                }
            }
        }
        val v = FloatArray(dim)
        // Sorted iteration: HashMap order varies between processes and float += is not associative,
        // so unsorted accumulation would make vectors differ in the last bits run to run.
        for (key in counts.keys.sorted()) {
            val count = counts.getValue(key)
            val bucket = (key ushr 8) % dim
            val sign = if ((key and 1) == 0) 1f else -1f
            v[bucket] += sign * (1f + ln(count.toDouble()).toFloat())
        }
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x.toDouble()
        if (norm > 0.0) {
            val inv = (1.0 / sqrt(norm)).toFloat()
            for (i in v.indices) v[i] *= inv
        }
        return v
    }

    override fun close() = Unit

    private fun bump(counts: HashMap<Int, Int>, key: Int) {
        counts[key] = (counts[key] ?: 0) + 1
    }

    private companion object {
        val SPLIT = Regex("[^\\p{L}\\p{N}]+")
        const val FNV_PRIME: Int = 0x01000193

        /** FNV-1a over UTF-16 code units; spreads better across buckets than String.hashCode. */
        fun fnv1a(s: String): Int {
            var h = 0x811C9DC5L.toInt()
            for (ch in s) {
                val c = ch.code
                h = (h xor (c and 0xFF)) * FNV_PRIME
                h = (h xor ((c ushr 8) and 0xFF)) * FNV_PRIME
            }
            return h
        }
    }
}

/**
 * Real semantic embeddings from all-MiniLM-L6-v2 via ONNX Runtime, with a WordPiece tokenizer
 * matching BERT's uncased preprocessing.
 */
class OnnxMiniLmEmbedder(
    private val modelFile: File,
    private val vocabFile: File,
) : Embedder {
    override val label: String = "all-MiniLM-L6-v2/onnx"

    override val dim: Int = 384

    private class Ready(
        val env: OrtEnvironment,
        val session: OrtSession,
        val vocab: Map<String, Int>,
        val unkId: Int,
        val clsId: Long,
        val sepId: Long,
    )

    private val initLock = Mutex()

    @Volatile
    private var ready: Ready? = null

    @Volatile
    private var closed: Boolean = false

    override suspend fun embed(text: String): FloatArray =
        withContext(Dispatchers.IO) {
            val r = ensureReady()
            val ids = tokenize(text, r)
            val seqLen = ids.size
            val mask = LongArray(seqLen) { 1L }
            val types = LongArray(seqLen)
            val shape = longArrayOf(1L, seqLen.toLong())
            val open = ArrayList<OnnxTensor>(3)
            try {
                val inputs = LinkedHashMap<String, OnnxTensor>(4)
                val idsTensor = OnnxTensor.createTensor(r.env, LongBuffer.wrap(ids), shape)
                open += idsTensor
                inputs["input_ids"] = idsTensor
                val maskTensor = OnnxTensor.createTensor(r.env, LongBuffer.wrap(mask), shape)
                open += maskTensor
                inputs["attention_mask"] = maskTensor
                val typeTensor = OnnxTensor.createTensor(r.env, LongBuffer.wrap(types), shape)
                open += typeTensor
                // Some exports drop token_type_ids; passing an input the graph does not declare fails the run.
                if (r.session.inputNames.contains(TOKEN_TYPE_IDS)) {
                    inputs[TOKEN_TYPE_IDS] = typeTensor
                }
                r.session.run(inputs).use { results ->
                    val out = results[0] as? OnnxTensor
                        ?: throw AgentException("Embedding model returned a non-tensor output; expected last_hidden_state.")
                    meanPool(out, mask, seqLen)
                }
            } catch (e: AgentException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                throw AgentException("Embedding inference failed (${modelFile.name}): ${t.message ?: t.javaClass.simpleName}")
            } finally {
                for (t in open) runCatching { t.close() }
            }
        }

    /**
     * Releases the session. The process-wide [OrtEnvironment] singleton is deliberately NOT closed,
     * since other sessions may share it. Callers must not race close() against an in-flight embed().
     */
    override fun close() {
        closed = true
        val r = ready
        ready = null
        if (r != null) runCatching { r.session.close() }
    }

    private suspend fun ensureReady(): Ready =
        initLock.withLock {
            if (closed) throw AgentException("This embedder was closed; create a new one before embedding.")
            ready?.let { return@withLock it }
            if (!modelFile.isFile) {
                throw AgentException(
                    "Embedding model not found at ${modelFile.absolutePath}. " +
                        "Download it from Settings, or copy all-MiniLM-L6-v2 model.onnx to that path.",
                )
            }
            if (!vocabFile.isFile) {
                throw AgentException(
                    "Tokenizer vocab not found at ${vocabFile.absolutePath}. " +
                        "Download it from Settings, or copy all-MiniLM-L6-v2 vocab.txt to that path.",
                )
            }
            val vocab = loadVocab(vocabFile)
            val built =
                try {
                    val env = OrtEnvironment.getEnvironment()
                    val session =
                        OrtSession.SessionOptions().use { opts ->
                            opts.setIntraOpNumThreads(
                                Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                            )
                            env.createSession(modelFile.absolutePath, opts)
                        }
                    Ready(
                        env = env,
                        session = session,
                        vocab = vocab,
                        unkId = vocab.getValue(UNK),
                        clsId = vocab.getValue(CLS).toLong(),
                        sepId = vocab.getValue(SEP).toLong(),
                    )
                } catch (t: Throwable) {
                    throw AgentException(
                        "Could not open the ONNX embedding session for ${modelFile.absolutePath}: " +
                            "${t.message ?: t.javaClass.simpleName}. The file may be truncated — re-download it.",
                    )
                }
            ready = built
            built
        }

    private fun meanPool(out: OnnxTensor, mask: LongArray, seqLen: Int): FloatArray {
        val shape = out.info.shape
        if (shape.isNotEmpty() && shape[shape.size - 1].toInt() != dim) {
            throw AgentException("Embedding model output width is ${shape[shape.size - 1]}, expected $dim; wrong model file.")
        }
        val flat = flatten(out, seqLen)
        val sums = FloatArray(dim)
        var kept = 0f
        for (t in 0 until seqLen) {
            if (mask[t] == 0L) continue
            kept += 1f
            val base = t * dim
            for (d in 0 until dim) sums[d] += flat[base + d]
        }
        if (kept == 0f) return sums
        var norm = 0.0
        for (d in 0 until dim) {
            sums[d] /= kept
            norm += sums[d].toDouble() * sums[d].toDouble()
        }
        if (norm > 0.0) {
            val inv = (1.0 / sqrt(norm)).toFloat()
            for (d in sums.indices) sums[d] *= inv
        }
        return sums
    }

    /** last_hidden_state comes back either as a float buffer or as a nested Array<Array<FloatArray>>. */
    private fun flatten(out: OnnxTensor, seqLen: Int): FloatArray {
        val expected = seqLen * dim
        // getFloatBuffer() may return null or throw for non-float tensors depending on runtime version.
        val buf = runCatching { out.floatBuffer }.getOrNull()?.duplicate()
        if (buf != null && buf.remaining() >= expected) {
            val flat = FloatArray(expected)
            buf.get(flat)
            return flat
        }
        val batch = out.value as? Array<*>
            ?: throw AgentException("Unexpected embedding output layout (${out.info}); expected float [1, seq, $dim].")
        val rows = batch.firstOrNull() as? Array<*>
            ?: throw AgentException("Unexpected embedding output shape ${batch.size}; expected [1, seq, $dim].")
        if (rows.size < seqLen) {
            throw AgentException("Embedding model returned ${rows.size} token rows for $seqLen tokens.")
        }
        val flat = FloatArray(expected)
        for (t in 0 until seqLen) {
            val row = rows[t] as? FloatArray
                ?: throw AgentException("Unexpected embedding row type at token $t; expected FloatArray.")
            if (row.size < dim) {
                throw AgentException("Embedding row width is ${row.size}, expected $dim; wrong model file.")
            }
            System.arraycopy(row, 0, flat, t * dim, dim)
        }
        return flat
    }

    private fun loadVocab(file: File): Map<String, Int> {
        val map = HashMap<String, Int>(1 shl 15)
        try {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                // The id of a token is its line number, so blank lines still consume an index.
                lines.forEachIndexed { index, raw ->
                    val token = if (raw.endsWith("\r")) raw.dropLast(1) else raw
                    if (token.isNotEmpty()) map[token] = index
                }
            }
        } catch (t: Throwable) {
            throw AgentException("Could not read ${file.absolutePath}: ${t.message ?: t.javaClass.simpleName}")
        }
        for (special in listOf(CLS, SEP, UNK)) {
            if (!map.containsKey(special)) {
                throw AgentException("${file.absolutePath} is missing the required $special entry; re-download vocab.txt.")
            }
        }
        return map
    }

    private fun tokenize(text: String, r: Ready): LongArray {
        val budget = MAX_TOKENS - 2
        val pieces = ArrayList<Int>(minOf(budget, 64))
        val word = ArrayList<Int>(8)
        outer@ for (basic in basicTokens(text)) {
            word.clear()
            wordPiece(basic, r, word)
            for (id in word) {
                if (pieces.size >= budget) break@outer
                pieces += id
            }
        }
        val ids = LongArray(pieces.size + 2)
        ids[0] = r.clsId
        for (i in pieces.indices) ids[i + 1] = pieces[i].toLong()
        ids[ids.size - 1] = r.sepId
        return ids
    }

    /** Greedy longest-match-first subwording; a word with any unmatchable piece becomes a single [UNK]. */
    private fun wordPiece(word: String, r: Ready, out: MutableList<Int>) {
        if (word.isEmpty()) return
        if (word.length > MAX_CHARS_PER_WORD) {
            out += r.unkId
            return
        }
        val staging = ArrayList<Int>(4)
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matched = -1
            while (start < end) {
                val piece = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                val id = r.vocab[piece]
                if (id != null) {
                    matched = id
                    break
                }
                end--
            }
            if (matched < 0) {
                out += r.unkId
                return
            }
            staging += matched
            start = end
        }
        out += staging
    }

    private fun basicTokens(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (ch in stripAccents(text.lowercase())) {
            when {
                ch.isWhitespace() || isControl(ch) -> {
                    if (current.isNotEmpty()) {
                        out += current.toString()
                        current.setLength(0)
                    }
                }
                isPunctuation(ch) || isCjk(ch) -> {
                    if (current.isNotEmpty()) {
                        out += current.toString()
                        current.setLength(0)
                    }
                    out += ch.toString()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun stripAccents(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
        }
        return sb.toString()
    }

    private fun isControl(ch: Char): Boolean =
        when (Character.getType(ch)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.SURROGATE.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.UNASSIGNED.toInt(),
            -> true
            else -> false
        }

    private fun isPunctuation(ch: Char): Boolean {
        val c = ch.code
        // BERT treats every non-alphanumeric ASCII char as punctuation, not just Unicode category P.
        if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            -> true
            else -> false
        }
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF
    }

    private companion object {
        const val MAX_TOKENS = 256
        const val MAX_CHARS_PER_WORD = 100
        const val TOKEN_TYPE_IDS = "token_type_ids"
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"
    }
}

/**
 * Locates and fetches the embedding model. The ~90 MB model is downloaded on demand rather than
 * bundled in the APK, so the shipped binary stays small and users who never use RAG never pay for it.
 */
object EmbedderFiles {
    private const val MODEL_URL =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx"
    private const val VOCAB_URL =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt"
    private const val MODEL_MIN_BYTES = 1_000_000L
    private const val VOCAB_MIN_BYTES = 10_000L
    private const val MAX_REDIRECTS = 5
    private const val PART_SUFFIX = ".part"

    fun modelFile(context: Context): File = File(modelsDir(context), "minilm.onnx")

    fun vocabFile(context: Context): File = File(modelsDir(context), "vocab.txt")

    fun available(context: Context): Boolean = modelFile(context).isFile && vocabFile(context).isFile

    /** [onProgress] receives 0..100 and is invoked on a background thread. */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val dir = modelsDir(context)
            dir.mkdirs()
            try {
                onProgress(0)
                // Vocab first: it is tiny, so failing fast here costs no bandwidth.
                fetch(VOCAB_URL, vocabFile(context), VOCAB_MIN_BYTES) { pct -> onProgress(pct * 2 / 100) }
                fetch(MODEL_URL, modelFile(context), MODEL_MIN_BYTES) { pct -> onProgress(2 + pct * 98 / 100) }
                onProgress(100)
                true
            } catch (e: CancellationException) {
                cleanupParts(dir)
                throw e
            } catch (t: Throwable) {
                cleanupParts(dir)
                false
            }
        }

    private fun modelsDir(context: Context): File = File(context.filesDir, "models")

    private fun cleanupParts(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(PART_SUFFIX)) runCatching { f.delete() }
        }
    }

    private suspend fun fetch(url: String, dest: File, minBytes: Long, onProgress: (Int) -> Unit) {
        val part = File(dest.path + PART_SUFFIX)
        if (part.exists() && !part.delete()) throw IOException("Could not clear ${part.name}")
        val conn = openFollowingRedirects(url)
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var seen = 0L
                    var lastPct = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        seen += n
                        if (total > 0L) {
                            val pct = ((seen * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        } finally {
            conn.disconnect()
        }
        if (part.length() < minBytes) {
            part.delete()
            throw IOException("${dest.name} download was only ${part.length()} bytes; the server likely returned an error page")
        }
        if (dest.exists() && !dest.delete()) throw IOException("Could not replace ${dest.name}")
        if (!part.renameTo(dest)) {
            part.delete()
            throw IOException("Could not move ${part.name} into place")
        }
    }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        var hops = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                // Followed by hand: the CDN redirect crosses hosts, which the built-in follower may drop.
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                // Identity encoding keeps Content-Length equal to the bytes we write, for real progress.
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "eae-android")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("HTTP $code with no Location header for $current")
                if (++hops > MAX_REDIRECTS) throw IOException("Too many redirects for $url")
                current = URL(URL(current), location).toString()
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IOException("HTTP $code for $current")
            }
            return conn
        }
    }
}

object EmbedderFactory {
    fun create(context: Context): Embedder =
        if (EmbedderFiles.available(context)) {
            OnnxMiniLmEmbedder(EmbedderFiles.modelFile(context), EmbedderFiles.vocabFile(context))
        } else {
            HashingEmbedder()
        }
}
