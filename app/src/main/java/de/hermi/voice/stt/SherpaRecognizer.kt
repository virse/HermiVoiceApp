package de.hermi.voice.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Deutsche Offline-Spracherkennung über sherpa-onnx.
 * Modell: sherpa-onnx-streaming-zipformer-de-kroko (zipformer2, 16 kHz).
 */
class SherpaRecognizer(modelDir: File, private val sampleRate: Int = 16000, numThreads: Int = 2) {

    private val recognizer: OnlineRecognizer

    init {
        val encoder = File(modelDir, "encoder.onnx").absolutePath
        val decoder = File(modelDir, "decoder.onnx").absolutePath
        val joiner = File(modelDir, "joiner.onnx").absolutePath
        val tokens = File(modelDir, "tokens.txt").absolutePath

        require(File(encoder).exists() && File(decoder).exists() &&
            File(joiner).exists() && File(tokens).exists()) {
            "Modell unvollständig in $modelDir"
        }

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = encoder,
                decoder = decoder,
                joiner = joiner
            ),
            tokens = tokens,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
            modelType = "zipformer2"
        )

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
            // Keine automatische Segment-End-Erkennung: Die App ist Push-to-Talk
            // (der Nutzer beendet die Aufnahme selbst per Knopf). Bei aktivem
            // Endpoint-Detection schließt sherpa-onnx bei jeder kleinen Sprechpause
            // ein Segment ab und wirft es weg → nach 2-4 Wörtern ist Schluss, egal
            // wie lang man weiterredet. Mit enableEndpoint=false bleibt es EIN
            // fortlaufendes Segment und getResult() enthält die ganze Äußerung.
            enableEndpoint = false
        )

        recognizer = OnlineRecognizer(config = config)
    }

    fun createStream(): OnlineStream = recognizer.createStream()

    /** Zufuhr eines Audio-Chunks (100 ms) und unmittelbare inkrementelle Decodierung.
     *  Wichtig für Streaming-Modelle (zipformer2): Nur wer jeden Chunk sofort in eine
     *  decode-Schleife schickt, schreibt den Decoder-Zustand fort. Ohne das wird nur
     *  der Anfang des Signals erkannt und der Rest verworfen. */
    fun feed(stream: OnlineStream, samples: FloatArray) {
        stream.acceptWaveform(samples, sampleRate)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
    }

    /** Liefert den aktuellen Zwischenstand (für Live-Anzeige während des Sprechens). */
    fun partialResult(stream: OnlineStream): String =
        recognizer.getResult(stream).text

    /** Markiert das Ende der Eingabe und liefert das finale Ergebnis.
     *  `inputFinished()` ist Pflicht, damit das Modell die letzte (evtl. unvollständige)
     *  Äußerung als abgeschlossen erkennen und zu Ende decodieren kann. */
    fun finalResult(stream: OnlineStream): String {
        stream.inputFinished()
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        return recognizer.getResult(stream).text.trim()
    }

    fun release() = recognizer.release()
}
