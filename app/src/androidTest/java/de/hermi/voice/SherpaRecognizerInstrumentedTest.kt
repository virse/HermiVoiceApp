package de.hermi.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifiziert sherpa-onnx + deutsches Kroko-Modell auf dem Gerät/Emulator.
 *
 * Erwartet das entpackte Modell in getFilesDir()/models/ und eine 16-kHz-WAV
 * in getFilesDir()/test.wav (wird von einem Setup-Skript per adb gepusht).
 */
@RunWith(AndroidJUnit4::class)
class SherpaRecognizerInstrumentedTest {

    @Test
    fun transcribesGermanWav() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val testPkg = InstrumentationRegistry.getInstrumentation().context
        val modelDir = File(app.filesDir, "models")
        val wav = File(app.filesDir, "test.wav")

        // Modell + Test-WAV aus den Test-Assets extrahieren
        extractAsset(testPkg, "models/encoder.onnx", File(modelDir, "encoder.onnx"))
        extractAsset(testPkg, "models/decoder.onnx", File(modelDir, "decoder.onnx"))
        extractAsset(testPkg, "models/joiner.onnx", File(modelDir, "joiner.onnx"))
        extractAsset(testPkg, "models/tokens.txt", File(modelDir, "tokens.txt"))
        extractAsset(testPkg, "test.wav", wav)

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, "encoder.onnx").absolutePath,
                decoder = File(modelDir, "decoder.onnx").absolutePath,
                joiner = File(modelDir, "joiner.onnx").absolutePath
            ),
            tokens = File(modelDir, "tokens.txt").absolutePath,
            numThreads = 2,
            debug = false,
            provider = "cpu",
            modelType = "zipformer2"
        )
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )

        val recognizer = OnlineRecognizer(config = config)
        val stream = recognizer.createStream()

        val samples = readWav16kMono(wav)
        // In 100-ms-Blöcken füttern
        val chunk = 1600
        for (i in samples.indices step chunk) {
            val end = minOf(i + chunk, samples.size)
            stream.acceptWaveform(samples.copyOfRange(i, end), sampleRate = 16000)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
        }
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()

        recognizer.release()

        println("TRANSCRIPT=<$text>")
        assertTrue("Transkription leer!", text.isNotBlank())
    }

    /** Kopiert eine Asset-Datei nach target (überschreibt bei erneutem Lauf). */
    private fun extractAsset(context: android.content.Context, assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists() && target.length() > 0L) return
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** Liest eine 16-kHz-mono-PCM16-WAV als FloatArray (-1..1). */
    private fun readWav16kMono(file: File): FloatArray {
        val bytes = file.readBytes()
        // WAV-Header 44 Bytes; hier für 16-bit PCM mono angenommen
        val dataStart = 44
        val count = (bytes.size - dataStart) / 2
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = bytes[dataStart + i * 2].toInt() and 0xFF
            val hi = bytes[dataStart + i * 2 + 1].toInt()
            val s = (hi shl 8) or lo
            out[i] = s / 32767.0f
        }
        return out
    }
}
