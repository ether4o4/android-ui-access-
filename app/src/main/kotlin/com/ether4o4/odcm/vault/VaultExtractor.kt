package com.ether4o4.odcm.vault

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipInputStream

data class ExtractedText(val text: String, val status: String)

/** Text extraction uses platform APIs; images/audio are explicitly filename-searchable only. */
class VaultExtractor(private val context: Context, private val documents: VaultDocuments) {
    fun extract(file: VaultFile): ExtractedText {
        val ext = file.path.substringAfterLast('.', "").lowercase()
        val text = when {
            VaultText.isText(file.path, file.mime) -> documents.open(file).use { VaultText.read(it) }
            ext in setOf("docx", "pptx", "xlsx", "odt") -> office(file, ext)
            ext == "pdf" && Build.VERSION.SDK_INT >= 35 -> pdf(file)
            ext == "pdf" -> return ExtractedText("", "Filename only: PDF text needs Android 15 or newer")
            else -> return ExtractedText("", "Filename only: no text extractor for this format")
        }
        return ExtractedText(text, if (text.isBlank()) "Filename only: no extractable text (OCR is not enabled)" else "Text indexed")
    }

    private fun office(file: VaultFile, ext: String): String {
        val out = StringBuilder()
        var entries = 0
        var expanded = 0L
        ZipInputStream(documents.open(file)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 20_000) { "Office archive contains too many entries" }
                val name = entry.name
                val relevant = when (ext) {
                    "docx" -> name == "word/document.xml" || name.matches(Regex("word/(footnotes|endnotes|comments|header[0-9]+|footer[0-9]+)\\.xml"))
                    "pptx" -> name.matches(Regex("ppt/(slides/slide|notesSlides/notesSlide)[0-9]+\\.xml"))
                    "xlsx" -> name == "xl/sharedStrings.xml" || name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml"))
                    else -> name == "content.xml"
                }
                // Bound every entry, including files we skip: a malicious ZIP cannot expand indefinitely.
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    expanded += n
                    require(expanded <= 128L * 1024 * 1024) { "Expanded Office document exceeds 128 MB" }
                    if (relevant) {
                        require(bytes.size() + n <= 32 * 1024 * 1024) { "Office section is too large" }
                        bytes.write(buffer, 0, n)
                    }
                }
                if (relevant) {
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    parser.setInput(bytes.toByteArray().inputStream(), "UTF-8")
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.TEXT) out.append(parser.text)
                        if (event == XmlPullParser.END_TAG && parser.name in setOf("p", "row", "t", "v", "table-row")) out.append('\n')
                        require(out.length <= VaultText.MAX_CHARS) { "Extracted document text is too large" }
                        event = parser.next()
                    }
                    out.append('\n')
                }
            }
        }
        return out.toString()
    }

    @androidx.annotation.RequiresApi(35)
    private fun pdf(file: VaultFile): String {
        val copy = File.createTempFile("vault-pdf-", ".pdf", context.cacheDir)
        try {
            documents.open(file).use { input -> copy.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 128L * 1024 * 1024) { "PDF exceeds 128 MB" }
                    output.write(buffer, 0, n)
                }
            } }
            return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    require(renderer.pageCount <= 2000) { "PDF exceeds 2,000 pages" }
                    buildString {
                        for (page in 0 until renderer.pageCount) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            renderer.openPage(page).use { p ->
                                val contents = p.textContents.filter { it.text.isNotBlank() }
                                if (contents.isNotEmpty()) append("\n[Page ${page + 1}]\n")
                                for (text in contents) append(text.text).append('\n')
                            }
                            require(length <= VaultText.MAX_CHARS) { "PDF text is too large" }
                        }
                    }
                }
            }
        } finally { copy.delete() }
    }
}
