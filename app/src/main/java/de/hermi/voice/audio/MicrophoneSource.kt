package de.hermi.voice.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import de.hermi.voice.config.RecordingParams
import kotlin.math.tanh
import kotlin.concurrent.thread

/**
 * Abstrakte Audio-Quelle. v1 liefert PCM-16k-Chunks direkt vom Mikrofon.
 * v2 (Wakeword) kann diese Schicht ersetzen/erweitern, ohne den Rest der App zu ändern.
 */
interface AudioSource {
    /** Verstärkungsfaktor (1.0 = unverändert, größer = empfindlicher). Wird auf das
     *  Mikrosignal vor der Spracherkennung angewandt, bevor [onSamples] aufgerufen wird. */
    var gain: Float

    /** Startet die Aufnahme. Chunks werden via [onSamples] als FloatArray (16 kHz, mono, -1..1) geliefert. */
    fun start(onSamples: (FloatArray) -> Unit, onAmplitude: (Float) -> Unit)

    /** Beendet die Aufnahme. Aufruf von onFinished, sobald der Thread wirklich beendet ist. */
    fun stop(onFinished: () -> Unit)
}

/**
 * Direkte Mikrofonaufnahme über AudioRecord (16 kHz, mono, PCM 16 bit).
 * Push-to-talk: start() beginnt, stop() beendet.
 */
class MicrophoneSource : AudioSource {
    companion object {
        private const val TAG = "MicrophoneSource"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_FRAMES = 1600 // 100 ms bei 16 kHz
    }

    override var gain: Float = 1f

    /** Manuell einstellbare Aufnahme-Parameter (Defaults = bewährte Werte).
     *  Wird vor start() gesetzt und ersetzt die früher hartcodierten Konstanten. */
    var params: RecordingParams? = null

    // @Volatile: Diese Felder werden vom UI-Thread (start/stop) und vom Audio-Thread
    // gelesen/geschrieben. Ohne Volatile könnte die Leseschleife `while (recording)`
    // ein späteres recording=false nie sehen (Aufnahme würde nicht enden).
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recording = false
    private var thread: Thread? = null
    // Start-Wert true: Ohne gestartete Aufnahme (oder nach fehlgeschlagenem start())
    // darf stop() nicht bis zum Timeout auf einen Thread warten, den es nie gab.
    @Volatile private var finished = true

