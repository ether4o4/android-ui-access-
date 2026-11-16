package com.ether4o4.odcm.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class VaultHit(val path: String, val uri: String, val excerpt: String, val offset: Int, val status: String)
data class VaultStats(val files: Int, val indexed: Int, val filenameOnly: Int, val errors: Int) {
    override fun toString() = "$files files · $indexed with searchable text · $filenameOnly filename only · $errors errors"
}

/** Private, disposable FTS cache. Source documents stay in the chosen Obsidian folder. */
class VaultIndex(context: Context) : SQLiteOpenHelper(context, "vault-reference.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE files (path TEXT PRIMARY KEY, uri TEXT, modified INTEGER, size INTEGER, status TEXT, generation INTEGER)")
        db.execSQL("CREATE VIRTUAL TABLE chunks USING fts4(path, body, start, tokenize=unicode61)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun refresh(tree: String, files: List<VaultFile>, force: Boolean = false,
                progress: (Int, Int) -> Unit = { _, _ -> }, extract: (VaultFile) -> ExtractedText): VaultStats = synchronized(LOCK) {
        val db = writableDatabase
        val previous = db.rawQuery("SELECT value FROM meta WHERE key='tree'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        if (previous != tree || force) {
            db.beginTransaction()
            try {
                db.delete("chunks", null, null); db.delete("files", null, null)
                db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('tree',?)", arrayOf(tree))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val generation = System.currentTimeMillis()
        for ((index, file) in files.withIndex()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val unchanged = db.rawQuery("SELECT modified,size,status,uri FROM files WHERE path=?", arrayOf(file.path)).use {
                it.moveToFirst() && file.modified > 0 && it.getLong(0) == file.modified && it.getLong(1) == file.size &&
                    !it.getString(2).startsWith("Error:") && it.getString(3) == file.uri.toString()
            }
            if (unchanged) {
                db.execSQL("UPDATE files SET generation=? WHERE path=?", arrayOf(generation, file.path))
            } else {
                val extracted = try { extract(file) } catch (e: InterruptedException) { throw e }
                    catch (e: Exception) { ExtractedText("", "Error: ${e.message?.take(200) ?: e.javaClass.simpleName}") }
                db.beginTransaction()
                try {
                    db.delete("chunks", "path=?", arrayOf(file.path))
                    val chunks = VaultText.chunks(extracted.text).toList().ifEmpty { listOf("") }
                    var offset = 0
                    for (body in chunks) {
                        db.insertOrThrow("chunks", null, ContentValues().apply {
                            put("path", file.path); put("body", body); put("start", offset.toString())
                        })
                        offset += body.length
                    }
                    db.insertWithOnConflict("files", null, ContentValues().apply {
                        put("path", file.path); put("uri", file.uri.toString()); put("modified", file.modified)
                        put("size", file.size); put("status", extracted.status); put("generation", generation)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            if (index % 25 == 0 || index == files.lastIndex) progress(index + 1, files.size)
        }
        // list() must finish successfully before refresh is called. An inaccessible provider never clears the cache.
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM chunks WHERE path IN (SELECT path FROM files WHERE generation != ?)", arrayOf(generation))
            db.execSQL("DELETE FROM files WHERE generation != ?", arrayOf(generation))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        stats()
    }

    fun stats(): VaultStats = synchronized(LOCK) {
        var files = 0; var text = 0; var errors = 0
        readableDatabase.rawQuery("SELECT status, COUNT(*) FROM files GROUP BY status", null).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(1); files += n
                if (c.getString(0) == "Text indexed") text += n
                if (c.getString(0).startsWith("Error:")) errors += n
            }
        }
        VaultStats(files, text, files - text - errors, errors)
    }

    fun search(query: String, limit: Int = 8): List<VaultHit> = synchronized(LOCK) {
        val max = limit.coerceIn(1, 30)
        val expression = VaultText.matchQuery(query)
        if (expression.isEmpty()) return@synchronized emptyList()
        fun candidates(all: Boolean): List<VaultHit> {
            val hits = mutableListOf<VaultHit>()
            readableDatabase.rawQuery(
                "SELECT chunks.path,files.uri,chunks.body,chunks.start,files.status FROM chunks JOIN files ON files.path=chunks.path WHERE chunks MATCH ? LIMIT 400",
                arrayOf(VaultText.matchQuery(query, all)),
            ).use { c -> while (c.moveToNext()) hits.add(VaultHit(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4))) }
            return hits
        }
        val exact = candidates(true)
        val matches = if (exact.size >= max) exact else (exact + candidates(false)).distinctBy { it.path to it.offset }
        matches.sortedByDescending { VaultText.score(it.path, it.excerpt, query) }.take(max)
    }

    /** Paged reads from extracted text. No file path from a model is used to reach outside the index. */
    fun read(path: String, offset: Int = 0, limit: Int = 6000): String = synchronized(LOCK) {
        require(VaultText.validPath(path)) { "Invalid path" }
        require(offset >= 0) { "Offset must be positive" }
        val status = readableDatabase.rawQuery("SELECT status FROM files WHERE path=?", arrayOf(path)).use {
            require(it.moveToFirst()) { "File not found; search for its exact path first." }; it.getString(0)
        }
        if (status != "Text indexed") return@synchronized status
        val length = limit.coerceIn(1, 12_000)
        val out = StringBuilder()
        var hasMore = false
        readableDatabase.rawQuery("SELECT body,start FROM chunks WHERE path=? ORDER BY CAST(start AS INTEGER)",
            arrayOf(path)).use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) + c.getString(0).length <= offset) continue
                val part = c.getString(0).drop((offset - c.getInt(1)).coerceAtLeast(0))
                val remaining = length - out.length
                out.append(part.take(remaining))
                if (part.length > remaining || (out.length >= length && !c.isLast)) { hasMore = true; break }
            }
        }
        "Source: $path\nOffset: $offset\n" + out + if (hasMore) "\n[More text: read_vault at offset ${offset + out.length}]" else "\n[End of file]"
    }

    fun requireTree(tree: String) = synchronized(LOCK) {
        val actual = readableDatabase.rawQuery("SELECT value FROM meta WHERE key='tree'", null).use { if (it.moveToFirst()) it.getString(0) else "" }
        require(actual == tree) { "Vault selection changed; refresh the index before searching." }
    }

    fun problems(): List<String> = synchronized(LOCK) {
        val rows = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT path,status FROM files WHERE status != 'Text indexed' ORDER BY path LIMIT 100", null).use {
            while (it.moveToNext()) rows.add("${it.getString(0)} — ${it.getString(1)}")
        }
        rows
    }

    companion object { private val LOCK = Any() }
}
