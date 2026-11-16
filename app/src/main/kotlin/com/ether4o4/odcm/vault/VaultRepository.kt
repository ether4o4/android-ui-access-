package com.ether4o4.odcm.vault

import android.content.Context
import android.net.Uri
import com.ether4o4.odcm.data.Settings
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class VaultRepository(private val context: Context) : java.io.Closeable {
    private val tree = Settings(context).vaultUri
    private val index = VaultIndex(context)
    private val documents: VaultDocuments get() {
        require(tree.isNotBlank()) { "Choose your Obsidian vault in ODCM first." }
        require(Settings(context).vaultUri == tree) { "Vault selection changed; start again." }
        return VaultDocuments(context.contentResolver, Uri.parse(tree))
    }
    fun refresh(force: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): VaultStats {
        val docs = documents
        val files = docs.list()
        return index.refresh(tree, files, force, progress, VaultExtractor(context, docs)::extract)
    }
    fun search(query: String, limit: Int = 8): List<VaultHit> { index.requireTree(tree); return index.search(query, limit) }
    fun read(path: String, offset: Int = 0): String { index.requireTree(tree); return index.read(path, offset) }
    fun problems() = index.problems()
    fun stats() = index.stats()

    fun publishGenerated(path: String, markdown: String) = documents.createIfMissing(path, "text/markdown") {
        it.write(markdown.toByteArray(Charsets.UTF_8))
    }

    fun importExport(source: Uri, name: String, progress: (String) -> Unit): String {
        val docs = documents
        val copy = File.createTempFile("conversation-export-", ".tmp", context.cacheDir)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            progress("Saving the original export…")
            val input = context.contentResolver.openInputStream(source) ?: error("Cannot open the export")
            input.use { src -> copy.outputStream().use { dst ->
                val buf = ByteArray(65536); var total = 0L
                while (true) {
                    val n = src.read(buf); if (n < 0) break
                    total += n; require(total <= 1024L * 1024 * 1024) { "Export exceeds 1 GB; split it before importing." }
                    hash.update(buf, 0, n); dst.write(buf, 0, n)
                }
            } }
            val digest = hash.digest().joinToString("") { "%02x".format(it) }
            val isZip = copy.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
            val archive = "Archive/$digest.${if (isZip) "zip" else "json"}"
            docs.createIfMissing(archive, if (isZip) "application/zip" else "application/json") { out -> copy.inputStream().use { it.copyTo(out) } }
            var imported = 0; var duplicates = 0; var unsupported = 0
            fun convert(stream: InputStream) {
                JsonRecords.read(stream.reader(Charsets.UTF_8)) { record ->
                    val note = ConversationImport.convert(record)
                    if (note == null) unsupported++ else {
                        val body = note.markdown + "\nOriginal export: [[$archive]]\nImported filename: ${name.replace('\n', ' ')}\n"
                        if (docs.createIfMissing(note.path, "text/markdown") { it.write(body.toByteArray(Charsets.UTF_8)) }) imported++ else duplicates++
                        if ((imported + duplicates) % 20 == 0) progress("Imported $imported conversations; $duplicates already present…")
                    }
                }
            }
            if (isZip) {
                var entries = 0; var found = false; var expanded = 0L
                ZipInputStream(copy.inputStream()).use { zip ->
                    val bounded = object : FilterInputStream(zip) {
                        override fun read(): Int { val n = super.read(); if (n >= 0) count(1); return n }
                        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = `in`.read(b, off, len); if (n > 0) count(n); return n }
                        private fun count(n: Int) { expanded += n; require(expanded <= 1024L * 1024 * 1024) { "Expanded export exceeds 1 GB" } }
                        override fun close() = Unit
                    }
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++entries <= 50_000) { "Too many archive entries" }
                        val leaf = entry.name.substringAfterLast('/').lowercase()
                        if (!entry.isDirectory && leaf.matches(Regex("conversations?(?:[-_][0-9]+)?\\.json"))) {
                            found = true; convert(bounded)
                        }
                        val buffer = ByteArray(65536)
                        while (bounded.read(buffer) >= 0) { /* Drain with the same expansion bound. */ }
                    }
                }
                require(found) { "No conversations.json found. Original saved in $archive; select the conversation JSON from this export." }
            } else copy.inputStream().use(::convert)
            require(imported + duplicates > 0) { "No supported conversation records found. Original preserved in $archive." }
            progress("Updating vault search…")
            refresh(progress = { done, total -> progress("Indexing $done / $total files…") })
            return "$imported conversations imported; $duplicates already present; $unsupported unsupported records. Original preserved in $archive. No model calls or import fees."
        } finally { copy.delete() }
    }
    override fun close() = index.close()
}