    override fun start(onSamples: (FloatArray) -> Unit, onAmplitude: (Float) -> Unit) {
        if (audioRecord != null) return

        // Aufnahme-Feintuning übernehmen (Defaults = bewährte Werte, falls nichts gesetzt).
        val p = params ?: RecordingParams(
            gain = gain,
            minTailBlocks = 8,
            silenceBlocks = 5,
            maxTailBlocks = 30,
            silenceFactor = 0.06f,
            silenceFloorAbs = 0.003f
        )
        gain = p.gain
        val minTailBlocks = p.minTailBlocks
        val silenceBlocks = p.silenceBlocks
        val maxTailBlocks = p.maxTailBlocks
        val silenceFactor = p.silenceFactor
        val silenceFloorAbs = p.silenceFloorAbs
        // Angewendete Parameter protokollieren (Filter: tag == HermiVoice), damit
        // sichtbar ist, dass die Werte nicht nur gespeichert, sondern wirklich
        // in die Aufnahme-Pipeline übernommen wurden.
        // v1.20: reine String-Konkatenation statt String.format - ein Format-Fehler
        // (IllegalFormatConversionException) ist damit strukturell unmöglich.
        Log.i(
            "HermiVoice", "v1.20 Feintuning -> gain=" + gain + " minTail=" + (minTailBlocks * 100)
                + "ms silence=" + silenceBlocks + " maxTail=" + (maxTailBlocks * 100)
                + "ms factor=" + silenceFactor + " floor=" + silenceFloorAbs
        )

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = maxOf(minBuf * 2, BUFFER_FRAMES * 2)

        // VOICE_RECOGNITION statt rohem MIC: Android schaltet hier automatische
        // Verstärkung (AGC) und Rauschunterdrückung ein. Ein leises Handy-Mikro
        // liefert mit rohem MIC oft so schwache Signale, dass eine sehr hohe
        // Nachverstärkung (Gain 12x) nötig wäre. Mit dieser Quelle bleibt der
        // Pegel in einem normalen Bereich.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
        )
        // Mikrofon nicht nutzbar (z. B. Berechtigung entzogen, Mikro von einer anderen
        // App belegt): AudioRecord wirft nicht, bleibt aber uninitialisiert. Dann sofort
        // freigeben und einen klaren Fehler melden, statt später im Thread zu scheitern.
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Mikrofon konnte nicht initialisiert werden")
        }
        audioRecord = record
        recording = true
        finished = false

        thread(name = "hermi-audio") {
            val shortBuf = ShortArray(BUFFER_FRAMES)
            val floatBuf = FloatArray(BUFFER_FRAMES)
            // Liest einen Block, verstärkt ihn und liefert ihn an die Erkennung.
            // Gibt die Peak-Amplitude des UNVERSTÄRKTEN (rohen) Blocks zurück (0..1)
            // für die Stille-Erkennung. Wichtig: Stille muss am ROH-Signal gemessen
            // werden, nicht am verstärkten. Sonst hebt die Gain-Verstärkung (z. B. 4x)
            // auch leises Grundrauschen nach dem Sprechen über die Stille-Schwelle,
            // die Stille wird nie erkannt und der Tail läuft immer bis zur Obergrenze
            // (→ 8-s-Gedenkpause). (Bug aus v1.10/v1.11, hier in v1.13 gefixt.)
            var speakPeak = 0f // höchster ROH-Peak während der Hauptaufnahme
            fun pump(): Float {
                val n = record.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) return 0f
                // ROH-Peak VOR der Verstärkung bestimmen.
                var rawPeak = 0f
                for (i in 0 until n) {
                    val a = if (shortBuf[i] < 0) -shortBuf[i] else shortBuf[i]
                    val norm = a.toFloat() / 32767.0f
                    if (norm > rawPeak) rawPeak = norm
                }
                // Verstärkung mit Soft-Clipping (tanh) statt hartem Clamp:
                // leises Signal wird bis zu `gain` angehoben, lautes greift
                // sanft in die Sättigung, ohne klickende Verzerrung.
                for (i in 0 until n) {
                    floatBuf[i] = tanh(shortBuf[i] / 32767.0f * gain)
                }
                onSamples(floatBuf.copyOf(n))
                // Lautstärke für UI-Feedback (0..1) bleibt verstärkt (sichtbar).
                var peak = 0f
                for (i in 0 until n) {
                    val a = if (floatBuf[i] < 0) -floatBuf[i] else floatBuf[i]
                    if (a > peak) peak = a
                }
                onAmplitude(peak)
                // Stille-Referenz aktualisieren: der laute Sprech-Pegel während
                // der Aufnahme ist der Maßstab, ab dem wir „Rede zu Ende“ erkennen.
                if (rawPeak > speakPeak) speakPeak = rawPeak
                return rawPeak
            }
            try {
                record.startRecording()
                while (recording) {
                    pump()
                }
                // AUSLAUF/Tail: Nach dem Loslassen steckt das Ende der gesprochenen
                // Rede wegen der Geräte-/Aufnahmelatenz noch unausgelesen im Puffer
                // von AudioRecord. Würden wir jetzt sofort record.stop() aufrufen,
                // ginge dieser letzte Teil (oft die letzten Wörter bis hin zur Hälfte
                // des Satzes) verloren und bliebe in der Transkription weg. Wir lesen
                // daher nach. Zusätzlich braucht der Streaming-Decoder (zipformer,
                // enableEndpoint=false) ein stilles Tail-Padding HINTER dem letzten
                // Wort, damit er Endsilben überhaupt zu Ende decodieren kann — zu
                // wenig Silence dahinter lässt das letzte Wort aus der Transkription
                // fallen. (Beobachtung: Abschneiden fast immer am Satzende.)
                // „Stille“ = ROH-Pegel deutlich unter dem Sprech-Pegel der Aufnahme.
                // WICHTIG (v1.15): Die 25-%-Schwelle war zu aggressiv — eine leise
                // letzte Silbe (z. B. unbetontes Satzende deutlich unter 25 % der
                // Lautstärke des lauten Satzanfangs) wurde als „Stille“ gewertet,
                // in silentRun mitgezählt und der Tail endete ohne sauberes Nachlauf-
                // Padding direkt nach der Silbe → Endsilbe fiel aus der Transkription
                // (Messung: 895 ms komplett vs. 935 ms Silbe fehlt, gleiche Länge →
                // nicht die Länge, sondern die Klassifikation ist die Ursache).
                // Schwelle auf ~6 % des Sprech-Pegels gesenkt; nur echte (fast
                // vollständige) Stille zählt jetzt. Absoluter Fallback bleibt für
                // sehr leise Räume, wo der ROH-Pegel nie nah an den Sprech-Pegel
                // herankommt.
                var silenceRef = maxOf(speakPeak * silenceFactor, silenceFloorAbs)
                var silentRun = 0
                var tailCount = 0
                while (tailCount < maxTailBlocks) {
                    val rawPeak = pump()
                    tailCount++
                    silentRun = if (rawPeak < silenceRef) silentRun + 1 else 0
                    // Erst nach dem Mindest-Nachlauf und anhaltender Stille beenden.
                    if (tailCount >= minTailBlocks && silentRun >= silenceBlocks) break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Aufnahme-Fehler", e)
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
                audioRecord = null
                finished = true
            }
        }
    }

    override fun stop(onFinished: () -> Unit) {
        recording = false
        thread(name = "hermi-audio-stop") {
            // Warten bis der Aufnahme-Thread den Record freigegeben hat.
            // Budget muss die Tail-Obergrenze (8 s) + Decoder-Nachlauf abdecken.
            // 600 × 20 ms = 12 s. Früher stand hier 110 → nur 2,2 s, was den
            // 8-s-Tail unterdeckte: onFinished() feuerte zu früh und das
            // Satzende ging verloren.
            var wait = 0
            while (!finished && wait < 600) { // ~12 s
                Thread.sleep(20)
                wait++
            }
            onFinished()
        }
    }
}
