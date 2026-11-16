package com.ether4o4.odcm.vault

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.InputStream

data class VaultFile(val path: String, val uri: Uri, val mime: String, val modified: Long, val size: Long)

/** Access is restricted by Android to the directory explicitly selected by the user. */
class VaultDocuments(private val resolver: ContentResolver, val tree: Uri) {
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val writeDirectories = HashMap<String, MutableMap<String, VaultFile>>()
    private fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    private fun directory(id: String) = writeDirectories.getOrPut(id) { children(id, "").associateBy { it.path }.toMutableMap() }

    fun list(): List<VaultFile> {
        val result = mutableListOf<VaultFile>()
        val seen = HashSet<String>()
        fun walk(id: String, prefix: String, depth: Int) {
            require(depth <= 64) { "Vault folders exceed 64 levels." }
            if (!seen.add(id)) return
            for (entry in children(id, prefix)) {
                // Obsidian configuration, source-control data and trash are not reference notes.
                if (entry.path.substringAfterLast('/').startsWith('.')) continue
                if (entry.mime == Document.MIME_TYPE_DIR) {
                    walk(DocumentsContract.getDocumentId(entry.uri), entry.path + "/", depth + 1)
                } else {
                    result.add(entry)
                    require(result.size <= 50_000) { "Vault exceeds 50,000 files; choose a smaller folder." }
                }
            }
        }
        walk(rootId, "", 0)
        return result
    }

    private fun children(id: String, prefix: String): List<VaultFile> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        val result = mutableListOf<VaultFile>()
        val cursor = resolver.query(childUri, columns, null, null, null)
            ?: error("The selected vault is unavailable. Choose it again.")
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                require('/' !in name && name != ".." && name != ".") { "Invalid document name" }
                result.add(VaultFile(prefix + name, uri(it.getString(0)), it.getString(2).orEmpty(), it.getLong(3), it.getLong(4)))
            }
        }
        return result
    }

    fun open(file: VaultFile): InputStream = resolver.openInputStream(file.uri)
        ?: error("Cannot read ${file.path}")

    /** Only app-generated import paths use this writer. Existing notes are never overwritten. */
    fun createIfMissing(path: String, mime: String, write: (java.io.OutputStream) -> Unit): Boolean {
        require(VaultText.validPath(path)) { "Invalid vault path" }
        val parts = path.split('/')
        var parent = rootId
        for (part in parts.dropLast(1)) {
            val siblings = directory(parent)
            val existing = siblings[part]
            val directory = if (existing != null) {
                require(existing.mime == Document.MIME_TYPE_DIR) { "$part is not a folder" }
                existing.uri
            } else DocumentsContract.createDocument(resolver, uri(parent), Document.MIME_TYPE_DIR, part)
                ?: error("Cannot create folder $part")
            siblings[part] = VaultFile(part, directory, Document.MIME_TYPE_DIR, 0, 0)
            parent = DocumentsContract.getDocumentId(directory)
        }
        val siblings = directory(parent)
        if (parts.last() in siblings) return false
        val created = DocumentsContract.createDocument(resolver, uri(parent), mime, parts.last())
            ?: error("Cannot create $path")
        try {
            val output = resolver.openOutputStream(created, "wt") ?: error("Cannot write $path")
            output.use(write)
        } catch (e: Exception) {
            // Remove only the incomplete file this operation just created, never an existing note.
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw e
        }
        siblings[parts.last()] = VaultFile(parts.last(), created, mime, 0, 0)
        return true
    }
}
