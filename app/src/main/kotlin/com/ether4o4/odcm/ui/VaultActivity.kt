package com.ether4o4.odcm.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ether4o4.odcm.data.Settings
import com.ether4o4.odcm.vault.VaultHit
import com.ether4o4.odcm.vault.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** The same selected tree is used by local search, export imports and agent recall. */
class VaultActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: Settings
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private val controls = mutableListOf<Button>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply { text = "Obsidian vault"; textSize = 24f })
        status = TextView(this).apply { text = if (settings.vaultUri.isBlank()) "No vault selected" else "Vault selected. Search to refresh its index." }
        root.addView(status)
        fun button(text: String, action: () -> Unit) {
            root.addView(Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { action() }; controls.add(this) })
        }
        button("Choose vault folder") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), PICK_VAULT)
        }
        root.addView(TextView(this).apply {
            text = "Choose the same folder you open in Obsidian. Notes, subfolders, text/code, Canvas, Office documents, and PDF text on Android 15+ are searchable. Images, audio, videos, scanned pages and unsupported files are searchable by filename only.\n\nSearch runs on this phone. During an agent task, matching excerpts may be sent to your configured Claude API. Files are reference data, never instructions to execute."
            textSize = 13f
        })
        val query = EditText(this).apply { hint = "Search anything in the vault"; maxLines = 3 }
        root.addView(query)
        button("Search vault") { work {
            val text = query.text.toString().trim()
            require(text.isNotEmpty()) { "Enter search words first." }
            status.text = "Checking for new and changed files…"
            val found = runInterruptible(Dispatchers.IO) { VaultRepository(this@VaultActivity).use { repo ->
                repo.refresh(progress = ::indexProgress)
                repo.search(text, 20) to repo.stats()
            } }
            results.removeAllViews()
            status.text = "${found.second}\n${found.first.size} matching passages"
            for (hit in found.first) results.addView(Button(this@VaultActivity).apply {
                isAllCaps = false; this.text = "${hit.path}\n${hit.excerpt.take(240).ifBlank { hit.status }}"
                setOnClickListener { showPassage(hit) }
            })
        } }
        button("Rebuild index") { work {
            status.text = "Rebuilding…"
            val summary = runInterruptible(Dispatchers.IO) { VaultRepository(this@VaultActivity).use { it.refresh(true, ::indexProgress).toString() } }
            status.text = summary
        } }
        button("Show files needing attention") { work {
            val problems = runInterruptible(Dispatchers.IO) { VaultRepository(this@VaultActivity).use { it.refresh(progress = ::indexProgress); it.problems() } }
            AlertDialog.Builder(this@VaultActivity).setTitle("File coverage (up to 100)")
                .setMessage(problems.joinToString("\n\n").ifEmpty { "All indexed files have extractable text." }).setPositiveButton("Close", null).show()
        } }
        button("Import GPT / Claude export") {
            requireVault {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/json", "application/octet-stream")), PICK_EXPORT)
            }
        }
        root.addView(TextView(this).apply {
            text = "Imports convert conversations to Markdown under Imports/ and keep the unchanged export under Archive/. Repeat imports skip identical conversations. Existing notes are not overwritten. Conversion is local and makes no API calls."
            textSize = 13f
        })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)
    }

    private fun showPassage(hit: VaultHit, offset: Int = hit.offset) { work {
        val text = runInterruptible(Dispatchers.IO) { VaultRepository(this@VaultActivity).use { it.read(hit.path, offset) } }
        val next = Regex("read_vault at offset ([0-9]+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        AlertDialog.Builder(this@VaultActivity).setTitle(hit.path).setMessage(text)
            .setNegativeButton("Close", null)
            .setNeutralButton("Open original") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(hit.uri), contentResolver.getType(Uri.parse(hit.uri)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { status.text = "No app available to open this format." }
            }.apply { if (next != null) setPositiveButton("Next passage") { _, _ -> showPassage(hit, next) } }.show()
    } }

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        requireVault {
            busy = true; controls.forEach { it.isEnabled = false }
            scope.launch {
                try { block() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status.text = "${e.message ?: "Vault operation failed"}\nYour source files are unchanged. You can retry." }
                finally { busy = false; controls.forEach { it.isEnabled = true } }
            }
        }
    }

    private fun requireVault(block: () -> Unit) {
        if (settings.vaultUri.isBlank()) status.text = "Choose your vault folder first." else block()
    }

    private fun indexProgress(done: Int, total: Int) = progress("Indexing $done / $total files…")
    private fun progress(message: String) { runOnUiThread { if (!isDestroyed) status.text = message } }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == PICK_VAULT) {
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Read access was not granted." }
                if (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                else contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                settings.vaultUri = uri.toString()
                results.removeAllViews()
                status.text = "Vault connected. Search now, or import an export."
            } catch (e: Exception) { status.text = "Cannot connect vault: ${e.message}" }
        } else if (requestCode == PICK_EXPORT) work {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "export.json"
            status.text = runInterruptible(Dispatchers.IO) { VaultRepository(this@VaultActivity).use { it.importExport(uri, name, ::progress) } }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object { private const val PICK_VAULT = 3101; private const val PICK_EXPORT = 3102 }
}
