package com.ether4o4.odcm.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/** The model is installed from APK assets, never downloaded at runtime. */
internal class KokoroVoice(private val context: Context) : VoiceSynthesizer {
    private var engine: OfflineTts? = null

    override fun prepare() = synchronized(nativeLock) {
        if (engine != null) return@synchronized
        val root = File(context.noBackupFilesDir, "kokoro-fp32-v1-60703707")
        val marker = File(root, ".complete")
        if (!marker.isFile) {
            root.deleteRecursively()
            copyAssets("kokoro", root)
            marker.writeText("60703707\n")
        }
        engine = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = File(root, "model.onnx").path,
                voices = File(root, "voices.bin").path,
                tokens = File(root, "tokens.txt").path,
                dataDir = File(root, "espeak-ng-data").path,
                lexicon = File(root, "lexicon-us-en.txt").path,
                lang = "en-us",
            ), numThreads = 2, debug = false, provider = "cpu",
        )))
        check(engine!!.numSpeakers() > MICHAEL) { "Bundled voice map is incomplete" }
    }

    override fun generate(text: String): VoicePcm = synchronized(nativeLock) {
        val generated = checkNotNull(engine).generate(text, sid = MICHAEL, speed = 1.0f)
        require(generated.sampleRate > 0 && generated.samples.isNotEmpty()) { "Empty speech" }
        require(generated.samples.all { it.isFinite() }) { "Invalid speech samples" }
        VoicePcm(generated.samples, generated.sampleRate)
    }

    override fun close() { synchronized(nativeLock) { engine?.release(); engine = null } }

    private fun copyAssets(asset: String, destination: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            check(destination.mkdirs() || destination.isDirectory)
            children.forEach { copyAssets("$asset/$it", File(destination, it)) }
        }
    }

    companion object {
        const val MICHAEL = 16 // kokoro-multi-lang-v1_0: am_michael
        // eSpeak uses process-global state: serialize initialization, inference and release.
        private val nativeLock = Any()
    }
}
