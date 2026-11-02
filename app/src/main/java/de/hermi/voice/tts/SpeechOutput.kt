package de.hermi.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import de.hermi.voice.AppLocale
import java.util.Locale

/**
 * Systemweite Sprachausgabe der Antwort über die Android-eingebaute TTS-Engine.
 *
 * Keine externe Dependency — TextToSpeech ist Teil des Android-Frameworks.
 *
 * Wichtig: Die Engine-Init ([TextToSpeech]) läuft asynchron und kann beim
 * ersten Start (Engine-Bindung) mehrere Sekunden dauern. Wird [speak] vorher
 * aufgerufen, darf die Nachricht NICHT still verworfen werden — sie wird
 * gepuffert und gesprochen, sobald der OnInit-Listener SUCCESS meldet.
 */
class SpeechOutput(context: Context) {

    private val appContext = context.applicationContext

    @Volatile private var initStatus = TextToSpeech.ERROR
    @Volatile private var initCalled = false
    /** Während der Engine-Init gepufferte Nachricht (wird nach SUCCESS sofort gesprochen). */
    @Volatile private var pendingText: String? = null

    private var tts: TextToSpeech? = null

    private val listener = TextToSpeech.OnInitListener { status ->
        initStatus = status
        initCalled = true
        if (status == TextToSpeech.SUCCESS) {
            Log.i(TAG, "TTS-Init: SUCCESS")
            // Puffer nachholen, falls während der Initialisierung gesprochen
            // werden sollte.
            val buffered = pendingText
            if (buffered != null) {
                pendingText = null
                doSpeak(buffered)
            }
        } else {
            Log.w(TAG, "TTS-Init fehlgeschlagen: status=$status (bitte TTS-Engine und Sprachdaten prüfen)")
        }
    }

    init {
        synchronized(this) {
            tts = TextToSpeech(appContext, listener)
        }
    }

    /**
     * Spielt [text] über die System-TTS-Engine ab. Bricht eine eventuell
     * noch laufende Wiedergabe restlos ab (QUEUE_FLUSH). Ist die Engine
     * noch nicht initialisiert, wird der Text gepuffert und direkt nach
     * erfolgreicher Init gesprochen (kein stiller Verlust).
     */
    fun speak(text: String) {
        if (!initCalled) {
            // Engine-Init läuft noch — Text merken und nachholen.
            pendingText = text
            return
        }
        if (initStatus != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS nicht bereit, übersprungen (status=$initStatus)")
            return
        }
        doSpeak(text)
    }

    private fun doSpeak(text: String) {
        val engine = tts ?: return
        try {
            // Die App-Sprache bevorzugen (folgt dem Sprachmodus); falls die
            // Stimme dafür keine Sprachdaten hat, auf die System-Sprache
            // zurückfallen — so bricht eine fehlende Stimme die Wiedergabe
            // nicht ab.
            val langResult = engine.setLanguage(AppLocale.effectiveLocale(appContext))
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED ||
                langResult == TextToSpeech.ERROR_NOT_INSTALLED_YET
            ) {
                Log.w(TAG, "App-Sprache nicht verfügbar (langResult=$langResult), nutze Systemstimme")
                engine.setLanguage(Locale.getDefault())
            }
            // Markdown entfernen und an der Engine-Grenze in Stücke teilen: speak()
            // verwirft Text über getMaxSpeechInputLength() komplett. Das erste Stück
            // flusht eine evtl. laufende Wiedergabe, die weiteren werden angehängt.
            val chunks = TtsTextPrep.split(
                TtsTextPrep.clean(text),
                TextToSpeech.getMaxSpeechInputLength() - 100
            )
            if (chunks.isEmpty()) return
            chunks.forEachIndexed { i, chunk ->
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val result = engine.speak(chunk, mode, null, "hermi_answer_$i")
                if (result != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TTS speak() status=$result (0=SUCCESS), Stück $i")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak() Fehler", e)
        }
    }

    /** Bricht die laufende Wiedergabe ab (z. B. beim Start einer neuen Aufnahme). */
    fun stop() {
        pendingText = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "TTS stop() Fehler", e)
        }
    }

    /** Gibt die Engine frei (z. B. im onDestroy der Activity). */
    fun shutdown() {
        synchronized(this) {
            pendingText = null
            tts?.shutdown()
            tts = null
            initCalled = false
        }
    }

    companion object {
        private const val TAG = "HermiVoice"
    }
}