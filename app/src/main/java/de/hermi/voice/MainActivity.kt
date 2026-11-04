package de.hermi.voice

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.ViewOutlineProvider
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.text.InputFilter
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.snackbar.Snackbar
import de.hermi.voice.AppLocale
import de.hermi.voice.api.ChatSender
import de.hermi.voice.api.HermesApiSender
import de.hermi.voice.api.StreamCancellation
import de.hermi.voice.audio.AudioSource
import de.hermi.voice.audio.MicrophoneSource
import de.hermi.voice.config.AppConfig
import de.hermi.voice.config.BotProfile
import de.hermi.voice.config.ChatBackground
import java.net.URLEncoder
import java.util.Locale
import de.hermi.voice.session.ChatMessage
import de.hermi.voice.session.ChatSession
import de.hermi.voice.session.GatewaySyncResult
import de.hermi.voice.session.PendingSyncStore
import de.hermi.voice.session.SearchHit
import de.hermi.voice.session.SessionController
import de.hermi.voice.ui.MarkdownFormatter
import de.hermi.voice.session.SessionStore
import de.hermi.voice.stt.ModelManager
import de.hermi.voice.stt.SherpaRecognizer
import de.hermi.voice.update.UpdateManager
import de.hermi.voice.tts.SpeechOutput
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var config: AppConfig
    // Sende-Routing (Server/Key/Modell je Bot, Gateway-Ziel je Session).
    private val chatSender by lazy(LazyThreadSafetyMode.NONE) { ChatSender(config) }
    // Such-/Sync-Logik der Sessions.
    private val sessionController by lazy(LazyThreadSafetyMode.NONE) { SessionController(chatSender) }
    /** Akzent beim letzten onCreate; onResume erstellt die Activity neu, falls
     *  sich der Akzent in den Einstellungen geändert hat (Theme-Wechsel). */
    private var appliedAccent: String = ""
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var statusModel: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var recordButton: Button

    /** true, solange die App sichtbar ist (Vordergrund). Nur dann wird KEINE
     *  Benachrichtigung bei fertiger Antwort gepostet — solange der Chat sichtbar
     *  ist, wäre ein zusätzliches Signal störend. */
    @Volatile
    private var appInForeground = true

    // Hintergrund-Polling: holt in regelmäßigen Abständen neue Inhalte vom
    // Gateway (z. B. Antworten, die auf einem anderen Gerät entstanden sind),
    // solange die App sichtbar ist und keine Antwort gestreamt wird. Ein
    // einfacher Handler-Timer auf dem UI-Thread; der eigentliche Netzwerk-
    // Abgleich läuft in syncSessionsFromGateway auf einem eigenen Thread.
    private val POLL_INTERVAL_MS = 20_000L
    private val RESUME_SYNC_DELAY_MS = 1_500L
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            maybePollSync()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }
    // true, solange ein Gateway-Abgleich (manuell oder Poll) läuft — verhindert
    // überlappende Requests, die sich gegenseitig die Historie überschreiben.
    @Volatile private var gatewaySyncRunning = false

    private val audioSource: AudioSource = MicrophoneSource()

    // Zielgröße (px) für eigene Avatar-Bilder; reicht für die kleinen Avatare
    // im Chat aus und bleibt speicherschonend.
    private val AVATAR_PX = 160
    // @Volatile: wird im Hintergrund-Thread geladen/freigegeben und im UI-Thread
    // gelesen (Bugfix 1.0.1: Modellaufbau läuft nicht mehr auf dem Main-Thread).
    @Volatile private var recognizer: SherpaRecognizer? = null
    /** Läuft gerade ein Modell-Aufbau im Hintergrund? Verhindert parallele Ladevorgänge. */
    @Volatile private var recognizerLoading = false
    @Volatile private var recording = false
    /** Einmal-Hinweis „Zugangsdaten nicht mehr entschlüsselbar“ (Bugfix 1.0.1). */
    private var secretWarningShown = false

    // Haptik-Feedback (Vibration) beim Aufnahme-Start/Stopp. VIBRATE ist eine
    // Normal-Disclosure-Permission, kein Runtime-Prompt — via Manifest erteilt.
    private val vibrator: Vibrator by lazy(LazyThreadSafetyMode.NONE) {
        applicationContext.getSystemService(Vibrator::class.java)
    }

    // Abbruch-Token der aktuell laufenden Streaming-Anfrage; null, wenn gerade
    // keine Antwort gestreamt wird. Der Stopp-Button (umgenutzter Aufnahme- bzw.
    // Senden-Button) bricht darüber die laufende Antwort ab.
    @Volatile private var activeStreamCancel: StreamCancellation? = null
    // ID der Session, zu der die gerade laufende Antwort gehört (null = keine). Dient
    // dazu, den Stream abzubrechen, wenn genau diese Session gelöscht wird.
    @Volatile private var activeStreamSessionId: String? = null

    // Systemweite Sprachausgabe der Antwort. Wird nur nach einer Mikrofon-
    // Eingabe gestartet (fromVoice) und nur, wenn der Nutzer den TTS-Toggle
    // eingeschaltet hat (config.ttsReadAnswer). Erst in onCreate befüllt,
    // damit applicationContext sicher existiert.
    private var speechOutput: SpeechOutput? = null

    // Proaktive Benachrichtigungs-Berechtigung (Android 13+): Wird beim App-Start
    // angefragt, falls der Benachrichtigungs-Toggle aktiv ist, die Runtime-
    // Permission aber fehlt (z. B. nach Neuinstallation/Update, wo sie zurückgesetzt
    // wurde — sonst schluckt notify() die "Antwort fertig"-Benachrichtigung still).
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private fun maybeRequestNotificationPermission() {
        if (config.notifyOnAnswer && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Version, mit der der aktuelle Recognizer geladen wurde (für Reload nach
     *  Modell-Update aus den Einstellungen). */
    private var loadedModelVersion: String = ""
    /** Sprache, für die der Recognizer zuletzt geladen wurde. */
    private var loadedModelLang: String = ""

    private lateinit var inputRow: LinearLayout
    private lateinit var inputField: EditText
    /** Zeitpunkt (uptime) des letzten Touchs auf eine Nachrichten-Bubble (Fokus-Wächter). */
    private var lastBubbleTouchTime = 0L
    private lateinit var keyboardToggle: ImageButton
    private lateinit var sendButton: ImageButton
    private lateinit var pasteButton: ImageButton

    // Bot+Cloud-Wechsel (Topbar-Dropdown). activeBot == null UND activeCloud == null
    // bedeutet Standard-Agent (globale Konfiguration). Die Liste im Spinner wird
    // bei onCreate und bei onResume neu geladen, damit Änderungen aus den
    // Einstellungen greifen. Cloud-Provider sind OpenAI-kompatible Dienste.
    private lateinit var botSpinner: Spinner
    private var activeBot: BotProfile? = null
    private var activeCloud: de.hermi.voice.config.CloudProvider? = null
    // Parallel zur Spinner-Anzeigeposition (Index 0 = Standard).
    private var spinnerBots: List<BotProfile> = emptyList()
    private var spinnerClouds: List<de.hermi.voice.config.CloudProvider> = emptyList()
    // Guard: verhindert Rekursion, wenn wir die Auswahl programmatisch setzen.
    private var spinnerProgrammatic = false

    // Session-Verwaltung: alle Sessions + die aktuell aktive. Die Historie der
    // aktiven Session wird bei jeder Anfrage komplett über `messages` an den
    // stateless API-Endpoint geschickt, damit Hermes den Kontext behält.
    private val sessions = mutableListOf<ChatSession>()
    private lateinit var activeSession: ChatSession
    private var sessionSearchQuery = ""

    // Anzahl aufeinanderfolgender Primär-Fehlschläge je Session (NUR im Speicher,
    // nicht persistiert). Key = Session-ID. Erreicht der Zähler die konfigurierte
    // Schwelle (config.primaryFailThreshold), werden künftige Anfragen dieser
    // Session direkt ans Fallback-Modell gesendet, statt jedes Mal ~30 s auf das
    // nicht erreichbare Primärmodell zu warten. Eine erfolgreiche Primär-Antwort
    // setzt den Zähler zurück. Reset automatisch bei Sessionwechsel (neuer Key)
    // oder App-Neustart (frische Map).
    // ConcurrentHashMap: der Sende-Thread liest/schreibt den Zähler, die UI nicht
    // synchron dazu.
    private val primaryFailStreaks = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Referenz auf den offenen Session-Dialog (zum Schließen beim Wechsel).
    private var activeDialog: AlertDialog? = null

    // Live-Vorschau der gerade erkennungsphase (Zwischenergebnis)
    private var liveText: TextView? = null

    // Wurzel der Live-Vorschau-Blase = das direkte Kind von chatContainer.
    // Wird beim Stoppen als GANZES entfernt (nicht nur bubbleBody/Text), sonst
    // bliebe die partielle Vorschau als zweite "Du"-Blase stehen.
    private var liveBubbleRoot: View? = null

    // Denkprozess (Reasoning) der aktuell streamenden Hermes-Nachricht.
    // Standard zugeklappt; true, sobald der Nutzer die Kopfzeile antippt.
    private var reasoningExpanded = false

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        // Akzentfarben-Overlay-Theme vor dem Rendern setzen, damit ?attr/... im
        // Layout und in den Ressourcen gegen die gewählte Farbe aufgelöst wird.
        setTheme(config.accentTheme())
        appliedAccent = config.accentColorName
        setContentView(R.layout.activity_main)
        // Edge-to-Edge (targetSdk 36): Inhalte zeichnen selbst unter die
        // Systemleisten; so erreichen die IME-Insets den Listener zuverlässig und
        // die Tastatur schiebt den unteren Bereich (letzte Antwort) nach oben.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Edge-to-Edge (targetSdk 36): Inhalte unter die Systemleisten abfangen
        applyWindowInsets()

        // Benachrichtigungs-Kanal für „Antwort fertig“ anlegen (idempotent).
        NotificationHelper.createChannel(this)

        // Nach dem Kanal-Check: Benachrichtigungs-Berechtigung auf Android 13+
        // beim Start proaktiv anfragen, falls der Toggle aktiv ist, die Runtime-
        // Permission aber fehlt (sonst wird die Benachrichtigung still geschluckt).
        maybeRequestNotificationPermission()

        // Sprachausgabe-Engine initialisieren (Ownership der System-TTS-Quelle).
        speechOutput = SpeechOutput(this)
        // Aufnahme-Feintuning (Tail/Stille/Gain) aktuell aus den Einstellungen übernehmen.
        // Wird vor jedem Start erneut gesetzt, damit manuelle Änderungen LIVE greifen.
        if (audioSource is MicrophoneSource) {
            (audioSource as MicrophoneSource).params = config.recordingParams()
        }
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        statusModel = findViewById(R.id.statusModel)
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        // Deterministische Fokussperre: Die Chat-ScrollView darf keinen Fokus
        // annehmen oder halten. Beim Wechsel in den Tastaturmodus (recordButton auf
        // GONE) fiel der Fokus sonst auf die ScrollView (ScrollView ist per Standard
        // focusable=true) und die IME wurde an sie serviert statt ans Eingabefeld
        // (mServedView=ScrollView, mServedInputConnection=null) → getippte Zeichen
        // kamen erst nach einem zweiten Tap an. Die XML-Attribute allein genügten
        // emulatorisch nicht, daher zusätzlich zur Laufzeit erzwingen.
        chatScroll.isFocusable = false
        chatScroll.isFocusableInTouchMode = false
        chatScroll.clearFocus()
        recordButton = findViewById(R.id.recordButton)
        // Hintergrund-Farbwechsel (Pressed vs. Losgelassen) übernimmt jetzt rein
        // das State-List-Drawable record_button_bg.xml (Selector auf isPressed).
        // Ein backgroundTintList aus Code wird bei Material3-Themes von der
        // MaterialShapeDrawable-Logik des MaterialButton überschrieben und
        // verpuffte deshalb (Button zeigte immer dieselbe Farbe).
        inputRow = findViewById(R.id.inputRow)
        inputField = findViewById(R.id.inputField)
        keyboardToggle = findViewById(R.id.keyboardToggle)
        botSpinner = findViewById(R.id.botSpinner)

        restoreSessionState()

        // Modell-Anzeige und Bot-Auswahl beim Start initialisieren. Bei einem
        // gespeicherten aktiven Bot wird auch dessen Modell angezeigt.
        rebuildBotSpinner()

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener { showSessionsDialog() }

        sendButton = findViewById(R.id.sendButton)
        sendButton.setOnClickListener {
            // Während einer Streaming-Antwort bricht der (umgenutzte) Senden-Button
            // die Antwort ab statt eine neue Nachricht zu senden.
            if (isStreaming()) cancelStream() else sendKeyboardInput()
        }
        pasteButton = findViewById(R.id.pasteButton)
        pasteButton.setOnClickListener { pasteFromClipboard() }
        keyboardToggle.setOnClickListener {
            val show = inputRow.visibility != View.VISIBLE
            if (show) openKeyboardInput() else closeKeyboardInput()
        }

        // Versionsnummer aus der PackageInfo in die Header-Zeile schreiben
        val versionText = findViewById<TextView>(R.id.appVersion)
        // Leerzeichen explizit im Code: Das String-Ressourcen-Compiler trimmt ein
        // nachgestelltes Leerzeichen in "Version " weg -> würde "Version1.21" zeigen.
        versionText.text = getString(R.string.app_version_prefix) + " " + versionLabel()

        setupRecordButton()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Bugfix 1.0.1: Einmaliger Hinweis, wenn gespeicherte Zugangsdaten nicht
        // mehr entschlüsselbar sind (Keystore-Key weg, z. B. nach Restore). Sonst
        // wirkt die App wie „nicht konfiguriert“, obwohl ein Backup existiert.
        if (!secretWarningShown && config.detectUnreadableSecrets()) {
            secretWarningShown = true
            addSystemNotice(getString(R.string.keys_unreadable_notice))
        }

        // Geteilten Text von der Share-Schleuse („Senden mit/Teilen mit") in das
        // Eingabefeld übernehmen, falls die App kalt über einen Share-Intent
        // gestartet wurde (Rest: kommt über onNewIntent bei laufender App).
        handleSharedText()

        // Startmodus aus den Einstellungen anwenden: Sprach-Modus (Default,
        // recordButton) oder Tastatur-Modus (Eingabe-Zeile ohne automatisch
        // geöffnete System-Tastatur). Nur beim Kaltstart (onCreate) — nicht bei
        // onResume — damit der Modus beim Zurückkehren aus den Einstellungen
        // unverändert bleibt.
        if (!config.startInputMode) showKeyboardRowAtStart()

        // Fokus-Wächter: Neu eingefügte/neu aufgebaute Nachrichten-TextViews sind
        // selectable und damit focusableInTouchMode; Android vergibt den Fokus dann
        // von selbst an sie (ohne Touch), die IME bleibt offen, tippt aber ins Leere.
        // Fokuswechsel auf eine Bubble OHNE vorherigen Touch darauf werden daher ans
        // inputField zurückgegeben. Echte Touches (Long-Press-Auswahl) bleiben unberührt.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && newFocus.id == R.id.messageText &&
                ::inputRow.isInitialized && inputRow.visibility == View.VISIBLE &&
                SystemClock.uptimeMillis() - lastBubbleTouchTime > 1500
            ) {
                inputField.post { inputField.requestFocus() }
            }
        }
    }

    /** Empfang eines neuen Intents bei laufender App (singleTop): Übernahme des
     *  geteilten Textes aus der Share-Schleuse als erneute Nutzereingabe. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText()
    }

    /** Wendet System- und IME-Insets (Tastaturhöhe) auf den Layout-Inhalt an,
     *  damit nichts unter der Status-/Navigationsleiste oder der Tastatur liegt
     *  (Edge-to-Edge bei targetSdk 36). Das Padding wird JEDES Mal frisch
     *  gesetzt und niemals akkumuliert, sonst wandert der Inhalt bei jedem
     *  Tastatur-Toggle nach unten. */
    private fun applyWindowInsets() {
        val content = findViewById<android.view.View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            // Tastaturhöhe: reicht die System-Navigationsleiste nicht über die
            // Tastatur hinaus, gilt die größere der beiden nach unten.
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(sysBars.bottom, ime.bottom)
            v.setPadding(sysBars.left, sysBars.top, sysBars.right, bottom)
            // Tastatur öffnet: die neueste Nachricht wieder in den sichtbaren
            // Bereich holen, statt sie hinter der Tastatur zu belassen.
            if (ime.bottom > 0) {
                content.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
            insets
        }
    }

    override fun onPause() {
        // Läuft eine Aufnahme, beim Verlassen der App sauber beenden: Das Mikrofon
        // darf nicht im Hintergrund weiter belegt bleiben (Datenschutz/Akku).
        if (recording) stopRecording()
        // Beim Verlassen der App den Stand garantiert auf die Platte bringen (der Prozess
        // kann im Hintergrund jederzeit beendet werden). Asynchron: Die Serialisierung
        // läuft im Hintergrund-Thread — ein synchroner Save hätte bei großen Beständen
        // den Main-Thread beim Screen-Off blockiert (ANR → Prozess-Kill durch das System).
        SessionStore.saveAsync(this, sessions)
        super.onPause()
        appInForeground = false
        // Polling im Hintergrund stoppen (keine unnötigen Gateway-Requests).
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onResume() {
        super.onResume()
        appInForeground = true
        // Update-Prüfung (Auto-Check max. alle 24 h, Pflicht-Updates, laufende Downloads).
        UpdateManager.onAppResume(this)
        // Polling (wieder) starten; der erste Tick nach dem Intervall, danach
        // periodisch. maybePollSync prüft Vordergrund/Sync-Überlappung/Stream.
        pollHandler.removeCallbacks(pollRunnable)
        // Beim Zurückkehren in die App zügig abgleichen (statt bis zu 20 s zu warten);
        // der Tick plant sich danach im üblichen Intervall selbst neu ein.
        pollHandler.postDelayed(pollRunnable, RESUME_SYNC_DELAY_MS)
        // Akzentfarben-Änderung aus den Einstellungen übernehmen: Activity einmalig
        // neu erstellen, damit das neue Overlay-Theme (Buttons, Logo, Blasen) greift.
        if (config.accentColorName != appliedAccent) {
            appliedAccent = config.accentColorName
            recreate()
            return
        }
        // Chat-Hintergrund (Standard-Einfärbung / Verlauf / Bild) aus den
        // Einstellungen übernehmen.
        applyChatBackground()
        // Eigene Avatar-Bilder aus den Einstellungen auf bestehende Nachrichten anwenden.
        // applyMessageAvatar läuft nur beim Erzeugen einer Nachricht — ohne diesen
        // Refresch blieben nach der Rückkehr aus den Einstellungen die Standard-Avatare
        // auf allen bereits vorhandenen Nachrichten sichtbar.
        refreshMessageAvatars()
        // Anzeigenamen aus „Avatare & Name“ auf bestehende Nachrichten anwenden.
        refreshMessageLabels()
        // Bot-Liste/Active-Auswahl frisch aus den Einstellungen übernehmen
        // (falls dort Bots geändert, hinzugefügt oder gelöscht wurden).
        if (::botSpinner.isInitialized) rebuildBotSpinner()
        // Läuft gerade ein Download, diesen NICHT durch ein erneutes onResume
        // stören (z.B. wenn der Mikrofon-Berechtigungsdialog schließt und den
        // Fokus zurückgibt): weder den Status-Text überschreiben noch einen
        // zweiten Download starten. Der laufende Thread meldet sich selbst.
        if (ModelManager.isDownloading()) return
        if (ModelManager.isInstalled(this, activeSttLanguage)) {
            // Setzt READY bzw. MODEL_LOADING selbst (Aufbau läuft im Hintergrund).
            ensureRecognizer()
            maybeCheckModelUpdate()
        } else {
            setState(State.MODEL_MISSING)
            // statusText zeigt bereits den model_missing-String; die
            // Konversation vorn nicht zusätzlich belegen.
            downloadModelInBackground()
        }
    }

    private fun downloadModelInBackground() {
        var phase = ModelManager.Phase.DOWNLOADING
        thread {
            val progress = { p: Float ->
                runOnUiThread {
                    val label = if (phase == ModelManager.Phase.EXTRACTING) {
                        getString(R.string.model_extracting)
                    } else {
                        getString(R.string.model_downloading)
                    }
                    statusText.text = "$label ${(p * 100).toInt()} %"
                }
            }
            val onPhase = { ph: ModelManager.Phase ->
                phase = ph
                runOnUiThread {
                    statusText.text = if (ph == ModelManager.Phase.EXTRACTING) {
                        getString(R.string.model_extracting)
                    } else {
                        getString(R.string.model_downloading)
                    }
                }
            }
            try {
                // Beide Sprachen werden parallel installiert (Wechsel ohne
                // Download): zuerst die aktive, danach jede fehlende andere.
                // Nur falls wirklich alles sauber heruntergeladen wurde, auf
                // READY schalten.
                var allInstalled = true
                val langs = ModelManager.supportedLanguages()
                    .sortedBy { if (it == activeSttLanguage) 0 else 1 }
                for (lang in langs) {
                    if (ModelManager.isInstalled(this, lang)) continue
                    val installed = ModelManager.download(
                        this,
                        lang,
                        onProgress = progress,
                        onPhase = onPhase
                    )
                    if (!installed) allInstalled = false
                }
                if (allInstalled) runOnUiThread {
                    // Aufbau im Hintergrund; Status (LOADING → READY) setzt ensureRecognizer.
                    ensureRecognizer()
                }
            } catch (e: Exception) {
                android.util.Log.e("HermiVoice", "Modell-Download fehlgeschlagen", e)
                runOnUiThread {
                    setState(State.ERROR)
                    addMessage(isUser = false, label = getString(R.string.app_name), text = getString(R.string.error_model_download_failed, e.message ?: "?"))
                }
            }
        }
    }

    /**
     * Startet den automatischen Versionsabgleich, sofern das wöchentliche
     * Prüfzeitfenster abgelaufen ist. Läuft asynchron und blockiert den
     * App-Start nicht. Der Check-Zeitstempel wird NUR bei Erfolg gesetzt —
     * ein fehlgeschlagener Check (offline etc.) verschiebt das Fenster nicht.
     */
    private fun maybeCheckModelUpdate() {
        if (!config.shouldCheckModelUpdate()) return
        thread {
            try {
                val latest = ModelManager.latestModel(activeSttLanguage)
                // Nur bei erfolgreichem Abruf: Zeitfenster neu starten.
                config.lastModelCheckMillis = System.currentTimeMillis()
                if (latest == null) return@thread
                val installed = ModelManager.installedVersion(this, activeSttLanguage)
                if (ModelManager.isNewer(latest, installed)) {
                    runOnUiThread { showModelUpdateSnackbar(latest) }
                }
            } catch (e: Exception) {
                // Kein Netz / API-Fehler: still überspringen (kein Zeitstempel).
                android.util.Log.i("HermiVoice", "Modell-Update-Check übersprungen", e)
            }
        }
    }

    /** Nicht-modaler Hinweis: Snackbar mit Aktions-Button „Aktualisieren“. */
    private fun showModelUpdateSnackbar(latest: ModelManager.ModelInfo) {
        Snackbar.make(
            findViewById(android.R.id.content),
            getString(R.string.model_update_available, latest.versionDate),
            Snackbar.LENGTH_LONG
        ).setAction(R.string.model_update_action) {
            runModelUpdate(latest)
        }.show()
    }

    /** Lädt die neue Modellversion und aktiviert sie. Läuft asynchron; ein
     *  paralleler Download wird durch die Reentranz-Sperre im ModelManager
     *  abgefangen (zweiter Aufruf liefert false → hier nichts überschreiben). */
    private fun runModelUpdate(latest: ModelManager.ModelInfo) {
        if (ModelManager.isDownloading()) return
        var phase = ModelManager.Phase.DOWNLOADING
        thread {
            try {
                val installed = ModelManager.downloadVersion(
                    this,
                    activeSttLanguage,
                    latest.name,
                    latest.downloadUrl,
                    onProgress = { p ->
                        runOnUiThread {
                            val label = if (phase == ModelManager.Phase.EXTRACTING) {
                                getString(R.string.model_extracting)
                            } else {
                                getString(R.string.model_downloading)
                            }
                            statusText.text = "$label ${(p * 100).toInt()} %"
                        }
                    },
                    onPhase = { ph ->
                        phase = ph
                        runOnUiThread {
                            statusText.text = if (ph == ModelManager.Phase.EXTRACTING) {
                                getString(R.string.model_extracting)
                            } else {
                                getString(R.string.model_downloading)
                            }
                        }
                    }
                )
                if (installed) runOnUiThread {
                    // Neues Modell aktivieren: ensureRecognizer gibt den alten Recognizer
                    // unter Lock frei und lädt den neuen im Hintergrund-Thread.
                    ensureRecognizer()
                    Toast.makeText(this, R.string.model_updated, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("HermiVoice", "Modell-Update fehlgeschlagen", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.model_update_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setupRecordButton() {
        recordButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Läuft gerade eine Streaming-Antwort, wird der Aufnahme-Button
                    // zum Stopp umgenutzt: ein Tippen bricht die Antwort ab statt
                    // eine neue Aufnahme zu starten.
                    if (isStreaming()) {
                        cancelStream()
                        true
                    } else {
                        // Gedrückt: Hintergrund hart auf dunkles Grün wechseln.
                        // Kein isPressed/Selector mehr – direkter Drawable-Tausch
                        // (v1.18), damit die Farbe deterministisch folgt.
                        view.setBackgroundResource(R.drawable.record_button_pressed)
                        startRecording()
                        true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Losgelassen: Hintergrund hart auf helles Grün zurück.
                    view.setBackgroundResource(R.drawable.record_button_bg)
                    // Während des Stopp-Taps (Streaming) ist nie eine Aufnahme
                    // aktiv; nur echte Aufnahmen hier sauber beenden.
                    if (!isStreaming()) stopRecording()
                    true
                }
                else -> true
            }
        }
    }

    /** Es läuft gerade eine Streaming-Antwort, deren Abbruch möglich ist. */
    private fun isStreaming(): Boolean = activeStreamCancel != null

    /** Bricht die laufende Streaming-Antwort ab (Flag + Verbindungs-Reset). */
    private fun cancelStream() {
        activeStreamCancel?.cancel()
    }

    /** Blendet den Stopp-Zustand am umgenutzten Aktionsbutton ein/aus.
     *  Sprachmodus: die Aufnahmetaste zeigt "✕ Stopp"; Tastaturmodus: der
     *  Senden-Button zeigt das Stopp-X. Beide brechen bei einem Tippen die
     *  laufende Antwort ab. */
    private fun setStreamingUi(streaming: Boolean) {
        if (streaming) {
            recordButton.text = "✕ ${getString(R.string.answer_stop)}"
            sendButton.setImageResource(R.drawable.ic_stop)
        } else {
            recordButton.text = getString(R.string.hold_to_talk)
            sendButton.setImageResource(R.drawable.ic_send)
        }
    }

    /** Aktive STT-Sprache laut Einstellung (gekoppelt an die UI-Sprache). */
    private val activeSttLanguage: String
        get() = config.activeSttLanguage

    /**
     * Stellt sicher, dass ein Recognizer für die aktive Sprache geladen ist.
     *
     * Bugfix 1.0.1: Das Laden der ONNX-Modelle dauert Größenordnung Sekunden und
     * lief früher auf dem UI-Thread (ANR-Risiko bei onResume/Sprachwechsel). Jetzt
     * läuft es im Hintergrund-Thread; die UI zeigt „Modell wird geladen“. Ist der
     * Recognizer bereits passend geladen, wird sofort (ohne Thread) auf READY gesetzt.
     * [onReady] läuft auf dem UI-Thread, sobald der Recognizer benutzbar ist.
     */
    private fun ensureRecognizer(onReady: (() -> Unit)? = null) {
        val lang = activeSttLanguage
        val installed = ModelManager.installedVersion(this, lang)
        if (recognizer != null && installed == loadedModelVersion && lang == loadedModelLang) {
            if (!recording) setState(State.READY)
            onReady?.let { runOnUiThread(it) }
            return
        }
        // Ein laufender Aufbau deckt diesen Wunsch mit ab (ein zweiter Thread würde
        // nur das gleiche Modell doppelt über die native Bibliothek laden).
        if (recognizerLoading) return
        recognizerLoading = true
        if (!recording) setState(State.MODEL_LOADING)
        thread(name = "hermi-model-load") {
            try {
                val loaded = synchronized(recognizerLock) {
                    // Activity läuft schon ab: nicht mehr in native Objekte starten.
                    if (recognizerReleased || isFinishing || isDestroyed) return@synchronized false
                    // Die installierte Modellversion ODER die aktive Sprache hat sich
                    // geändert (Update über die Einstellungen bzw. Sprachwechsel) —
                    // alten Recognizer freigeben und neu laden, sonst liefe das alte
                    // Modell (und die alte Sprache) im Speicher weiter.
                    try { recognizer?.release() } catch (_: Exception) {}
                    recognizer = null
                    recognizer = SherpaRecognizer(ModelManager.modelDir(this, lang))
                    loadedModelVersion = installed
                    loadedModelLang = lang
                    true
                }
                if (loaded) {
                    android.util.Log.i("HermiVoice", "STT-Modell geladen: $lang (Version $installed)")
                    runOnUiThread {
                        if (!recording) setState(State.READY)
                        onReady?.invoke()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HermiVoice", "STT-Modell laden fehlgeschlagen", e)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        setState(State.ERROR)
                        addMessage(
                            isUser = false,
                            label = getString(R.string.app_name),
                            text = getString(R.string.error_recognition_failed, e.message ?: "?")
                        )
                    }
                }
            } finally {
                recognizerLoading = false
            }
        }
    }

    /** Spiele einen kurzen Vibrationspuls ab, sofern Haptik aktiv ist. Der Puls
     *  am Start (15 ms) ist bewusst kürzer als am Stopp (40 ms), damit der
     *  Push-to-talk-Nutzer die beiden Ereignisse ohne Blick aufs Display auseinanderhält. */
    private fun hapticFeedback(millis: Long) {
        if (!config.hapticFeedback) return
        try {
            // minSdk=26 → VibrationEffect direkt verfügbar, kein Legacy-Fallback.
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // Geräte ohne Vibrator oder Hardware-Fehler sicher ignorieren.
            android.util.Log.d("HermiVoice", "Haptik fehlgeschlagen: ${e.message}")
        }
    }

    private fun startRecording() {
        if (recording) return
        // Berechtigung kann zur Laufzeit entzogen worden sein; ohne Prüfung bliebe
        // AudioRecord uninitialisiert und die UI hinge in LISTENING.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val rec = recognizer
        if (rec == null) {
            // Modell wird gerade im Hintergrund geladen (Bugfix 1.0.1): nicht still
            // ignorieren, sondern kurz informieren.
            if (recognizerLoading) addSystemNotice(getString(R.string.model_loading_notice))
            return
        }
        if (!config.isConfigured()) {
            setState(State.ERROR)
            addMessage(isUser = false, label = getString(R.string.app_name), text = getString(R.string.no_token))
            return
        }

        recording = true
        setState(State.LISTENING)
        recordButton.text = getString(R.string.hold_to_talk)
        // Sprachausgabe stoppen: eine neue Aufnahme bricht eine evtl. noch
        // laufende Wiedergabe der vorherigen Antwort ab.
        if (speechOutput != null) speechOutput!!.stop()
        // Haptik: kurzer Puls als Bestätigung des Aufnahme-Starts.
        hapticFeedback(15)
        // Live-Vorschau-Zeile in den Chat einfügen, die während des Sprechens
        // laufend mit dem Zwischenergebnis aktualisiert wird.
        liveText = addLiveMessage(chatLabel(true))

        val stream = rec.createStream()
        streamRef = stream
        val recRef = rec
        (audioSource as MicrophoneSource).params = config.recordingParams()
        audioSource.gain = config.micGain
        try {
            audioSource.start(
                onSamples = { samples ->
                    // Bugfix 1.0.1: feed()/partialResult() greifen auf native
                    // sherpa-onnx-Objekte zu. Freigegeben werden Recognizer und Stream
                    // unter recognizerLock (Erkennungs-Ende, onDestroy) — deshalb laufen
                    // die nativen Aufrufe ebenfalls unter diesem Lock. Der Guard
                    // verhindert zusätzlich den Zugriff auf einen bereits freigegebenen
                    // Stream (streamRef wird beim release auf null gesetzt).
                    var partial: String? = null
                    synchronized(recognizerLock) {
                        if (!recognizerReleased && streamRef === stream) {
                            try {
                                recRef.feed(stream, samples)
                                if (recording) {
                                    val p = recRef.partialResult(stream)
                                    if (p.isNotBlank()) partial = p
                                }
                            } catch (e: Exception) {
                                // Stream nach Stop kann kurz noch Samples liefern — ignorieren.
                                // JEDE andere Exception loggen, damit stillschweigende Fehler
                                // (z.B. Native/STM-Fehler) nicht unsichtbar bleiben.
                                if (recording) {
                                    android.util.Log.e("HermiVoice", "audio sample fehler", e)
                                }
                            }
                        }
                    }
                    partial?.let { p -> runOnUiThread { liveText?.text = p } }
                },
                onAmplitude = { amp ->
                    // Nur waehrend der AKTIVEN Aufnahme pulsieren lassen. Der Audio-Tail
                    // (Stille-Nachlauf nach Loslassen) liefert weiter onAmplitude-Callbacks;
                    // ohne Guard wuerde er alpha erneut auf ~0.55 druecken und der Button
                    // bliebe nach dem Loslassen dunkel (Bug Punkt 8, Root-Cause bestaetigt).
                    if (recording) {
                        runOnUiThread { recordButton.alpha = 0.55f + amp * 0.45f }
                    }
                }
            )
        } catch (e: Exception) {
            // Mikrofon nicht startbar (belegt, Berechtigung weg, Init-Fehler): UI und
            // native Ressourcen sauber zurücksetzen, statt in LISTENING zu hängen.
            android.util.Log.e("HermiVoice", "Mikrofon-Start fehlgeschlagen", e)
            recording = false
            synchronized(recognizerLock) {
                if (!recognizerReleased) {
                    try { stream.release() } catch (_: Exception) {}
                }
                streamRef = null
            }
            recordButton.alpha = 1f
            removeLiveMessage()
            setState(State.ERROR)
            addMessage(isUser = false, label = getString(R.string.app_name), text = getString(R.string.mic_start_failed, e.message ?: ""))
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        // Haptik: längerer Puls als Bestätigung des Aufnahme-Stopps.
        hapticFeedback(90)
        runOnUiThread {
            recordButton.alpha = 1f
            setState(State.RECOGNIZING)
        }
        val rec = recognizer
        val stream = streamRef
        if (rec == null || stream == null) {
            // Nichts zu erkennen (Recognizer/Stream weg): UI nicht in RECOGNIZING hängen lassen.
            audioSource.stop { }
            runOnUiThread {
                removeLiveMessage()
                setState(State.READY)
            }
            return
        }
        var tDecodedText = ""
        audioSource.stop {
            thread {
                try {
                    // Unter Lock: onDestroy gibt Recognizer/Stream ebenfalls unter diesem
                    // Lock frei. Ist die Activity schon beendet, nicht mehr in native
                    // Objekte greifen (Absturzgefahr).
                    val decoded = synchronized(recognizerLock) {
                        if (recognizerReleased) null
                        else {
                            val t = rec.finalResult(stream)
                            stream.release()
                            streamRef = null
                            t
                        }
                    } ?: return@thread
                    tDecodedText = decoded
                    android.util.Log.i("HermiVoice", "STT ergebnis: '$tDecodedText'")
                    runOnUiThread {
                        // Live-Vorschau entfernen und durch die endgültige Frage ersetzen.
                        removeLiveMessage()
                        if (tDecodedText.isNotBlank()) {
                            maybeAutoResetSession()
                            val bubble = addMessage(
                                isUser = true,
                                label = chatLabel(true),
                                text = tDecodedText,
                                showSeparator = (activeSession.history.lastOrNull()?.role == "assistant")
                            )
                            sendText(tDecodedText, bubble, fromVoice = true)
                        } else {
                            setState(State.READY)
                        }
                    }
                } catch (e: Exception) {
                    synchronized(recognizerLock) {
                        if (!recognizerReleased) {
                            try { stream.release() } catch (_: Exception) {}
                        }
                        streamRef = null
                    }
                    runOnUiThread {
                        removeLiveMessage()
                        setState(State.ERROR)
                        addMessage(isUser = false, label = getString(R.string.app_name), text = getString(R.string.error_recognition_failed, e.message ?: "?"))
                    }
                }
            }
        }
    }

    // Der Stream wird zwischen start und stop über eine Feld-Referenz geteilt.
    @Volatile private var streamRef: com.k2fsa.sherpa.onnx.OnlineStream? = null
    // Schützt Recognizer/Stream gegen gleichzeitiges Freigeben (onDestroy) und Nutzen
    // (Erkennungs-Thread). recognizerReleased = true, sobald onDestroy freigegeben hat.
    private val recognizerLock = Any()
    @Volatile private var recognizerReleased = false

    /** Prüft, ob nach der konfigurierten Pause eine neue Konversation gestartet
     *  werden soll. Läuft VOR dem Rendern der neuen User-Bubble. Reset nur, wenn
     *  die aktuelle Konversation bereits Nachrichten enthält (leere „Neue
     *  Konversation" wird nie umgeschaltet). Bestehende Verläufe bleiben erhalten. */
    private fun maybeAutoResetSession() {
        if (!config.autoNewSession) return
        if (activeSession.history.isEmpty()) return
        val pauseMs = config.autoNewSessionMinutes * 60_000L
        if (System.currentTimeMillis() - activeSession.lastMessageAt >= pauseMs) {
            newSession()
            addSystemNotice(getString(R.string.auto_session_notice))
        }
    }

    private fun sendText(text: String, userBubble: View? = null, fromVoice: Boolean = false) {
        // Bugfix 1.0.1: Defense-in-Depth gegen parallele Streams. Der UI-Button ist
        // bereits geschützt, aber sendText ist auch programmatisch erreichbar (Sprache,
        // Share). Ein zweiter Stream würde activeStreamCancel/activeStreamSessionId
        // überschreiben und die Historie (nicht thread-sichere MutableList) parallel
        // beschreiben — deshalb hier ablehnen statt still zu starten.
        if (isStreaming()) {
            android.util.Log.w("HermiVoice", "sendText abgelehnt: es läuft bereits eine Antwort")
            addSystemNotice(getString(R.string.answer_in_progress_notice))
            return
        }
        // Sende-Konfiguration des aktiven Bots (Standard-Agent → globale Werte)
        // hier im UI-Thread als Schnappschuss bestimmen; der Hintergrund-Thread
        // liest ausschließlich diese snapshot (activeBot ist ein UI-Feld).
        val profile = chatSender.activeSendProfile(activeBot)
        // Cloud-Sender-Ziel: Ist ein OpenAI-kompatibler Anbieter aktiv (API-Key
        // + primäres Modell), geht diese Anfrage an dessen /chat/completions-
        // Endpoint statt ans Hermes-Gateway. Ebenfalls als Schnappschuss für den
        // Hintergrund-Thread (activeCloud ist ein UI-Feld).
        val cloud = if (activeCloud != null && activeCloud!!.isUsable()) activeCloud else null
        // Die Session, zu der DIESE Anfrage gehört, einmalig festhalten. Der Nutzer
        // darf während der Antwort die Session wechseln/neu anlegen; die Antwort
        // wird dann im Hintergrund in DIESER Session fertig (Historie, Gateway-ID,
        // Fehlerpfade) und nur in die Anzeige geschrieben, solange diese Session
        // noch sichtbar ist (siehe isViewing()). Niemals activeSession im Thread lesen.
        val sendSession = activeSession
        // Server-Basis des aktiven Bots an der Session vermerken, damit die
        // spätere Gateway-Löschung gegen das RICHTIGE Gateway läuft (Docker-Bot
        // mit eigener URL/Port besitzt eine eigene Session-Verwaltung).
        val newServerBase = activeBot?.serverBase?.trim().takeIf { !it.isNullOrEmpty() }
        val newViaBot = activeBot != null
        // Wechselt die Session auf einen anderen Server/Bot, gehört die bisherige
        // Gateway-Session-ID zum ALTEN Server. Sie darf nicht gegen den neuen
        // Server abgeglichen werden (Reverse-Sync würde die lokale Session sonst
        // fälschlich als „serverseitig gelöscht“ entfernen). Die neue ID liefert
        // die nächste Antwort.
        if (cloud == null) {
            // Gateway/Bot-Pfad: Die bisherige Gateway-Session-ID gehört zum
            // ALTEN Server/Bot. Wechselt das Ziel, darf sie nicht gegen den
            // neuen Server abgeglichen werden (Reverse-Sync würde die lokale
            // Session sonst fälschlich als „serverseitig gelöscht“ entfernen).
            // Die neue ID liefert die nächste Antwort.
            if (sendSession.gatewaySessionId != null &&
                (sendSession.serverBase != newServerBase || sendSession.viaBot != newViaBot ||
                    sendSession.botId != activeBot?.id)
            ) {
                sendSession.gatewaySessionId = null
            }
            sendSession.serverBase = newServerBase
            sendSession.viaBot = newViaBot
        } else {
            // Cloud-Sender: zustandslos — keine Gateway-Session-ID, kein
            // Server/Profil, kein Reverse-Sync; nur lokale Session-Historie.
            sendSession.gatewaySessionId = null
            sendSession.serverBase = null
            sendSession.viaBot = false
        }
        // Start-Profil bzw. Cloud-Anbieter an der Session vermerken: Die
        // Fortsetzung aus der Historie muss wieder mit DIESEM Bot bzw. DIESEM
        // Cloud-Anbieter laufen (nicht mit dem zuletzt gewählten).
        sendSession.botId = if (cloud == null) activeBot?.id else null
        sendSession.cloudId = cloud?.id
        // Sendeberechtigt: ein aktiver Cloud-Anbieter (eigener Key + Basis-URL)
        // ODER ein aktiver Bot (eigener Key) bzw. die globale Konfiguration
        // (Standard). Beides braucht eine erreichbare Server-/Anbieter-URL.
        val sendAllowed = if (cloud != null) {
            cloud.baseUrl().isNotBlank() && cloud.apiKey.isNotBlank()
        } else {
            config.serverUrl.isNotBlank() && profile.apiKey.isNotBlank()
        }
        if (!sendAllowed) {
            setState(State.ERROR)
            addMessage(isUser = false, label = getString(R.string.app_name), text = getString(R.string.no_token))
            return
        }
        val prefix = config.messagePrefix
        val message = if (prefix.isBlank()) text else "$prefix $text"
        // Beim ersten User-Turn der Session den sichtbaren Titel ableiten
        // (danach nicht mehr ändern, damit die Liste stabil bleibt). Ein
        // manuell gesetzter Titel wird dabei nicht überschrieben.
        if (sendSession.history.isEmpty() && !sendSession.manualTitle) {
            sendSession.title = SessionStore.deriveTitle(text)
            persistSessions()
        }
        // User-Turn an die Konversations-Historie anhängen; eine Schnappschuss-
        // Kopie geht in den Hintergrund-Thread (ArrayList ist nicht threadsicher,
        // die UI mutiert die Historie während der Anfrage weiter).
        val userNow = System.currentTimeMillis()
        sendSession.history.add(ChatMessage("user", message, userNow))
        sendSession.lastMessageAt = userNow

        // Lösch-Schaltfläche an die soeben gerenderte User-Bubble binden.
        // Deterministisch: die Bubble-Referenz kommt direkt vom addMessage-
        // Aufrufer (userBubble) und nicht mehr aus einer Suche im Container,
        // die nach einem Auto-Reset die falsche/frische Bubble verfehlen kann.
        // Fallback: letzte, noch nicht verknüpfte User-Bubble im Container.
        val userMessage = sendSession.history.last()
        if (userBubble != null) {
            enableBubbleDelete(userBubble, userMessage)
        } else {
            for (i in chatContainer.childCount - 1 downTo 0) {
                val child = chatContainer.getChildAt(i)
                if (child.tag == null &&
                    (child as? LinearLayout)?.findViewById<TextView>(R.id.messageLabel)
                        ?.text?.toString() == chatLabel(true)
                ) {
                    enableBubbleDelete(child, userMessage)
                    break
                }
            }
        }
        val historySnapshot = ArrayList(sendSession.history)
        // (b) Beim Senden den veralteten [provider/model]-Präfix aus den
        // assistant-Turns strippen. Dahinter bleibt er in der Anzeige-Historie
        // unverändert (keine Migration) — aber das Modell imitiert den Stil der
        // mitgesendeten Alts nur, solange diese ihn noch enthalten.
        val sendSnapshot = historySnapshot.map { m ->
            if (m.role == "assistant") m.role to chatSender.stripModelPrefix(m.content)
            else m.role to m.content
        }
        setState(State.SENDING)
        // Antwort-Platzhalter: zeigt zunächst den 30s-Countdown der
        // First-Token-Phase („Modell arbeitet… Ns“). Läuft er ohne erstes
        // Token ab, bleibt statisch „Modell arbeitet…“ stehen (statt der
        // früheren drei Punkte); kommt das erste Token, übernimmt der echte
        // Stream. Die Frist ist dieselbe Konstante, die der Sender für sein
        // Netzwerk-Timeout nutzt (Single Source of Truth).
        reasoningExpanded = false
        val placeholderRoot = addMessage(
            isUser = false,
            label = chatLabel(false),
            text = "",
            countdownSeconds = config.firstTokenTimeoutSec
        )
        // true, solange die Session dieser Anfrage im Chat sichtbar ist UND der
        // Platzhalter noch im Container hängt (nach einem Session-Wechsel wird der
        // Container geleert). NUR auf dem UI-Thread aufrufen.
        fun isViewing(): Boolean =
            activeSession === sendSession && placeholderRoot.parent === chatContainer
        // Läuft auf dem UI-Thread (sendText wird vom UI-Thread aufgerufen).
        // onTick/onFinish aktualisieren die Countdown-Zeile live; cancel()
        // erfolgt beim ersten Token und nach Schleifenende (dort wird die
        // Zählanzeige zusätzlich aus-/jetzt ausgeblendet).
        val firstTokenCountdown = object : CountDownTimer(
            (config.firstTokenTimeoutSec * 1000L), 1000L
        ) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000).toInt()
                placeholderRoot?.findViewById<TextView>(R.id.messageCountdown)
                    ?.setText(getString(R.string.model_working_countdown, secs))
            }
            override fun onFinish() {
                placeholderRoot?.findViewById<TextView>(R.id.messageCountdown)
                    ?.setText(getString(R.string.model_working))
            }
        }
        firstTokenCountdown.start()
        // Foreground-Service: hält den Prozess während des Streams am Leben, damit
        // die Verbindung beim Wechsel in den Hintergrund nicht abreißt.
        StreamKeepAliveService.cancelHandler = { runOnUiThread { cancelStream() } }
        StreamKeepAliveService.start(this)
        thread {
            // Abbruch-Token für den Stopp-Button: wird in alle sendMessageStream-
            // Aufrufe gereicht; cancel() (siehe setStreamingUi/Stopp-Button) beendet
            // die laufende Antwort sofort. Ein Nutzer-Abbruch ist KEIN Fehler und
            // löst weder einen Fallback noch einen Fehlerhinweis aus.
            val cancelToken = StreamCancellation()
            activeStreamCancel = cancelToken
            activeStreamSessionId = sendSession.id
            runOnUiThread { setStreamingUi(streaming = true) }

            // Modell-Auswahl (erste Wahl, falls gesetzt, sonst Standard). Bei aktiver
            // Cloud läuft die Anfrage an den OpenAI-kompatiblen Anbieter mit dessen
            // primärem/ Fallback-Modell; sonst gilt das Hermes-Profil/Standard.
            val primary = if (cloud != null) cloud.primaryPair() else profile.primary
            val fallback = if (cloud != null) cloud.fallbackPair() else profile.fallback
            // Session-Schwellenwert: nach N aufeinanderfolgenden Primär-Fehlschlägen
            // direkt ans Fallback-Modell, um den ~30-s-Wartezyklus zu vermeiden.
            val threshold = config.primaryFailThreshold
            val streak = primaryFailStreaks[sendSession.id] ?: 0
            val skipPrimary = streak >= threshold && fallback != null && fallback != primary
            // Nur ein Fallback-Versuch je Nachricht (Q1) — erst wenn der
            // erste Request fehlschlägt, und nur wenn Fallback ≠ primär ist.
            // Bei erreichter Schwelle wird das Primärmodell übersprungen.
            val attempts: List<Pair<String, String>?> = buildList {
                if (!skipPrimary) add(primary)
                if (fallback != null && fallback != primary) add(fallback)
            }
            var lastError: Exception? = null
            var answer: String = ""
            var reasoning: String = ""
            // Modell, das die Antwort tatsächlich geliefert hat (primär ODER
            // nach Fallback). Wird für den Q2-Präfix genutzt, damit bei einem
            // automatischen Fallback nicht fälschlich das Primär-Modell steht.
            var usedPair: Pair<String, String>? = null
            // Mitten im Stream bereits angekommene Teilantwort: wird behalten,
            // es findet dann KEIN Fallback-Versuch mehr statt.
            var partialReceived = false
            // Vom Gateway in der Antwort gelieferte, LÖSCHBARE Session-ID
            // (Header X-Hermes-Session-Id). Wird im onSessionId-Callback des
            // Senders gesetzt und nach dem Stream am Ende in die aktive Session
            // übernommen + persistiert. Bleibt null, wenn das Gateway keine ID
            // liefert → dann kann lokal nichts an der Session gelöscht werden.
            var gatewayId: String? = null
            // Vom Gateway als `hermes.failed=true` signalisierter fehlgeschlagener
            // Turn (Modell nicht verfügbar): Die bereits gestreamte englische
            // Server-Notiz ist KEINE echte Antwort, sondern wird nach dem
            // Senden durch eine deutsche Meldung mit „Erneut senden“-Button
            // ersetzt.
            var failedTurn = false
            // Rein von Seiten der UI-Warte: eine vom Nutzer abgebrochene Antwort
            // wird sauber verworfen und NICHT als Fehler/Teilantwort behandelt.
            var userCancelled = false
            val renderHandler = android.os.Handler(android.os.Looper.getMainLooper())
            for (pair in attempts) {
                val acc = StringBuilder()
                val reasoningAcc = StringBuilder()
                // true, sobald mindestens EIN Token (Inhalt ODER Denk-Token)
                // angekommen ist. Blockiert den Fallback NUR bei echtem Text;
                // reine Reasoning-Token zusammen mit einer HermesEmptyResponse-
                // Exception (tool_calls-Ende ohne Text) lösen weiterhin den
                // Fallback aus.
                var receivedAny = false
                // Streaming-Rendering gedrosselt: Markdown-Parsing + Layout laufen höchstens
                // alle ~80 ms statt bei jedem Token (lange Antworten bleiben flüssig).
                // Der Endzustand wird nach dem Stream ohnehin vollständig gerendert.
                renderHandler.removeCallbacksAndMessages(null)
                val renderPending = java.util.concurrent.atomic.AtomicBoolean(false)
                var renderWithDone = false
                val renderRunnable = Runnable {
                    renderPending.set(false)
                    if (isViewing()) {
                        val text: String
                        val reason: String
                        synchronized(acc) { text = acc.toString(); reason = reasoningAcc.toString() }
                        updateLastHermesMessage(text, reasoning = reason, reasoningDone = renderWithDone && reason.isNotEmpty(), streaming = true)
                    }
                }
                val scheduleRender = { done: Boolean ->
                    renderWithDone = done
                    if (renderPending.compareAndSet(false, true)) renderHandler.postDelayed(renderRunnable, 80L)
                }
                // Fallback-Wechsel: Bei der zweiten (Fallback-)Iteration zeigt
                // die Countdown-Zeile statt des weiterlaufenden Countdowns
                // „Modell wird gewechselt…“, weil der primäre Request gescheitert
                // ist. Den Countdown stoppen, damit onTick/onFinish nichts mehr
                // überschreibt; die Zeile bleibt bis zum ersten Fallback-Token.
                if (pair != primary) {
                    firstTokenCountdown.cancel()
                    runOnUiThread {
                        placeholderRoot?.findViewById<TextView>(R.id.messageCountdown)?.apply {
                            this.text = getString(R.string.model_switching)
                            visibility = View.VISIBLE
                        }
                    }
                }
                try {
                    // Streaming: Antwort wächst tokenweise; onDelta/onReasoning
                    // laufen auf diesem Hintergrund-Thread, UI-Updates werden
                    // hier per runOnUiThread gehoben. Die Callbacks sind für
                    // Cloud- (sendCloudStream) und Gateway-Sender (sendMessageStream)
                    // identisch und werden je Ziel gebunden.
                    val streamDelta: (String) -> Unit = { d ->
                        receivedAny = true
                        synchronized(acc) { acc.append(d) }
                        firstTokenCountdown.cancel()
                        runOnUiThread {
                            placeholderRoot?.findViewById<View>(R.id.messageCountdown)?.visibility = View.GONE
                            scheduleRender(true)
                        }
                    }
                    val streamReasoning: (String) -> Unit = { r ->
                        receivedAny = true
                        synchronized(acc) { reasoningAcc.append(r) }
                        firstTokenCountdown.cancel()
                        runOnUiThread {
                            placeholderRoot?.findViewById<View>(R.id.messageCountdown)?.visibility = View.GONE
                            scheduleRender(false)
                        }
                    }
                    answer = if (cloud != null) {
                        // Cloud-Kanal: zustandsloser Request an den OpenAI-kompatiblen
                        // Anbieter des aktiven Cloud-Ziels. pair.second = das Modell
                        // des aktuellen Versuchs (primär bzw. Fallback).
                        HermesApiSender.sendCloudStream(
                            baseUrl = cloud.baseUrl(),
                            apiKey = cloud.apiKey,
                            history = sendSnapshot,
                            model = pair?.second ?: "",
                            provider = cloud.provider,
                            enabledToolSlugs = cloud.tools,
                            cancellationToken = cancelToken,
                            firstTokenTimeoutMs = config.firstTokenTimeoutSec * 1000L,
                            streamTimeoutMs = config.streamTimeoutMin * 60_000L,
                            onDelta = streamDelta,
                            onReasoning = streamReasoning
                        )
                    } else {
                        HermesApiSender.sendMessageStream(
                            profile.serverUrl, profile.apiKey, sendSnapshot, modelPair = pair,
                        // Stabiler Session-Key pro Chat: die unveränderliche
                        // App-UUID der Session. Bleibt über Umbenennen und
                        // Favorisieren stabil (wird nie aus dem Titel abgeleitet)
                        // und dient dem Server als Memory-Scope/Konversationsname.
                        sessionKey = sendSession.id,
                        cancellationToken = cancelToken,
                        firstTokenTimeoutMs = config.firstTokenTimeoutSec * 1000L,
                        streamTimeoutMs = config.streamTimeoutMin * 60_000L,
                        onDelta = streamDelta,
                        onReasoning = streamReasoning,
                        // LÖSCHBARE Gateway-Session-ID aus dem Antwort-Header
                        // übernehmen (läuft im Sender auf dem Hintergrund-Thread,
                        // gatewayId ist eine Thread-lokale Variable des Sende-
                        // Runnables und wird am Ende in die aktive Session gespeichert).
                        onSessionId = { id -> if (id != null) gatewayId = id }
                        )
                    }
                    reasoning = reasoningAcc.toString()
                    usedPair = pair
                    lastError = null
                    break
                } catch (e: HermesApiSender.HermesFailedTurnException) {
                    lastError = e
                    // Failed Turn (Modell nicht verfügbar): Das Gateway hat die
                    // englische Notiz als content gestreamt (receivedAny=true),
                    // aber es ist KEINE echte Antwort. Kein weiterer Fallback —
                    // das Gateway hat selbst primär+Fallback versucht. Die UI
                    // ersetzt die Notiz nach dem Senden durch die deutsche Meldung.
                    failedTurn = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    // Nutzer-Abbruch: sofort raus — weder Teilantwort behalten
                    // (wird sauber verworfen) noch ein Fallback versuchen.
                    if (cancelToken.cancelled) {
                        userCancelled = true
                        break
                    }
                    if (receivedAny && e !is HermesApiSender.HermesEmptyResponseException) {
                        // Es ist schon sichtbarer Text da: Teilantwort behalten
                        // und KEINEN Fallback-Versuch mehr (sonst würde der
                        // bereits angezeigte Text verworfen).
                        // Ausnahme: HermesEmptyResponseException — da kam nur
                        // Reasoning und/oder finish_reason=tool_calls ohne Text;
                        // das ist KEINE Antwort, also weiter im Fallback.
                        answer = acc.toString()
                        reasoning = reasoningAcc.toString()
                        partialReceived = true
                        break
                    }
                    // Noch gar nichts angekommen → nächster Versuch (Fallback).
                }
            }
            // Session-Schwellenwert pflegen: Zählt aufeinanderfolgende
            // Primär-Fehlschläge, bei denen das Fallback-Modell geantwortet hat.
            // Eine erfolgreiche Primär-Antwort setzt zurück; ein übersprungenes
            // Primärmodell (skipPrimary) lässt den Zähler unverändert.
            val used = usedPair
            if (used != null) {
                if (used == primary) {
                    primaryFailStreaks[sendSession.id] = 0
                } else if (!skipPrimary) {
                    primaryFailStreaks[sendSession.id] = (primaryFailStreaks[sendSession.id] ?: 0) + 1
                }
            }
            // Streaming beendet: Token freigeben und Stopp-Anzeige zurückbauen,
            // bevor die Status-/Historie-Logik greift.
            activeStreamCancel = null
            activeStreamSessionId = null
            StreamKeepAliveService.stop(this)
            runOnUiThread {
                // Countdown beenden, falls die Schleife ohne Token endete
                // (Fehler/Fallback) — er darf keine spätere Anzeige überschreiben.
                firstTokenCountdown.cancel()
                placeholderRoot?.findViewById<View>(R.id.messageCountdown)
                    ?.visibility = View.GONE
                // Ausstehendes gedrosseltes Rendering verwerfen, damit es keine
                // Endzustände (Fehlerhinweis, Abbruch) überschreibt.
                renderHandler.removeCallbacksAndMessages(null)
                setStreamingUi(streaming = false)
                when {
                    userCancelled -> {
                        // Sauberer Nutzer-Abbruch: die laufende Antwort (inkl.
                        // bereits angekommenem Teiltext) und den unbeantworteten
                        // User-Turn verwerfen, zurück auf Bereit. Kein Fallback,
                        // kein Fehlerhinweis, keine Historie/Timestamp-Änderung.
                        if (isViewing()) removeLastHermesMessage()
                        removeLastUserTurn(sendSession)
                        setState(State.READY)
                    }
                    failedTurn -> {
                        // Failed Turn (Modell nicht verfügbar): Die vom Gateway
                        // gestreamte englische Notiz ist keine echte Antwort.
                        // Stattdessen eine deutsche Meldung mit „Erneut senden“-
                        // Button zeigen. Kein stiller Verlust: Der User-Turn wird
                        // entfernt (bleibt nicht als unbeantworteter Kontext), der
                        // Retry-Button sendet die Frage auf Wunsch erneut.
                        setState(State.ERROR)
                        if (isViewing()) updateLastHermesMessage(getString(R.string.failed_turn_notice))
                        else Toast.makeText(this, R.string.background_send_failed, Toast.LENGTH_LONG).show()
                        removeLastUserTurn(sendSession)
                        placeholderRoot?.let { root ->
                            root.findViewById<TextView>(R.id.messageRetryButton)?.apply {
                                visibility = View.VISIBLE
                                setOnClickListener {
                                    removeLastHermesMessage()
                                    sendText(text, userBubble)
                                }
                            }
                        }
                    }
                    lastError == null -> {
                        setState(State.SENT)
                        if (answer.isBlank()) {
                            if (isViewing()) removeLastHermesMessage()
                            // Leere Antwort: User-Turn ohne Antwort wieder verwerfen
                            // (bleibt nicht als unbeantworteter Kontext in der Historie).
                            removeLastUserTurn(sendSession)
                        } else {
                            // (b) Kein [provider/model]-Präfix mehr auf neue Antworten.
                            // Das real genutzte Modell (primär ODER Fallback, Q1/Q2)
                            // wird stattdessen oben rechts neben dem Bearbeitungsstatus
                            // angezeigt (c).
                            val answerNow = System.currentTimeMillis()
                            // Zeitstempel auf den tatsächlichen Antwort-Zeitpunkt
                            // setzen, nicht die Erstellzeit des "…"-Platzhalters.
                            if (isViewing()) updateLastHermesMessage(answer, answerNow, reasoning.ifBlank { null }, reasoningDone = true)
                            val assistantMessage = ChatMessage("assistant", answer, answerNow)
                            sendSession.history.add(assistantMessage)
                            placeholderRoot?.let { enableBubbleDelete(it, assistantMessage) }
                            // Fallback-Notiz (Variante A: am Ende der Schleife
                            // geprüft): Hat nicht das primäre Modell geantwortet
                            // (usedPair != primary), war ein Fallback nötig →
                            // gedimmte Hinweiszeile in der Bubble einblenden.
                            val usedPairNow = usedPair
                            if (usedPairNow != null && usedPairNow != primary) {
                                val fallbackNote = getString(
                                    R.string.fallback_notice,
                                    usedPairNow.first,
                                    usedPairNow.second
                                )
                                placeholderRoot?.findViewById<TextView>(R.id.messageFallbackNote)?.let { noteView ->
                                    noteView.text = fallbackNote
                                    noteView.visibility = View.VISIBLE
                                }
                            }
                            sendSession.lastMessageAt = answerNow
                            // Sprachausgabe: nur bei Mikrofon-Eingabe und aktivem
                            // TTS-Toggle wird die Antwort vorgelesen.
                            if (fromVoice && config.ttsReadAnswer && isViewing()) {
                                speechOutput?.speak(answer)
                            }
                            // Benachrichtigung bei fertiger Antwort: nur, wenn die
                            // App gerade nicht sichtbar ist (Nutzer ist weg) und der
                            // Toggle aktiv ist. Läuft auf dem Sende-Thread, das
                            // Posten selbst ist thread-sicher.
                            // Auch bei laufender App, wenn die Antwort zu einer gerade
                            // nicht sichtbaren Session gehört (Hintergrund-Antwort).
                            if ((!appInForeground || !isViewing()) && config.notifyOnAnswer) {
                                NotificationHelper.notifyAnswer(this, answer)
                            }
                        }
                    }
                    partialReceived -> {
                        // Verbindungsabbruch nach Teilempfang: Teilantwort stehen
                        // lassen, nur einen Fehlerhinweis anhängen (kein Fallback).
                        setState(State.ERROR)
                        val answerNow = System.currentTimeMillis()
                        val hint = getString(R.string.partial_connection_lost, lastError.message)
                        if (isViewing()) updateLastHermesMessage(answer + hint, answerNow, reasoning.ifBlank { null }, reasoningDone = true)
                        // Teilantwort als Assistant-Turn sichern, damit die Historie
                        // konsistent mit dem Angezeigten bleibt.
                        val assistantMessage = ChatMessage("assistant", answer, answerNow)
                        sendSession.history.add(assistantMessage)
                        placeholderRoot?.let { enableBubbleDelete(it, assistantMessage) }
                        sendSession.lastMessageAt = answerNow
                    }
                    else -> {
                        setState(State.ERROR)
                        // Kein stiller Verlust: Fehler bleibt als Nachricht stehen.
                        if (isViewing()) updateLastHermesMessage(getString(R.string.send_failed_with_reason, lastError.message))
                        else Toast.makeText(this, R.string.background_send_failed, Toast.LENGTH_LONG).show()
                        removeLastUserTurn(sendSession)
                        // Retry-Button in der Fehlerbubble: entfernt die Fehlerbubble
                        // und sendet die zuletzt fehlgeschlagene User-Nachricht erneut.
                        // Der neue sendText-Aufruf erzeugt den frischen "…"-Platzhalter
                        // und re-bindet die Lösch-Funktion an die vorhandene User-Bubble
                        // (keine doppelte "Du"-Blase).
                        placeholderRoot?.let { root ->
                            root.findViewById<TextView>(R.id.messageRetryButton)?.apply {
                                visibility = View.VISIBLE
                                setOnClickListener {
                                    removeLastHermesMessage()
                                    sendText(text, userBubble)
                                }
                            }
                        }
                    }
                }
                // LÖSCHBARE Gateway-Session-ID der letzten Antwort in der
                // aktiven Session speichern, damit beim Löschen ein DELETE
                // ans Gateway gehen kann. Nur setzen, wenn das Gateway eine ID
                // geliefert hat (frische, nie gesendete Session bleibt null).
                if (gatewayId != null) {
                    sendSession.gatewaySessionId = gatewayId
                    // Session wurde während der Antwort gelöscht: die Server-Session
                    // ist gerade erst entstanden und hätte keine lokale Entsprechung →
                    // sonst würde der Sync sie als "neue" Session zurückholen.
                    if (sessions.none { it === sendSession }) {
                        // gatewayId ist eine im Thread-Closure mutierte lokale var;
                        // für den Smart-Cast hier eine val-Kopie nehmen.
                        val serverSessionId: String = gatewayId!!
                        PendingSyncStore.addDelete(this, serverSessionId, chatSender.effectiveBase(sendSession))
                        Thread { flushPendingGatewayOps() }.start()
                    }
                }
                persistSessions()
                // War der Nutzer zwischenzeitlich in einer anderen Session und ist
                // inzwischen zu dieser zurückgekehrt, hängt der Platzhalter nicht mehr
                // im Container: Verlauf (inkl. der fertigen Antwort) neu aufbauen.
                if (activeSession === sendSession && placeholderRoot.parent !== chatContainer) {
                    chatContainer.removeAllViews()
                    renderHistory(sendSession)
                }
                // Anzeige des real genutzten Modells aktualisieren
                // (nach dem DB-Logik zur lastMessageAt im follow-up).
                if (activeSession === sendSession) updateStatusModel(usedPair)
                // Tastatur-Modus: Fokus wieder auf das Eingabefeld, damit ohne
                // erneutes Antippen weitergeschrieben werden kann (der Senden-
                // Button hatte den Fokus gezogen und die Tastatur geschlossen).
                if (activeSession === sendSession) restoreKeyboardFocus()
            }
        }
    }

    /** Entfernt den letzten noch offenen User-Turn aus der Historie, wenn Hermes
     *  keine Antwort dazu geliefert hat (Fehler/leere Antwort). */
    private fun removeLastUserTurn(session: ChatSession) {
        if (session.history.isNotEmpty() &&
            session.history.last().role == "user"
        ) {
            val removed = session.history.removeAt(session.history.lastIndex)
            // Merken, damit der Sync den ggf. serverseitig gespeicherten Turn nicht zurückholt.
            session.suppressedTurns.add(removed.content)
            while (session.suppressedTurns.size > 20) session.suppressedTurns.removeAt(0)
        }
    }

    // ── Session-Verwaltung ────────────────────────────────────────────────

    /** Lädt die gespeicherten Sessions, stellt die zuletzt aktive wieder her
     *  und rendert deren Verlauf in den Chat. */
    private fun restoreSessionState() {
        sessions.clear()
        sessions.addAll(SessionStore.load(this))
        if (SessionStore.lastLoadFailed) {
            Toast.makeText(this, R.string.sessions_load_failed, Toast.LENGTH_LONG).show()
        }

        val activeId = SessionStore.loadActiveId(this)
        val stored = sessions.firstOrNull { it.id == activeId }
        if (stored != null) {
            activeSession = stored
        } else {
            // Keine (passende) gespeicherte Session → neue eröffnen.
            activeSession = SessionStore.create()
            sessions.add(activeSession)
            persistSessions()
        }
        renderHistory(activeSession)
    }

    /** Schreibt den aktuellen Session-Stand auf die Platte. */
    private fun persistSessions() {
        val activeId = activeSession.id
        // Asynchron: Serialisierung hier (konsistenter Schnappschuss), Verschlüsselung
        // und Dateischreiben im Hintergrund. onPause sichert zusätzlich synchron.
        SessionStore.saveAsync(this, sessions)
        SessionStore.saveActiveId(this, activeId)
    }

    /** Eröffnet eine neue, leere Session und aktiviert sie. Sie wird dem aktuell
     *  gewählten Bot-Profil zugeordnet, damit die Session auch ohne Senden ihrem
     *  Start-Profil folgt. */
    private fun newSession() {
        activeSession = SessionStore.create()
        activeSession.botId = activeBot?.id
        sessions.add(activeSession)
        persistSessions()
        chatContainer.removeAllViews()
        applyInputPreference()
    }

    /** Wechselt die aktive Session und stellt deren Verlauf im Chat wieder her.
     *  Dabei wird auch das Start-Profil der Session wiederhergestellt: Der Chat
     *  läuft wieder mit dem Bot, mit dem er begonnen wurde (Spinner, gespeicherte
     *  Wahl, Modell-Anzeige) — ohne eine neue Session zu eröffnen. */
    private fun switchToSession(id: String) {
        val target = sessions.firstOrNull { it.id == id } ?: return
        if (target.id == activeSession.id) return
        activeSession = target
        val bot = resolveSessionBot(target)
        activeBot = bot
        config.setActiveBotId(bot?.id ?: "")
        reselectActive() // programmatisch → kein activateBot → keine neue Session
        updateModelForActive()
        persistSessions()
        chatContainer.removeAllViews()
        applyInputPreference()
        renderHistory(activeSession)
    }

    /** Löscht eine Session samt Verlauf. Die aktive Session wird durch eine
     *  neue ersetzt, damit immer eine gültige, aktive Konversation existiert.
     *  Existiert für die Session bereits eine Gateway-Session-ID (aus dem
     *  Antwort-Header `X-Hermes-Session-Id`), wird zusätzlich best-effort ein
     *  DELETE an den API-Server gesendet - asynchron, damit die UI nicht
     *  blockiert. Ein Fehlschlag ist unkritisch: Die lokale Session ist in
     *  jedem Fall schon entfernt, nur eine verwaiste Server-Session bliebe. */
    private fun deleteSession(id: String) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        // Vor dem Entfernen merken: nur bei vorhandener Gateway-ID ist ein
        // Server-DELETE nötig (frische, nie gesendete Session hat keine). Die
        // Server-Basis der Session bestimmt, gegen welches Gateway gelöscht wird.
        val session = sessions[index]
        val gatewaySessionId = session.gatewaySessionId
        // Läuft gerade die Antwort DIESER Session im Hintergrund, abbrechen — sie
        // hätte keine Session mehr, in die sie schreiben könnte.
        if (activeStreamSessionId == id) cancelStream()
        sessions.removeAt(index)
        if (activeSession.id == id) {
            activeSession = SessionStore.create()
            activeSession.botId = activeBot?.id
            sessions.add(activeSession)
            chatContainer.removeAllViews()
            applyInputPreference()
        }
        persistSessions()

        // Server-Session löschen (falls eine ID vom Gateway bekannt ist). Der
        // Endpoint liegt am Stamm des jeweiligen Servers (…/api/sessions/{id}),
        // nicht unter einem Bot-Prefix. Läuft die Session über einen Docker-Bot
        // mit eigener Basis (Modus B), wird gegen genau diese Basis + den Key des
        // passenden Bots gelöscht; sonst gegen den globalen Gateway + globalen Key.
        // Lokale Absicht gewinnt: Tombstone persistent vormerken, damit der Reverse-Sync
        // die Session nicht zurückholt, solange der Server das Löschen nicht bestätigt
        // hat (Netzfehler, 409 bei aktivem Turn …). Der Abgleich wiederholt das DELETE.
        if (!gatewaySessionId.isNullOrBlank()) {
            PendingSyncStore.addDelete(this, gatewaySessionId, chatSender.effectiveBase(session))
            Thread { flushPendingGatewayOps() }.start()
        }
    }

    /** Serialisiert das Abarbeiten der Warteschlange (Tombstones/Pending-Renames). */
    private val pendingFlushLock = Any()

    /** Sendet alle noch unbestätigten lokalen Absichten (DELETE/PATCH) an den Gateway.
     *  Bestätigt (2xx/404) → aus der Warteschlange entfernt; sonst bleibt der Eintrag
     *  und wird beim nächsten Sync-Tick erneut versucht. MUSS auf einem
     *  Hintergrund-Thread laufen. */
    private fun flushPendingGatewayOps() {
        synchronized(pendingFlushLock) {
            for (d in PendingSyncStore.pendingDeletes(this)) {
                val (url, key) = chatSender.gatewayTarget(gatewayStub(d.serverBase))
                try {
                    if (HermesApiSender.deleteGatewaySession(url, key, d.gatewaySessionId)) {
                        PendingSyncStore.confirmDelete(this, d.gatewaySessionId)
                    }
                } catch (_: Exception) {
                    // Netzfehler: Tombstone bleibt, nächster Versuch beim nächsten Tick.
                }
            }
            for (r in PendingSyncStore.pendingRenames(this)) {
                if (PendingSyncStore.isDeleted(this, r.gatewaySessionId)) continue
                val (url, key) = chatSender.gatewayTarget(gatewayStub(r.serverBase))
                try {
                    if (HermesApiSender.renameGatewaySession(url, key, r.gatewaySessionId, r.title)) {
                        PendingSyncStore.confirmRename(this, r.gatewaySessionId, r.title)
                    }
                } catch (e: HermesApiSender.TitleConflictException) {
                    // Server lehnt den Titel dauerhaft ab (HTTP 400): nicht endlos
                    // wiederholen. Der Eintrag entfällt, der Server-Titel setzt sich
                    // beim nächsten Abgleich durch — Nutzer gezielt informieren.
                    PendingSyncStore.confirmRename(this, r.gatewaySessionId, r.title)
                    runOnUiThread {
                        Toast.makeText(this, R.string.session_rename_conflict, Toast.LENGTH_LONG).show()
                    }
                } catch (_: Exception) {
                    // Netzfehler: Pending-Rename bleibt, lokaler Titel gewinnt weiter.
                }
            }
        }
    }

    /** Minimale Session nur zur Auflösung von Gateway-URL/Key über [ChatSender.gatewayTarget]. */
    private fun gatewayStub(serverBase: String?) =
        ChatSession(id = "", title = "", createdAt = 0L, serverBase = serverBase)

    /** Entfernt Sessions lokal, ohne ein Gateway-DELETE zu senden — für den
     *  Reverse-Sync, wo die Server-Session bereits gelöscht ist (Server gewinnt).
     *  Ist die aktive Session betroffen, wird sie durch eine frische ersetzt,
     *  damit immer eine gültige Konversation offen bleibt. */
    private fun removeLocalSessions(ids: Set<String>) {
        val toRemove = sessions.filter { it.id in ids }
        if (toRemove.isEmpty()) return
        if (toRemove.any { it.id == activeStreamSessionId }) cancelStream()
        sessions.removeAll(toRemove)
        if (toRemove.any { it.id == activeSession.id }) {
            activeSession = SessionStore.create()
            activeSession.botId = activeBot?.id
            sessions.add(activeSession)
            chatContainer.removeAllViews()
            applyInputPreference()
            // Nicht stillschweigend: die geöffnete Konversation wurde auf dem Server gelöscht.
            Toast.makeText(this, R.string.session_deleted_remote, Toast.LENGTH_LONG).show()
        }
        persistSessions()
    }

    /** Reverse-Sync: gleicht beim Öffnen der Listenansicht die lokalen Sessions
     *  gegen den Gateway-Stand ab — Server gewinnt als letzte Source of Truth.
     *  Lokale Sessions, deren Gateway-Session nicht (mehr) existiert, werden
     *  lokal entfernt; serverseitig geänderte Titel werden lokal übernommen.
     *  Asynchron und best-effort: Ist ein Gateway nicht erreichbar, wird der
     *  Abgleich für diese Basis übersprungen, die lokale Liste bleibt dann stehen.
     *  [onDone] läuft auf dem UI-Thread, damit die Liste neu gerendert werden kann. */
    private fun syncSessionsFromGateway(onDone: () -> Unit) {
        // Überlappung vermeiden: läuft bereits ein Abgleich (Poll oder manuell),
        // diesen Aufruf überspringen — der Callback läuft trotzdem, damit der
        // Aufrufer (z. B. Listenneuzeichnung) nicht hängen bleibt.
        if (gatewaySyncRunning) {
            onDone()
            return
        }
        gatewaySyncRunning = true
        // Schnappschuss auf dem UI-Thread: der Hintergrund-Abgleich arbeitet NUR auf
        // Kopien (Historie ist eine nicht threadsichere MutableList, die UI mutiert sie
        // weiter). Beim Anwenden wird gegen den Schnappschuss geprüft, ob sich die
        // echte Session seither geändert hat (gesendet, umbenannt, gelöscht …).
        val snapshots = sessionController.syncCandidates(sessions)
            .map { it.copy(history = it.history.toMutableList(), suppressedTurns = it.suppressedTurns.toMutableList()) }
        // Auch bei leerer Liste weiterlaufen: diffAgainstGateway prüft intern immer
        // zusätzlich die globale Gateway-Basis und importiert dort neu entstandene
        // Sessions, selbst wenn lokal noch keine gatewaySessionId existiert.
        Thread {
            val result = try {
                // Zuerst offene lokale Absichten (Löschen/Umbenennen) nachreichen,
                // dann abgleichen — lokale Absicht gewinnt, bis der Server bestätigt.
                flushPendingGatewayOps()
                sessionController.diffAgainstGateway(
                    snapshots,
                    isPendingDelete = { PendingSyncStore.isDeleted(this, it) },
                    hasPendingRename = { PendingSyncStore.hasPendingRename(this, it) },
                )
            } catch (e: Exception) {
                // Unerwarteter Fehler: Flag MUSS zurückgesetzt werden, sonst wäre das
                // Polling bis zum App-Neustart blockiert.
                runOnUiThread {
                    gatewaySyncRunning = false
                    onDone()
                }
                return@Thread
            }

            if (result.deletedIds.isEmpty() && result.renames.isEmpty() &&
                result.newSessions.isEmpty() && result.historyOverrides.isEmpty()
            ) {
                runOnUiThread {
                    gatewaySyncRunning = false
                    onDone()
                }
                return@Thread
            }

            runOnUiThread {
                try {
                    applyGatewaySyncResult(snapshots, result)
                } finally {
                    gatewaySyncRunning = false
                    onDone()
                }
            }
        }.start()
    }

    /** Wendet ein Sync-Ergebnis auf die ECHTEN Sessions an (UI-Thread). Das Ergebnis
     *  bezieht sich auf Schnappschuss-Kopien; jede Änderung wird nur übernommen, wenn
     *  die echte Session seit dem Schnappschuss unverändert ist — sonst gewinnt der
     *  lokale Stand (z. B. gerade gesendete Nachricht, frisch vergebener Titel). */
    private fun applyGatewaySyncResult(snapshots: List<ChatSession>, result: GatewaySyncResult) {
        val snapById = snapshots.associateBy { it.id }
        fun realOf(snap: ChatSession) = sessions.firstOrNull { it.id == snap.id }
        // true, wenn für diese Session gerade gesendet wird oder sich die Historie
        // seit dem Schnappschuss verändert hat.
        fun historyChanged(real: ChatSession): Boolean {
            val snap = snapById[real.id] ?: return true
            return (isStreaming() && activeStreamSessionId == real.id) || real.history != snap.history
        }

        for ((snap, newTitle) in result.renames) {
            val real = realOf(snap) ?: continue
            val gid = real.gatewaySessionId
            // Seit dem Schnappschuss lokal umbenannt oder Umbenennung noch offen → lokal gewinnt.
            if (real.title != snap.title) continue
            if (gid != null && PendingSyncStore.hasPendingRename(this, gid)) continue
            real.title = newTitle
            real.manualTitle = true
        }
        // Server gewinnt/source of truth: bekannte Sessions auf die Server-Gesamt-
        // Historie zurücksetzen (Nachzügler von anderen Geräten tauchen so wieder auf).
        // lastMessageAt auf die letzte Server-Nachricht heben, damit die Sortierung stimmt.
        for ((snap, serverHistory) in result.historyOverrides) {
            val real = realOf(snap) ?: continue
            if (historyChanged(real)) {
                // Nicht angewendet → Stand beim nächsten Tick erneut vom Server holen.
                real.gatewaySessionId?.let { sessionController.invalidateSyncStamp(it) }
                continue
            }
            val oldHistory = real.history.toList() // Vorher-Zustand für den UI-Abgleich
            real.history.clear()
            real.history.addAll(serverHistory)
            serverHistory.lastOrNull()?.let { real.lastMessageAt = it.timestamp }
            // Aktive Session: neue Nachrichten in die sichtbare Ansicht übernehmen
            // (inkrementell, ohne Scroll-Sprung) — sonst bliebe der Chat auf dem alten
            // Stand bis zum nächsten Session-Wechsel.
            if (real === activeSession) {
                applyHistoryUpdate(real, oldHistory, serverHistory)
            }
        }
        // Neu vom Gateway erkannte Sessions (Server gewinnt): nur in die lokale
        // Session-Liste aufnehmen, NICHT öffnen (Nutzer-Vorgabe). Erneut gegen
        // Tombstones und bereits vorhandene Gateway-IDs prüfen: seit dem Abgleichsstart
        // kann gelöscht oder (durch eine laufende Antwort) verknüpft worden sein.
        val knownGids = sessions.mapNotNull { it.gatewaySessionId }.toSet()
        val toAdd = result.newSessions.filter { n ->
            val gid = n.gatewaySessionId
            gid != null && gid !in knownGids && !PendingSyncStore.isDeleted(this, gid)
        }
        if (toAdd.isNotEmpty()) {
            sessions.addAll(toAdd)
        }
        // Serverseitig gelöscht: nur entfernen, wenn lokal seither nichts passiert ist.
        val safeDeleted = result.deletedIds.filter { id ->
            val real = sessions.firstOrNull { it.id == id }
            real != null && !historyChanged(real)
        }.toSet()
        if (safeDeleted.isNotEmpty()) {
            removeLocalSessions(safeDeleted) // persistiert intern
        } else {
            persistSessions()
        }
    }

    /** Ein Poll-Tick: nur wenn die App sichtbar ist, kein Abgleich läuft und keine
     *  Antwort gestreamt wird (sonst könnte der Abgleich die gerade gestreamte
     *  Historie überschreiben). Läuft auf dem UI-Thread. */
    private fun maybePollSync() {
        if (!appInForeground || gatewaySyncRunning || isStreaming()) return
        syncSessionsFromGateway { /* UI-Aktualisierungen macht der Sync selbst */ }
    }

    /** true, wenn die Chat-ScrollView (fast) am unteren Ende steht — dann dürfen
     *  neue Nachrichten automatisch nachgescrollt werden, sonst bleibt die
     *  Position des Lesers erhalten. */
    private fun isChatAtBottom(): Boolean {
        val scroll = chatScroll
        if (scroll.childCount == 0) return true
        val child = scroll.getChildAt(0)
        val tolerance = dp(24)
        return scroll.scrollY + scroll.height >= child.height - tolerance
    }

    /** Überträgt eine per Gateway-Sync aktualisierte Session in die sichtbare
     *  Chat-Ansicht. Üblicher Fall (neue Nachrichten hängen am Ende an): nur die
     *  neuen Nachrichten anhängen, ohne die Scroll-Position des Lesers zu
     *  verändern. Weichen die Historien ab (lokal stand z. B. mehr), wird die
     *  Ansicht komplett neu gerendert — der seltene Fall, ein Scroll-Sprung ist
     *  dort akzeptabel. Läuft auf dem UI-Thread. */
    private fun applyHistoryUpdate(
        session: ChatSession,
        oldHistory: List<ChatMessage>,
        newHistory: List<ChatMessage>
    ) {
        val wasAtBottom = isChatAtBottom()
        if (newHistory.size >= oldHistory.size && SessionController.sameConversation(newHistory.take(oldHistory.size), oldHistory)) {
            for (i in oldHistory.size until newHistory.size) {
                val m = newHistory[i]
                val isUser = m.role == "user"
                val showSeparator = isUser && i > 0 && newHistory[i - 1].role == "assistant"
                addMessage(
                    isUser = isUser,
                    label = chatLabel(isUser),
                    text = m.content,
                    timestamp = m.timestamp,
                    chatMessage = m,
                    showSeparator = showSeparator,
                    autoScroll = false
                )
            }
            if (wasAtBottom) scrollToBottom()
        } else {
            chatContainer.removeAllViews()
            renderHistory(session)
        }
    }

    /** Rendert die Nachrichten-Historie einer Session von oben nach unten. */
    private fun renderHistory(session: ChatSession) {
        for (i in session.history.indices) {
            val m = session.history[i]
            val isUser = m.role == "user"
            // Trenner nur am Beginn eines neuen Frage-Blocks, dessen vorheriger
            // Eintrag eine abgeschlossene Antwort ist (Frage+Antwort, Trenner,
            // Frage+Antwort, ...). Kein Trenner vor dem ersten Block.
            val showSeparator = isUser && i > 0 && session.history[i - 1].role == "assistant"
            addMessage(
                isUser = isUser,
                label = chatLabel(isUser),
                text = m.content,
                timestamp = m.timestamp,
                chatMessage = m,
                showSeparator = showSeparator
            )
        }
    }

    /** Setzt die untere Leiste passend zur Toggle-Präferenz (config.startInputMode):
     *  true = Sprach-/Mikrofonmodus, false = Tastaturmodus. Wird bei der Rückkehr
     *  aus der Session-History (Session neu/wechseln/löschen, Dialog schließen)
     *  angewandt, damit die Einstellung konsistent greift — der Toggle ist die
     *  Referenz. Ein bereits getippter Entwurf im Eingabefeld bleibt erhalten. */
    private fun applyInputPreference() {
        if (!config.startInputMode) {
            showKeyboardRowAtStart()
            return
        }
        // Sprach-/Mikrofonmodus (Standard): Eingabezeile aus, Record-Button an.
        if (inputRow.visibility != View.GONE) {
            inputRow.visibility = View.GONE
            recordButton.visibility = View.VISIBLE
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(inputField.windowToken, 0)
            keyboardToggle.contentDescription = getString(R.string.keyboard_toggle_open)
        }
    }

    /** Referenz auf den Listen-Container des gerade offenen Session-Dialogs, damit
     *  Änderungen (Favorit-Toggle) gezielt nur die Liste neu zeichnen, ohne den
     *  ganzen Dialog neu aufzubauen (verursachte sichtbares Flackern). */
    private var sessionListContainer: LinearLayout? = null

    /** Rendert die Session-Liste im bereits offenen Dialog neu (inkl. sofortiger
     *  Neu-Sortierung, Favoriten zuerst). Kein Dialog-Neuaufbau → kein Flackern. */
    private fun refreshSessionList() {
        if (activeDialog?.isShowing != true) return
        val container = sessionListContainer ?: return
        populateSessionList(container)
    }

    /** Öffnet das Session-Menü (Hamburger): Liste + Neue-Session erstellen. */
    private fun showSessionsDialog() {
        // Bereits offenen Dialog zuerst schließen, sonst stapelt sich beim erneuten
        // Aufbau (z. B. Stern-Toggle, Löschen, Umbenennen) ein zweiter darüber und
        // das Fenster lässt sich nicht mehr schließen.
        activeDialog?.dismiss()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_sessions, null)
        val listContainer = view.findViewById<LinearLayout>(R.id.sessionListContainer)
        sessionListContainer = listContainer

        view.findViewById<ImageButton>(R.id.newSessionButton).setOnClickListener {
            newSession()
            if (activeDialog != null) activeDialog?.dismiss()
        }

        // Schließen ohne Auswahl — Dialog zuklappen und die Toggle-Präferenz für
        // die Eingabeart wieder anwenden (Tastatur- vs. Sprachmodus).
        view.findViewById<TextView>(R.id.closeSessionsButton).setOnClickListener {
            activeDialog?.dismiss()
            applyInputPreference()
        }

        // Suchfeld: filtert live beim Tippen, der Dialog bleibt offen (kein Fokusverlust).
        val searchInput = view.findViewById<EditText>(R.id.sessionSearchInput)
        searchInput.setText(sessionSearchQuery)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: Editable?) {
                sessionSearchQuery = s?.toString() ?: ""
                populateSessionList(listContainer)
            }
        })

        populateSessionList(listContainer)

        activeDialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        activeDialog?.show()

        // Reverse-Sync beim Öffnen: Server-Stand nachziehen (Server gewinnt),
        // asynchron nach dem Anzeigen; bei Änderungen wird die Liste neu gerendert.
        syncSessionsFromGateway { populateSessionList(listContainer) }
    }

    /** Befüllt die Session-Liste: leere Suche = Sortierung, sonst Trefferliste. */
    private fun populateSessionList(container: LinearLayout) {
        container.removeAllViews()
        val query = sessionSearchQuery.trim()

        if (query.isEmpty()) {
            // Sortieren: favorisierte (gepinnte) Sessions zuerst, davor die zuletzt
            // mit echtem Austausch aktivste; innerhalb jeder Gruppe neueste zuerst.
            val sorted = sessions.sortedWith(
                compareByDescending<ChatSession> { it.pinned }
                    .thenByDescending { it.lastMessageAt }
            )
            if (sorted.isEmpty()) {
                container.addView(emptySessionHint(getString(R.string.session_empty)))
                return
            }
            for (session in sorted) {
                container.addView(buildSessionRow(session, null))
            }
            return
        }

        val hits = sessionController.searchSessions(sessions, query, themeAccent())
        if (hits.isEmpty()) {
            container.addView(emptySessionHint(getString(R.string.session_search_empty, query)))
            return
        }
        for (hit in hits) {
            container.addView(buildSessionRow(hit.session, hit))
        }
    }

    private fun emptySessionHint(text: CharSequence): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        setPadding(0, 16, 0, 16)
    }

    /** Aktuelle Akzent-Basisfarbe aus dem Theme-Attribut colorAccent auflösen. */
    private fun themeAccent(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) tv.data
        else getColor(R.color.accent)
    }

    /** Baut eine einzelne Session-Zeile (Klick = öffnen, Mülleimer = löschen). */
    private fun buildSessionRow(session: ChatSession, hit: SearchHit? = null): View {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_session, null) as LinearLayout

        val title = row.findViewById<TextView>(R.id.sessionRowTitle)
        title.text = session.title.ifBlank { getString(R.string.session_default_title) }
        // Die aktive Session farblich hervorheben.
        if (session.id == activeSession.id) {
            title.setTextColor(themeAccent())
        }

        val meta = row.findViewById<TextView>(R.id.sessionRowMeta)
        val snippet = row.findViewById<TextView>(R.id.sessionRowSnippet)
        if (hit != null) {
            // Suchmodus: Trefferzahl statt Datum, Treffersnippet als zweite Zeile.
            meta.text = getString(R.string.session_search_count, hit.matchCount)
            snippet.text = hit.snippet
            snippet.visibility = View.VISIBLE
        } else {
            meta.text = formatSessionDate(session.lastMessageAt) +
                " · " + getString(R.string.session_count, session.history.size)
            snippet.visibility = View.GONE
        }

        // Favorit (Stern): gefüllter, akzentfarbener Stern bei aktivem Favorit,
        // sonst Kontur in Sekundärfarbe. Toggle speichert und rendert gezielt nur
        // die Liste neu (sofortige Neu-Sortierung, ohne Dialog-Neuaufbau → kein
        // Flackern des Fensters).
        val pinBtn = row.findViewById<ImageButton>(R.id.sessionPinButton)
        val pinActive = session.pinned
        pinBtn.setImageResource(if (pinActive) R.drawable.ic_star else R.drawable.ic_star_outline)
        pinBtn.imageTintList = ColorStateList.valueOf(
            getColor(if (pinActive) R.color.accent else R.color.text_secondary)
        )
        pinBtn.contentDescription = getString(
            if (pinActive) R.string.session_unpin else R.string.session_pin
        )
        pinBtn.setOnClickListener {
            session.pinned = !session.pinned
            persistSessions()
            refreshSessionList()
        }

        row.setOnClickListener {
            activeDialog?.dismiss()
            switchToSession(session.id)
        }
        row.findViewById<ImageButton>(R.id.shareSessionButton).setOnClickListener {
            activeDialog?.dismiss()
            shareSession(session)
        }
        row.findViewById<ImageButton>(R.id.renameSessionButton).setOnClickListener {
            renameSession(session)
        }
        row.findViewById<ImageButton>(R.id.deleteSessionButton).setOnClickListener {
            confirmDeleteSession(session)
        }
        return row
    }

    /** Dialog zum manuellen Umbenennen einer Session (max. 48 Zeichen). */
    private fun renameSession(session: ChatSession) {
        val input = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.session_rename_hint)
            filters = arrayOf(InputFilter.LengthFilter(48))
            setText(session.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.session_rename_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newTitle = input.text.toString().trim()
                session.title = newTitle
                // Leerer Titel = zurück zur automatischen Benennung.
                session.manualTitle = newTitle.isNotEmpty()
                persistSessions()
                // Gateway-Umbenennung nachziehen (PATCH /api/sessions/{id}), damit
                // der lokale Titel nicht vom Server driftet. Best-effort wie beim
                // Löschen: asynchron, Fehler bleiben ohne Nutzerhinweis.
                // Lokale Absicht gewinnt: Pending-Rename persistent vormerken; der
                // Reverse-Sync übernimmt den Server-Titel erst, wenn der Server den
                // lokalen bestätigt (oder dauerhaft abgelehnt) hat.
                val gatewaySessionId = session.gatewaySessionId
                if (!gatewaySessionId.isNullOrBlank()) {
                    PendingSyncStore.setRename(this, gatewaySessionId, chatSender.effectiveBase(session), newTitle)
                    Thread { flushPendingGatewayOps() }.start()
                }
                activeDialog?.dismiss()
                showSessionsDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Bestätigungsdialog vor dem Löschen einer Session. */
    private fun confirmDeleteSession(session: ChatSession) {
        AlertDialog.Builder(this)
            .setTitle(R.string.session_delete_confirm_title)
            .setMessage(R.string.session_delete_confirm_msg)
            .setPositiveButton(R.string.session_delete) { _, _ ->
                activeDialog?.dismiss()
                deleteSession(session.id)
                showSessionsDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Export der übergebenen Session als Markdown und Teilen über das
     * System-Share-Sheet (SendTo). Die .md-Datei wird unter API 29+ als
     * MediaStore-Download angelegt und über die content://media-URI geteilt;
     * der Leser lädt sie über den System-MediaProvider (immer laufen) statt
     * über den eigenen App-Provider. Grund: Auf gehärteten ROMs (z. B.
     * GrapheneOS-ähnlich) publiziert der App-Prozess seinen FileProvider nie
     * ans ActivityManagerService — das Share-Sheet hängt dann für 3×20 s bei
     * „Failed to find provider info". Die MediaStore-URI umgeht diesen
     * defekten Pfad vollständig. Unter API 26-28 fällt sie auf den
     * FileProvider im Cache zurück. Eine leere Session wird nicht exportiert;
     * das Reasoning wird bewusst nicht aus der Historie übernommen.
     */
    private fun shareSession(session: ChatSession) {
        if (session.history.isEmpty()) {
            Toast.makeText(this, R.string.session_share_empty, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val markdown = buildSessionMarkdown(session)
            val subject = session.title.ifBlank {
                getString(R.string.session_default_title) }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeExportToMediaStore(subject, markdown)
            } else {
                val file = File(cacheDir, buildExportFileName(subject))
                file.writeText(markdown)
                FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", file)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                // Primär Markdown, damit der Dateiname sauber .md bleibt.
                // Zusätzlich text/plain über EXTRA_MIME_TYPES, damit
                // Ziel-Apps ohne text/markdown-Support die Datei trotzdem
                // annehmen und das .txt-Anhängen älterer Mail-Clients
                // vermieden wird. Der Inhalt bleibt in beiden Fällen
                // Markdown.
                type = "text/markdown"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/markdown", "text/plain"))
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                // setClipData + Flag legen den URI-Grant fest, sodass das
                // Share-Sheet und die Ziel-App die Datei lesen können.
                clipData = ClipData.newUri(contentResolver, subject, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Wichtig: Der URI-Grant muss auch auf dem createChooser-Wrapper
            // liegen. Nur auf dem inneren Intent reicht auf Android 13+ nicht,
            // sonst kann der Share-Resolver die Datei-Vorschau nicht lesen.
            val chooser = Intent.createChooser(send, getString(R.string.session_share_title))
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.session_share_error, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Schreibt die Markdown-Datei als Download in den MediaStore und liefert
     * die zugehörige content://media-URI. Die Datei wird stufenweise über das
     * IS_PENDING-Flag veröffentlicht, damit sie erst nach vollständigem
     * Schreiben für andere Apps erscheint. @throws Exception bei Fehlern.
     */
    private fun writeExportToMediaStore(subject: String, markdown: String): Uri {
        val filename = buildExportFileName(subject)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
            put(MediaStore.Downloads.IS_PENDING, 1)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        contentResolver.openOutputStream(uri).use { os ->
            os ?: throw IllegalStateException("MediaStore stream failed")
            os.write(markdown.toByteArray(Charsets.UTF_8))
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    /** Baut die Markdown-Datei aus der Session-Historie (Titel + Nachrichten). */
    private fun buildSessionMarkdown(session: ChatSession): String {
        val timeFmt = java.text.SimpleDateFormat(
            "dd.MM.yyyy, HH:mm", AppLocale.effectiveLocale(this))
        val title = session.title.ifBlank { getString(R.string.session_default_title) }
        val sb = StringBuilder()
        sb.append("# ").append(title).append('\n')
        for (msg in session.history) {
            val label = chatLabel(msg.role == "user")
            val time = timeFmt.format(java.util.Date(msg.timestamp))
            sb.append('\n').append("## ").append(label).append(" · ").append(time).append('\n')
            sb.append(msg.content).append('\n')
        }
        return sb.toString()
    }

    /** Dateiname für den Export: bereinigter Titel + Zeitstempel. */
    private fun buildExportFileName(title: String): String {
        val safe = title.ifBlank { getString(R.string.session_default_title) }
            .replace(Regex("[^A-Za-z0-9äöüÄÖÜß _.-]"), "").trim()
            .ifBlank { "session" }.replace(' ', '_').take(60)
        val stamp = java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss", AppLocale.effectiveLocale(this)).format(java.util.Date())
        return "hermi_${safe}_$stamp.md"
    }

    /** Formatiert den letzten Austausch-Zeitpunkt als Datum + Uhrzeit
     *  (Heute, 10:22 / Gestern, 09:05 / 12.10.2026, 08:41). */
    private fun formatSessionDate(epochMillis: Long): String {
        val dayFmt = java.text.SimpleDateFormat("dd.MM.yyyy", AppLocale.effectiveLocale(this))
        val timeFmt = java.text.SimpleDateFormat("HH:mm", AppLocale.effectiveLocale(this))
        val day = dayFmt.format(java.util.Date(epochMillis))
        val time = timeFmt.format(java.util.Date(epochMillis))
        val today = dayFmt.format(java.util.Date())
        val yesterday = (java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_MONTH, -1)
        }).timeInMillis.let { dayFmt.format(java.util.Date(it)) }
        val base = when (day) {
            today -> getString(R.string.session_date_today)
            yesterday -> getString(R.string.session_date_yesterday)
            else -> day
        }
        return "$base, $time"
    }

    /** Formatiert den Nachrichten-Zeitpunkt immer als Datum + Uhrzeit
     *  (kein "heute"), kompakt für die Anzeige neben dem Label. */
    private fun formatMessageTime(epochMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("dd.MM. / HH:mm 'Uhr'", AppLocale.effectiveLocale(this))
        return fmt.format(java.util.Date(epochMillis))
    }

    // ── Konversations-Anzeige ─────────────────────────────────────────────

    /** Rendert eine dezente Systemzeile (z. B. „Neue Konversation nach Pause").
     *  Wird nur angezeigt, nie in die Verlaufs-Historie geschrieben — die Zeile
     *  darf den mitgeschickten Kontext nicht verwässern. */
    private fun addSystemNotice(text: String) {
        val tv = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.ITALIC)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }
        chatContainer.addView(tv)
        scrollToBottom()
    }

    /**
     * Zwei-Tap-Schutz (zentral).
     *
     * setTextIsSelectable(true) setzt intern isFocusableInTouchMode=true (AOSP
     * TextView.setTextIsSelectable). Dadurch klaut ein normaler Tap auf die
     * Bubble den IME-Fokus vom inputField — die Tastatur verschwindet und der
     * User muss ein zweites Mal tippen („Zwei-Tap-Problem").
     *
     * isFocusableInTouchMode=false als Gegenmittel bricht die Long-Press-Auswahl
     * (Editor.checkField -> requestFocus schlägt im Touch-Modus fehl ->
     * „Selection cancelled.", belegt im Emulator). Stattdessen wird hier ein
     * Touch-Listener gesetzt, der nur kurze Taps abfängt und den Fokus zurück
     * zum inputField schiebt; Long-Press (>= getLongPressTimeout) bleibt
     * unberührt, die Selection funktioniert weiter.
     *
     * Rückgabe false: Das Event wird nicht konsumiert — Link-Klicks und die
     * Long-Press-Erkennung der TextView laufen normal weiter.
     */
    private fun installTwoTapGuard(tv: TextView) {
        var downTime = 0L
        var downX = 0f
        var downY = 0f
        var inputFocusedBeforeDown = false
        tv.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = event.eventTime
                    lastBubbleTouchTime = SystemClock.uptimeMillis()
                    downX = event.x
                    downY = event.y
                    inputFocusedBeforeDown = ::inputField.isInitialized && inputField.isFocused
                }
                MotionEvent.ACTION_UP -> {
                    val timeout = ViewConfiguration.getLongPressTimeout().toLong()
                    val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                    val isTap = event.eventTime - downTime < timeout &&
                        Math.abs(event.x - downX) <= slop &&
                        Math.abs(event.y - downY) <= slop
                    if (isTap && inputFocusedBeforeDown && ::inputField.isInitialized) {
                        // Kurzer Tap: Fokus zurück ans Eingabefeld (post, damit der
                        // Fokuswechsel erst nach der Event-Verarbeitung der Bubble greift).
                        inputField.post { inputField.requestFocus() }
                    }
                }
                else -> Unit
            }
            false // Event nicht konsumieren — Link-Klicks & Long-Press-Erkennung bleiben intakt
        }
    }

    /** Fügt eine feste Nachricht (Frage oder Antwort) als Chat-Bubble hinzu.
     *  Der Zeitstempel steht neben dem Label auf Label-Höhe (nie in der Bubble). */
    private fun addMessage(
        isUser: Boolean,
        label: String,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
        chatMessage: ChatMessage? = null,
        showSeparator: Boolean = false,
        countdownSeconds: Int? = null,
        autoScroll: Boolean = true
    ): View {
        val v = inflateMessage() as LinearLayout
        applyMessageAvatar(v, isUser)
        // Trenner nur am Beginn eines neuen Frage-Blocks (siehe Aufrufer).
        // Deaktiviert (Einstellungen): Linie ausblenden, Abstand bleibt (INVISIBLE).
        v.findViewById<View>(R.id.messageSeparator).apply {
            visibility = when {
                !showSeparator -> View.GONE
                !config.showMessageSeparator -> View.INVISIBLE
                else -> View.VISIBLE
            }
        }
        // Avatar sitzt INLINE in der Kopfzeile (XML-Standard-Reihenfolge:
        // [Avatar, Zeit, Label, Mülleimer]). Reihenfolge und Ausrichtung sind
        // typabhängig — der Avatar bleibt dabei immer direkt am eigenen Item:
        //   "Hermes" -> Avatar, "Hermes", Zeit, Mülleimer   (linksbündig)
        //   "Du"     -> Mülleimer, Zeit, "Du", Avatar       (rechtsbündig)
        val row = v.findViewById<LinearLayout>(R.id.messageHeaderRow)
        val avatar = v.findViewById<View>(R.id.messageAvatar)
        val del = v.findViewById<View>(R.id.messageDeleteButton)
        if (isUser) {
            // "Du": Mülleimer nach vorn, Avatar ans Ende (rechts neben "Du").
            row.removeView(del)
            row.removeView(avatar)
            row.addView(del, 0)
            row.addView(avatar)
            // Analog zum Abstand zwischen den Kopf-Elementen bei "Hermes"
            // (8dp via paddingEnd) gilt hier derselbe Abstand zwischen
            // Mülleimer und Datumsanzeige — nur am Button statt am Label.
            (del.layoutParams as LinearLayout.LayoutParams).marginEnd =
                (8 * resources.displayMetrics.density).toInt()
        } else {
            // "Hermes": Label direkt nach dem Avatar (Avatare haben in der
            // XML bereits kleine seitliche Margins, damit kein Abstands-Loch
            // zwischen Avatar und nächstliegendem Label/Zeit entsteht).
            val label = v.findViewById<View>(R.id.messageLabel)
            row.removeView(label)
            row.addView(label, 1)
        }
        // Ganzes Nachrichten-Block ausrichten: "Hermes" links, "Du" rechts —
        // nur die Inhaltspalte, NICHT die Wurzel (sonst wandert der volle
        // Trennstrich mit und der Avatar käme auf die falsche Seite).
        v.findViewById<LinearLayout>(R.id.messageBodyColumn).gravity =
            if (isUser) Gravity.END else Gravity.START
        v.findViewById<TextView>(R.id.messageLabel).apply {
            setTextColor(getColor(R.color.text_secondary))
            this.text = label
            typeface = Typeface.DEFAULT_BOLD
        }
        v.findViewById<TextView>(R.id.messageTime).apply {
            this.text = formatMessageTime(timestamp)
        }
        v.findViewById<LinearLayout>(R.id.bubbleBody).apply {
            background = getDrawable(if (isUser) R.drawable.bubble_user else R.drawable.bubble_hermes)
        }
        v.findViewById<TextView>(R.id.messageText).apply {
            setTextColor(getColor(R.color.text_primary))
            // Nur Modell-Antworten werden als Markdown formatiert; eigene
            // Eingaben bleiben unverändert.
            this.text = if (isUser) text else formatMarkdown(text)
            setTextIsSelectable(true) // Long-Press-Auswahl aktivieren
            // setTextIsSelectable(sel) setzt intern selbst focusable+focusableInTouchMode
            // (AOSP TextView.setTextIsSelectable). isFocusableInTouchMode MUSS true bleiben:
            // AOSP Editor.checkField() (startSelectionActionMode) verlangt im Touch-Modus ein
            // erfolgreiches requestFocus(), das nur bei isFocusableInTouchMode=true klappt.
            // Ein isFocusableInTouchMode=false erzeugt hier "TextView does not support text
            // selection. Selection cancelled." (belegt im Emulator, PID de.hermi.voice).
            // HINWEIS: Dies ist das EXPERIMENT ohne isFocusableInTouchMode=false — prüft die
            // These, dass dieses Flag die Long-Press-Auswahl zerstört.
            movementMethod = LinkArrowKeyMovementMethod.getInstance()
            // Zwei-Tap-Schutz: Fokus bleibt nach kurzem Tap am inputField
            // (isFocusableInTouchMode=true bleibt, Long-Press-Auswahl intakt).
            installTwoTapGuard(this)
        }
        // First-Token-Countdown: beim Senden läuft der Countdown NICHT im
        // messageText (16sp), sondern in der kleinen, gedimmten messageCountdown-
        // Zeile (12sp, wie die Fallback-Notiz). Der messageText bleibt leer und
        // wird erst vom echten Stream befüllt, sobald das erste Token kommt.
        if (countdownSeconds != null) {
            // Leerbereich vermeiden: messageText (16sp) bleibt bis zum ersten
            // Token leer und würde als leere Zeile vertikalen Platz einnehmen.
            // Dafür GONE setzen — die Zeile kollabiert, der Ist-Punkt rückt an
            // den Bubble-Anfang. Beim ersten echten Inhalt (updateLastHermes-
            // Message) wird sie wieder VISIBLE.
            v.findViewById<TextView>(R.id.messageText).apply {
                this.text = ""
                visibility = View.GONE
            }
            v.findViewById<TextView>(R.id.messageCountdown).apply {
                this.text = getString(R.string.model_working_countdown, countdownSeconds)
                visibility = View.VISIBLE
            }
        } else {
            v.findViewById<TextView>(R.id.messageCountdown).visibility = View.GONE
        }
        // Kein Seiten-Spiegeln mehr: Wurzel ist vertikal und bleibt immer
        // linksbündig. Der Avatar liegt fest in der Kopfzeile ganz links, die
        // zugehörige Reihenfolge (Label/Zeit/Mülleimer) wird oben gesetzt.
        if (chatMessage != null) enableBubbleDelete(v, chatMessage)
        chatContainer.addView(v)
        // autoScroll=false: beim Polling-Anhängen die Scroll-Position des Lesers
        // nicht verändern (gescrollt wird nur, wenn er ohnehin unten war).
        if (autoScroll) scrollToBottom()
        return v
    }

    /** Fügt die Live-Vorschau-Zeile (laufendes Zwischenergebnis) ein. */
    /** Bindet die Lösch-Schaltfläche einer Bubble an ihre History-Nachricht und
     *  macht sie sichtbar. Referenz [msg] statt Index: Indizes verschieben sich
     *  beim Löschen, die Referenz bleibt stabil. Ein Tap löscht sofort, ohne
     *  Bestätigungsdialog. */
    private fun enableBubbleDelete(v: View, msg: ChatMessage) {
        v.tag = msg
        v.findViewById<View>(R.id.messageDeleteButton).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                // Nach einem Sync-Override sind die Objekte ausgetauscht → Fallback
                // auf Rolle + Inhalt (statt dass das Löschen stillschweigend ins Leere läuft).
                val idx = activeSession.history.indexOfFirst { it === msg }.takeIf { it >= 0 }
                    ?: activeSession.history.indexOfLast { it.role == msg.role && it.content.trim() == msg.content.trim() }
                if (idx >= 0) activeSession.history.removeAt(idx)
                chatContainer.removeView(v)
                persistSessions()
            }
        }
    }

    private fun addLiveMessage(label: String): TextView {
        val v = inflateMessage() as LinearLayout
        applyMessageAvatar(v, isUser = true)
        v.findViewById<TextView>(R.id.messageLabel).apply {
            setTextColor(getColor(R.color.text_secondary))
            this.text = label
            typeface = Typeface.DEFAULT_BOLD
        }
        // Live-Vorschau hat noch keinen Zeitpunkt — Zeitstempel ausblenden.
        v.findViewById<TextView>(R.id.messageTime).apply {
            this.visibility = View.GONE
        }
        v.findViewById<TextView>(R.id.messageText).apply {
            setTextColor(getColor(R.color.text_secondary))
            this.text = ""
            setTextIsSelectable(true) // placeholder, Long-Press-Auswahl aktivieren
            // AOSP: isFocusableInTouchMode muss true bleiben (Editor.checkField/
            // requestFocus) — siehe Z.2172. EXPERIMENT: Zeile entfernt.
            movementMethod = LinkArrowKeyMovementMethod.getInstance()
            // Zwei-Tap-Schutz analog zur festen Nachricht (installTwoTapGuard).
            installTwoTapGuard(this)
        }
        // Live-Vorschau rechts ausrichten wie eine finale "Du"-Blase: Die
        // Ausrichtung gehört auf die messageBodyColumn, NICHT auf die Wurzel.
        // (Gravity auf der match_parent-Wurzel hat keinen Effekt → die Blase
        // würde sonst links stehen, siehe Bug "falsche Du-Blase".)
        v.findViewById<LinearLayout>(R.id.messageBodyColumn).gravity = Gravity.END
        // Bubble-Hintergrund identisch zur User-Blase (XML hat keinen Default).
        v.findViewById<LinearLayout>(R.id.bubbleBody).background =
            getDrawable(R.drawable.bubble_user)
        // Wurzel merken, damit removeLiveMessage die GANZE Blase entfernt.
        liveBubbleRoot = v
        chatContainer.addView(v)
        scrollToBottom()
        return v.findViewById(R.id.messageText)
    }

    private fun removeLiveMessage() {
        // Die GANZE Live-Bubble (Wurzel = direktes Kind von chatContainer)
        // entfernen, nicht nur bubbleBody — sonst bleibt die partielle
        // Vorschau-Blase als zweite "Du"-Blase neben der finalen stehen
        // (Bug: zwei "Du"-Blasen bei Spracheingabe).
        liveBubbleRoot?.let { root -> removeView(root) }
        liveBubbleRoot = null
        liveText = null
    }

    private fun removeView(v: View) {
        chatContainer.removeView(v)
        scrollToBottom()
    }

    private fun removeLastHermesMessage() {
        if (chatContainer.childCount > 0) {
            chatContainer.removeViewAt(chatContainer.childCount - 1)
            scrollToBottom()
        }
    }

    /** Aktualisiert die zuletzt hinzugefügte Nachricht (Hermes-Antwort bzw.
     *  deren "…"-Platzhalter). Der Text wird ersetzt; optional wird auch der
     *  Zeitstempel auf den Antwort-Zeitpunkt gesetzt (Empfangszeit, nicht die
     *  Erstellzeit des Platzhalters). Liefert das Modell Denk-Tokens, wird der
     *  Klapp-Denkblock eingerichtet (siehe [applyReasoning]); ist
     *  [reasoningDone] gesetzt, zeigt die Kopfzeile die Zeichenzahl des
     *  beendeten Denkprozesses. Bei aufgeklapptem Denkblock wird das
     *  Auto-Scroll bei jedem Token unterdrückt, damit die Ansicht nicht
     *  nach unten reißt, während der Nutzer den Denktext liest. */
    private fun updateLastHermesMessage(
        text: String,
        timestamp: Long? = null,
        reasoning: String? = null,
        reasoningDone: Boolean = false,
        streaming: Boolean = false
    ) {
        // Die zuletzt hinzugefügte Nachricht ist die Hermes-Antwort bzw. der
        // "…"-Platzhalter. Deren Textinhalt aktualisieren.
        for (i in chatContainer.childCount - 1 downTo 0) {
            val child = chatContainer.getChildAt(i) as? View ?: continue
            val tv = child.findViewById<TextView>(R.id.messageText) ?: continue
            tv.setTextColor(getColor(R.color.text_primary))
            // Beim First-Token-Countdown ist messageText auf GONE (Leerbereich
            // ausblenden); sobald echter Antworttext ankommt, wieder einblenden.
            tv.visibility = View.VISIBLE
            // (a) Während des Streamings nur den Rohtext setzen (kein Markdown-
            // Parsing bei jedem Chunk — das relayoutet den ganzen Text und
            // flackert). Das teure Markdown-Rendering passiert genau einmal am
            // Ende des Streams ([streaming] == false).
            tv.text = if (streaming) text else formatMarkdown(text)
            tv.setTextIsSelectable(true) // Long-Press-Auswahl aktivieren
            // AOSP: isFocusableInTouchMode muss true bleiben (Editor.checkField/
            // requestFocus) — siehe Z.2172. EXPERIMENT: Zeile entfernt.
            tv.movementMethod = LinkArrowKeyMovementMethod.getInstance()
            applyReasoning(child, reasoning, reasoningDone)
            if (timestamp != null) {
                child.findViewById<TextView>(R.id.messageTime)
                    ?.text = formatMessageTime(timestamp)
            }
            // Während des Streamings kontinuierlich ans Ende folgen, damit die
            // Antwort nach unten anwächst (kein "Hängen+Springen"). Das ist
            // jetzt flackertfrei, weil (a) nur Rohtext gesetzt wird und kein
            // Markdown-Parsing pro Chunk läuft. Nur der finale Schritt (Stream
            // zu Ende, [streaming]==false) respektiert, dass der Nutzer nach
            // oben gescrollt haben könnte, und reißt die Ansicht dann nicht weg.
            if (!reasoningExpanded && (streaming || isChatAtBottom())) scrollToBottom()
            return
        }
    }

    /** Richtet den Klapp-Denkblock einer Nachricht ein. Ohne Denk-Text bleibt
     *  alles verborgen; sonst zeigt die Kopfzeile „Modell denkt…“ (während des
     *  Denkens) bzw. „Denkprozess beendet · N Zeichen“ ([reasoningDone]).
     *  Standard ist zugeklappt; Klick auf die Kopfzeile klappt auf/zu. */
    private fun applyReasoning(child: View, reasoning: String?, reasoningDone: Boolean) {
        val rv = child.findViewById<TextView>(R.id.messageReasoning) ?: return
        val header = child.findViewById<View>(R.id.reasoningHeader)
        val chevron = child.findViewById<TextView>(R.id.reasoningChevron)
        val label = child.findViewById<TextView>(R.id.reasoningLabel)
        val answer = child.findViewById<TextView>(R.id.messageText)
        if (reasoning.isNullOrBlank()) {
            rv.visibility = View.GONE
            header?.visibility = View.GONE
            // Ohne Denk-Block gilt wieder der normale Abstand Label→Antwort.
            answer?.setPadding(answer.paddingLeft, dp(10), answer.paddingRight, answer.paddingBottom)
            return
        }
        // Denk-Block vorhanden: gleicher Innenabstand der Antwort-Bubble wie
        // bei allen anderen Nachrichten (Nutzer-Vorgabe: Text darf nicht zu
        // nah an die Oberkante der Bubble rücken; Referenz ist die DU-Bubble).
        answer?.setPadding(answer.paddingLeft, dp(10), answer.paddingRight, answer.paddingBottom)
        rv.text = reasoning
        header?.visibility = View.VISIBLE
        chevron?.text = if (reasoningExpanded) "▸" else "▾"
        label?.text = if (reasoningDone) {
            getString(R.string.chat_reasoning_done, reasoning.length)
        } else {
            getString(R.string.chat_reasoning_thinking)
        }
        rv.visibility = if (reasoningExpanded) View.VISIBLE else View.GONE
        header?.setOnClickListener {
            reasoningExpanded = !reasoningExpanded
            rv.visibility = if (reasoningExpanded) View.VISIBLE else View.GONE
            chevron?.text = if (reasoningExpanded) "▸" else "▾"
        }
    }

    private fun inflateMessage(): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_chat_message, chatContainer, false)
        return v
    }

    /** Hintergrund des Chat-Bereichs an die Einstellung anpassen.
     *  Angewendet wird der voll nutzbare Scroll-Bereich (chatScroll) – dieser
     *  füllt via fillViewport den gesamten Nachrichtenbereich. Standard lässt
     *  den bisherigen (transparenten/Thema-)Hintergrund unangetastet. */
    // Zähler für veraltete Hintergrund-Dekodierungen (siehe applyChatBackground).
    @Volatile private var chatBgToken = 0

    private fun applyChatBackground() {
        val scroll = findViewById<ScrollView>(R.id.chatScroll)
        val bg = config.chatBackground()
        scroll.background = when (bg) {
            ChatBackground.GRADIENT -> {
                val grad = config.chatGradientId
                val res = config.chatGradients()[grad] ?: config.chatGradients().values.first()
                ContextCompat.getDrawable(this, res)
            }
            ChatBackground.IMAGE -> {
                val file = config.chatBackgroundImageFile()
                if (file.exists()) {
                    // Dekodierung im Hintergrund: Ein großes Bild blockierte sonst den
                    // UI-Thread (Ruckler/ANR beim Start und beim Zurückkehren aus den
                    // Einstellungen). Bis das Bild fertig ist, bleibt der bisherige
                    // Hintergrund stehen; Token verwirft veraltete Ergebnisse.
                    val token = ++chatBgToken
                    val reqW = scroll.width.coerceAtLeast(1)
                    val reqH = scroll.height.coerceAtLeast(1)
                    thread {
                        val bmp = decodeSampledBitmap(file, reqW, reqH)
                        runOnUiThread {
                            if (!isDestroyed && token == chatBgToken &&
                                config.chatBackground() == ChatBackground.IMAGE
                            ) {
                                scroll.background = bmp?.let { BitmapDrawable(resources, it) }
                            }
                        }
                    }
                    scroll.background
                } else null
            }
            else -> null // Standard = bisherige Einfärbung, kein Bild
        }
    }

    // Avatar-Bitmaps einmal dekodieren und wiederverwenden: Früher wurde die Datei für JEDE
    // Nachricht erneut von der Platte gelesen und dekodiert (O(Nachrichten) auf dem UI-Thread).
    // Schlüssel enthält Änderungszeit/Größe, damit ein neues Bild sofort greift.
    private val avatarBitmapCache = HashMap<String, Bitmap>()

    private fun cachedAvatarBitmap(file: File): Bitmap? {
        val key = "${file.path}|${file.lastModified()}|${file.length()}"
        avatarBitmapCache[key]?.let { return it }
        val bmp = decodeSampledBitmap(file, AVATAR_PX, AVATAR_PX) ?: return null
        // Alte Einträge desselben Pfads verwerfen (nach Bildwechsel).
        avatarBitmapCache.keys.removeAll { it.startsWith(file.path + "|") }
        avatarBitmapCache[key] = bmp
        return bmp
    }

    /** Lädt ein Hintergrund-Bild speicherschonend, auf den Zielbereich skaliert. */
    private fun decodeSampledBitmap(file: File, reqW: Int, reqH: Int): Bitmap? {
        // Zuerst nur die Maße lesen.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > reqW * 2 || bounds.outHeight / sample > reqH * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    /** Setzt den Avatar (Gesicht/Roboter) samt Leuchtfarbe auf eine Nachricht.
     *  Eigene Eingaben zeigen das Gesicht, Bot-/Hermes-Antworten den Avatar des
     *  aktiven Bots (konfigurierbar über BotProfile.avatar; Standard "robot").
     *  Hat der Nutzer ein eigenes Avatar-Bild hochgeladen, wird dieses stattdessen
     *  verwendet (ohne Akzentfärbung), sonst bleibt das Standard-Drawable aktiv. */
    private fun applyMessageAvatar(root: View, isUser: Boolean) {
        val avatar = root.findViewById<ImageView>(R.id.messageAvatar) ?: return
        val imgFile = if (isUser) config.userAvatarFile() else config.botAvatarFile()
        val pathOk = if (isUser) config.userAvatarPath.isNotBlank() else config.botAvatarPath.isNotBlank()
        if (pathOk && imgFile.exists()) {
            val bmp = cachedAvatarBitmap(imgFile)
            if (bmp != null) {
                avatar.setImageBitmap(bmp)
                avatar.imageTintList = null // echtes Bild nicht monochrom färben
                avatar.setBackgroundResource(R.drawable.avatar_circle_bg)
                avatar.outlineProvider = ViewOutlineProvider.BACKGROUND
                avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                avatar.clipToOutline = true
                avatar.contentDescription = getString(
                    if (isUser) R.string.chat_avatar_user else R.string.chat_avatar_bot
                )
                return
            }
        }
        val drawRes = when {
            isUser -> R.drawable.avatar_face
            activeBot?.avatar == "face" -> R.drawable.avatar_face
            else -> R.drawable.avatar_robot
        }
        avatar.setImageResource(drawRes)
        avatar.imageTintList = ColorStateList.valueOf(themeAccent())
        avatar.setBackgroundResource(0)
        avatar.clipToOutline = false
        avatar.contentDescription = getString(
            if (isUser) R.string.chat_avatar_user else R.string.chat_avatar_bot
        )
    }

    /**
     * Wendet die (ggf. geänderten) Avatar-Bilder erneut auf alle bereits
     * gerenderten Nachrichten im Chat an. Wird beim Zurückkehren aus den
     * Einstellungen aufgerufen, damit neue eigene Avatar-Bilder auch auf
     * bestehenden Nachrichten sichtbar werden — applyMessageAvatar läuft
     * sonst nur beim Erzeugen einer Nachricht.
     */
    private fun refreshMessageAvatars() {
        for (i in 0 until chatContainer.childCount) {
            val child = chatContainer.getChildAt(i)
            if (child.findViewById<View>(R.id.messageAvatar) == null) continue
            // Rollen-Ableitung aus dem an die Nachrichten-Wurzel gehängten Tag.
            // Nachrichten ohne Rolle (Live-Vorschau) gehören zur User-Seite.
            val msg = child.tag as? ChatMessage
            val isUser = msg == null || msg.role != "assistant"
            applyMessageAvatar(child, isUser)
        }
    }

    /**
     * Anzeigename einer Chat-Nachricht: konfigurierter Name („Avatare & Name“),
     * leer = Standard-Label (Du / Hermes).
     */
    private fun chatLabel(isUser: Boolean): String =
        (if (isUser) config.userName else config.botName).ifBlank {
            getString(if (isUser) R.string.chat_user_label else R.string.chat_hermes_label)
        }

    /**
     * Wendet die (ggf. geänderten) Anzeigenamen erneut auf alle bereits
     * gerenderten Nachrichten an. Wird beim Zurückkehren aus den
     * Einstellungen aufgerufen, damit Namen aus „Avatare & Name“ auch auf
     * bestehenden Nachrichten sichtbar werden — addMessage setzt das Label
     * sonst nur beim Erzeugen einer Nachricht.
     */
    private fun refreshMessageLabels() {
        for (i in 0 until chatContainer.childCount) {
            val child = chatContainer.getChildAt(i)
            val labelView = child.findViewById<TextView>(R.id.messageLabel) ?: continue
            val msg = child.tag as? ChatMessage
            val isUser = msg == null || msg.role != "assistant"
            labelView.text = chatLabel(isUser)
        }
    }

    private fun scrollToBottom() {
        // post: erst nach dem Layout-Pass laufen, damit `child.height` die
        // bereits um die neuen Chunk-Zeilen gewachsene Container-Höhe enthält.
        // (Ohne post war `end` pro Chunk auf Basis der alten Höhe berechnet →
        // letzte Zeilen blieben unten zurück und wurden von der Tastatur verdeckt.)
        chatScroll.post { scrollToBottom0() }
    }

    private fun scrollToBottom0() {
        val s = chatScroll
        if (s.childCount == 0) return
        // Direkt die Scroll-Position ans Ende stellen, statt per
        // fullScroll(FOCUS_DOWN) den Fokus zu navigieren: Bei einem pro Chunk
        // wachsenden TextView springt die Fokus-Navigation in Stufen und reißt
        // die Ansicht hoch/zurück (Flacker-/Spring-Bug). `scrollY` ist die
        // schreibbare Property (siehe isChatAtBottom); ein zu großer Wert wird
        // vom ScrollView auf sein Maximum geklemmt → garantiert unterste Position.
        val end = (s.getChildAt(0).height - s.height + dp(8))
        if (s.scrollY != end) s.scrollY = end
    }

    // ── Markdown-Formatierung ─────────────────────────────────────────────

    /** dp → px (Display-Dichte). */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Delegiert an [MarkdownFormatter] (aus der Activity herausgelöst). */
    private fun formatMarkdown(text: String): Spanned =
        MarkdownFormatter.format(text, resources.displayMetrics.density, getString(R.string.code_copy_hint))

    // ── Tastatureingabe (zweiter Eingabepfad) ─────────────────────────────

    /** Blendet das Eingabefeld ein und öffnet die System-Tastatur. */
    private fun openKeyboardInput() {
        inputRow.visibility = View.VISIBLE
        recordButton.visibility = View.GONE
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        // Verzögert öffnen: Erst NACH dem nächsten Layout-Pass (inputRow sichtbar,
        // recordButton GONE) fokussieren und die Tastatur anzeigen. Ein verlängerter
        // Fokus im selben Frame würde verworfen, ohne Fokus erscheint die Tastatur
        // nicht (bekannter Bug beim Wechsel von Sprach- auf Tastatureingabe).
        // Wichtig (per Emulator-Test bestätigt): Der Fokus-Request auf ein gerade
        // eingeblendetes Feld kann beim ersten Versuch verworfen werden. Zeigt man
        // dann die Tastatur mit SHOW_FORCED trotzdem, ist die IME zwar sichtbar, aber
        // es wird keine InputConnection serviert (focused=false, mServedInputConnection
        // =null) — getippte Zeichen haben keinen Empfänger und erscheinen erst nach
        // einem zweiten Tap. Darum: erst FOKUS verifizieren, dann Tastatur zeigen;
        // den Fokus-Request bis zu 10× im 60-ms-Takt wiederholen, bis er sitzt.
        inputField.postDelayed({
            var attempts = 0
            @Suppress("ObjectLiteralToLambda")
            val tryFocus = object : Runnable {
                override fun run() {
                    if (inputField.isFocused) {
                        imm.showSoftInput(inputField,
                            android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                    } else if (attempts++ < 10) {
                        inputField.requestFocusFromTouch()
                        inputField.postDelayed(this, 60)
                    }
                }
            }
            tryFocus.run()
        }, 100)
        keyboardToggle.contentDescription = getString(R.string.keyboard_toggle_close)
        scrollToBottom()
    }

    /** Blendet das Eingabefeld aus und schließt die System-Tastatur. */
    private fun closeKeyboardInput() {
        inputRow.visibility = View.GONE
        recordButton.visibility = View.VISIBLE
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(inputField.windowToken, 0)
        keyboardToggle.contentDescription = getString(R.string.keyboard_toggle_open)
        inputField.text.clear()
    }

    /** Startet die App direkt im Tastatur-Modus: blendet die Eingabe-Zeile ein,
     *  ohne die System-Tastatur automatisch zu öffnen (der Nutzer tippt zuerst
     *  ins Feld). Bewusst ohne requestFocus()/showSoftInput() im Gegensatz zu
     *  openKeyboardInput(), das für das manuelle Umschalten gedacht ist. */
    private fun showKeyboardRowAtStart() {
        if (inputRow.visibility == View.VISIBLE) return
        inputRow.visibility = View.VISIBLE
        recordButton.visibility = View.GONE
        keyboardToggle.contentDescription = getString(R.string.keyboard_toggle_close)
        scrollToBottom()
    }

    /** Stellt nach Abschluss einer Antwort den Fokus wieder auf das Eingabefeld
     *  her (Tastatur-Modus), damit der Nutzer ohne erneutes Antippen weiterschreiben
     *  kann. Der Senden-Button zieht beim Antippen den Fokus vom EditText ab und
     *  schließt dadurch die Tastatur — hier wird sie wieder geöffnet. Nur wirksam,
     *  wenn die Eingabe-Zeile sichtbar ist (Tastatur-Modus aktiv).
     *  Einstellung "Tastatur im Vordergrund": bei deaktiviertem Schalter wird nur
     *  die Tastatur geschlossen, der Fokus-Zustand des Felds bleibt unangetastet
     *  (requestFocus() wäre ein Fokuswechsel, der die IME erneut öffnet; ein
     *  clearFocus() würde eine Eingabe erzeugen, die erst nach einem zweiten Tap
     *  sichtbar wird). Antippen des Felds öffnet die Tastatur wie gewohnt. */
    private fun restoreKeyboardFocus() {
        if (inputRow.visibility != View.VISIBLE) return
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        if (!config.keyboardInForeground) {
            imm.hideSoftInputFromWindow(inputField.windowToken, 0)
            return
        }
        inputField.postDelayed({
            inputField.requestFocus()
            imm.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    /** Sendet den eingetippten Text als normale Nutzernachricht an Hermes. */
    private fun sendKeyboardInput() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return
        inputField.text.clear()
        maybeAutoResetSession()
        val bubble = addMessage(
            isUser = true,
            label = chatLabel(true),
            text = text,
            showSeparator = (activeSession.history.lastOrNull()?.role == "assistant")
        )
        sendText(text, bubble)
    }

    /** Liest die zuletzt kopierte Text-Zwischenablage und hängt sie an das
     *  Eingabefeld an (Cursor ans Ende), damit der Nutzer noch eine Anweisung
     *  ergänzen und dann über den Send-Button senden kann. Leere/nicht-textuelle
     *  Zwischenablage → kurzer Hinweis statt stiller Ausfall. */
    private fun pasteFromClipboard() {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else {
            ""
        }
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
            return
        }
        // An vorhandenes Eingabe-Ende anhängen; Cursor ans Ende setzen und die
        // System-Tastatur wieder öffnen (wie beim offenen Tastatur-Modus).
        insertIntoInput(text)
        inputField.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    /** Hängt Text an das Ende des Eingabefelds an (Cursor ans Ende). Gemeinsame
     *  Einfüge-Logik für Zwischenablage und geteilten Text (Share-Schleuse). */
    private fun insertIntoInput(text: String) {
        val editable = inputField.text ?: android.text.Editable.Factory.getInstance().newEditable("")
        editable.insert(editable.length, text)
        inputField.setText(editable)
        inputField.setSelection(editable.length)
    }

    /** Empfängt geteilten Text von der Share-Schleuse (ShareActivity). Öffnet
     *  den Tastatur-Modus und fügt den Text ins Eingabefeld ein (editierbar),
     *  konsistent zur Zwischenablage-UX - gesendet wird erst über den Button. */
    private fun handleSharedText() {
        val text = intent.getStringExtra(ShareActivity.EXTRA_SHARED_TEXT)?.trim().orEmpty()
        if (text.isBlank()) return
        // Eingabe-Zeile/Tastatur aktivieren, dann Text anhängen.
        if (inputRow.visibility != View.VISIBLE) openKeyboardInput()
        insertIntoInput(text)
        inputField.requestFocus()
    }

    /** Liest die Versionsnummer aus der PackageInfo (aus build.gradle.kts). */
    private fun versionLabel(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun setState(state: State) {
        statusText.text = state.label(this)
        statusDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(state.color(this))
    }

    /** Zeigt das real genutzte Modell-Paar oben rechts neben dem Status an.
     *  null → kein Override (Gateway entscheidet) → Standard-Anzeige. */
    private fun updateStatusModel(usedPair: Pair<String, String>?) {
        statusModel.text = if (usedPair == null) {
            getString(R.string.model_default_gateway_label)
        } else {
            "(${usedPair.first}/${usedPair.second})"
        }
    }

    // ── Bot-Wechsel (Topbar-Dropdown) ─────────────────────────────────────

    /** Lädt die aktuelle Bot- UND Cloud-Liste neu und richtet Dropdown + Modell-Anzeige
     *  ein. Das Dropdown besteht aus zwei optisch getrennten Gruppen: oben
     *  "Hermes-Profile" (Standard + Bots), darunter "Cloud-KI" (OpenAI-kompatible
     *  Anbieter), jeweils mit Gruppen-Überschrift. */
    private fun rebuildBotSpinner() {
        val bots = config.getBots()
        val clouds = config.getCloudProviders()
        spinnerBots = bots
        spinnerClouds = clouds
        // Start-Profil der aktiven Session hat Vorrang (Fortsetzung mit dem
        // richtigen Bot bzw. Cloud-Anbieter); sonst die gespeicherte ID; unbekannt
        // oder ohne Key/Modell → Standard.
        val sessionBot = if (::activeSession.isInitialized) {
            activeSession.botId?.let { id -> bots.firstOrNull { it.id == id }?.takeIf { it.isUsable() } }
        } else null
        val activeId = config.getActiveBotId()
        activeBot = sessionBot
            ?: bots.firstOrNull { it.id == activeId }?.takeIf { it.isUsable() }
            ?: null
        val sessionCloud = if (::activeSession.isInitialized) {
            activeSession.cloudId?.let { id -> clouds.firstOrNull { it.id == id }?.takeIf { it.isUsable() } }
        } else null
        val activeCloudId = config.getActiveCloudId()
        activeCloud = sessionCloud
            ?: clouds.firstOrNull { it.id == activeCloudId }?.takeIf { it.isUsable() }
            ?: null
        // Beide Zieltypen schließen sich gegenseitig aus: Ist ein Cloud-Sender aktiv,
        // darf kein Bot gesetzt sein (und umgekehrt).
        if (activeBot != null) activeCloud = null
        else if (activeCloud != null) activeBot = null

        // Spinner-Einträge mit zweigeteilter Struktur (Header-Zeilen sind nicht wählbar):
        //   0        = Header "Hermes-Profile"
        //   1        = Standard
        //   2..      = Bots
        //   danach   = Header "Cloud-KI" (nur falls Clouds existieren)
        //   danach   = Cloud-Anbieter
        val labels = ArrayList<String>()
        val enabled = ArrayList<Boolean>()
        val isHeader = ArrayList<Boolean>()
        labels.add(getString(R.string.bot_default_label)) // Platzhalter, wird unten ersetzt
        enabled.add(true)
        isHeader.add(true)
        labels[0] = "≡ " + getString(R.string.bots_section_title) + " —"
        labels.add(getString(R.string.bot_default_label)); enabled.add(true); isHeader.add(false)
        for (b in bots) {
            labels.add(b.name.ifBlank { getString(R.string.bot_name_hint) })
            enabled.add(b.isUsable()); isHeader.add(false)
        }
        if (clouds.isNotEmpty()) {
            labels.add("≡ " + getString(R.string.cloud_group_header) + " —")
            enabled.add(false); isHeader.add(true)
            for (c in clouds) {
                labels.add(c.name.ifBlank { c.providerName() })
                enabled.add(c.isUsable()); isHeader.add(false)
            }
        }
        botSpinner.adapter = BotSpinnerAdapter(labels, enabled, isHeader)

        // Auswahl auf den aktiven Bot/Cloud setzen; Listener ist noch nicht gesetzt.
        // Position: Header bei 0, Standard bei 1, Bots ab 2; Cloud-Header/Clouds
        // hängen danach an.
        val pos = when {
            activeBot != null -> bots.indexOfFirst { it.id == activeBot!!.id }.let { 2 + it }
            activeCloud != null -> {
                val cloudStart = 2 + bots.size + 1 // nach Cloud-Header
                cloudStart + clouds.indexOfFirst { it.id == activeCloud!!.id }
            }
            else -> 1 // Standard
        }
        botSpinner.setSelection(pos)

        botSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (spinnerProgrammatic) return
                // Layout: 0=Header Hermes, 1=Standard, 2..=Bots, dann Cloud-Header,
                // dann Clouds. Header-Zeilen sind nicht vom Nutzer wählbar; falls der
                // Spinner sie trotzdem (z. B. programmatisch) anzeigt, zurücksetzen.
                val cloudHeaderPos = if (spinnerClouds.isNotEmpty()) 2 + spinnerBots.size else -1
                val cloudStart = cloudHeaderPos + 1
                when {
                    position == 0 || position == cloudHeaderPos -> reselectActive()
                    position == 1 -> activateBot(null)
                    position >= 2 && position < (if (cloudHeaderPos < 0) 2 + spinnerBots.size else cloudHeaderPos) -> {
                        val b = spinnerBots.getOrNull(position - 2)
                        if (b == null || !b.isUsable()) {
                            reselectActive()
                            Toast.makeText(this@MainActivity, R.string.bot_spinner_disabled, Toast.LENGTH_SHORT).show()
                        } else activateBot(b)
                    }
                    position >= cloudStart -> {
                        val c = spinnerClouds.getOrNull(position - cloudStart)
                        if (c == null || !c.isUsable()) {
                            reselectActive()
                            Toast.makeText(this@MainActivity, R.string.cloud_spinner_disabled, Toast.LENGTH_SHORT).show()
                        } else activateCloud(c)
                    }
                    else -> reselectActive()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateModelForActive()
    }

    /** Stellt die Spinner-Auswahl auf den aktuell aktiven Bot bzw. Cloud-Anbieter
     *  (oder Standard) zurück. */
    private fun reselectActive() {
        val pos = when {
            activeBot != null -> {
                val i = spinnerBots.indexOfFirst { it.id == activeBot!!.id }
                if (i < 0) 1 else 2 + i
            }
            activeCloud != null -> {
                val cloudHeaderPos = 2 + spinnerBots.size
                val cloudStart = cloudHeaderPos + 1
                val i = spinnerClouds.indexOfFirst { it.id == activeCloud!!.id }
                if (i < 0) 1 else cloudStart + i
            }
            else -> 1 // Standard
        }
        spinnerProgrammatic = true
        botSpinner.setSelection(pos)
        spinnerProgrammatic = false
    }

    /** Setzt den aktiven Bot (null = Standard), persistiert die Wahl und eröffnet
     *  bei einem tatsächlichen Wechsel eine neue Session. Ein aktiver Cloud-Kanal
     *  wird dabei deaktiviert. */
    private fun activateBot(bot: BotProfile?) {
        if (bot?.id == activeBot?.id && activeCloud == null) return
        activeCloud = null
        activeBot = bot
        config.setActiveBotId(bot?.id ?: "")
        config.setActiveCloudId("")
        updateModelForActive()
        switchTargetSession()
    }

    /** Setzt den aktiven Cloud-KI-Anbieter, persistiert die Wahl und eröffnet einen
     *  neuen zustandslosen Chat (kein Gateway-Session-Key, kein Sync). Ein aktiver
     *  Bot wird dabei deaktiviert. */
    private fun activateCloud(cloud: de.hermi.voice.config.CloudProvider?) {
        if (cloud?.id == activeCloud?.id && activeBot == null) return
        activeBot = null
        activeCloud = cloud
        config.setActiveBotId("")
        config.setActiveCloudId(cloud?.id ?: "")
        updateModelForActive()
        switchTargetSession()
    }

    /** Gemeinsame Folgeaktion eines Zielwechsels: neue Session, falls die aktive
     *  bereits Inhalt hat, sonst die bestehende Session dem neuen Ziel zuordnen.
     *  Achtung: Für Cloud-Kanäle wird die Session-Start-Id nur im Speicher gesetzt
     *  und NICHT im Store persistiert (zustandslose Chats). */
    private fun switchTargetSession() {
        if (activeSession.history.isNotEmpty()) newSession()
        else {
            activeSession.botId = activeBot?.id
            activeSession.cloudId = activeCloud?.id
        }
    }

    /** Modell-Anzeige rechts oben auf den aktiven Bot, den aktiven Cloud-Anbieter
     *  oder das globale Primärmodell setzen. */
    private fun updateModelForActive() {
        val cloud = activeCloud
        if (cloud != null) {
            updateStatusModel(cloud.primaryPair())
            return
        }
        val bot = activeBot
        updateStatusModel(bot?.primaryModelPair() ?: config.primaryModelPair())
    }

    /** Löst das Start-Profil einer Session in einen Bot auf. Gespeicherte botId
     *  (vorhanden, nutzbar) ⇒ dieser Bot; sonst (gelöscht, ohne Key, alte Session
     *  ohne botId) ⇒ null = Standard-Agent. */
    private fun resolveSessionBot(session: ChatSession): BotProfile? {
        val id = session.botId
        if (id == null) return null
        return config.getBots().firstOrNull { it.id == id }?.takeIf { it.isUsable() }
    }

    /** Dunkler Spinner-Adapter mit zweigeteilten Gruppen: "Hermes-Profile" oben,
     *  "Cloud-KI" darunter. Header-Zeilen (isHeader) sind nur optische Überschriften:
     *  fett, maskiert gedimmt und im Dropdown zusätzlich als nicht wählbar markiert.
     *  Bots/Clouds ohne API-Key werden ausgegraut (aber per Handler unselektierbar). */
    private inner class BotSpinnerAdapter(
        private val labels: List<String>,
        private val enabled: List<Boolean>,
        private val isHeader: List<Boolean>
    ) : BaseAdapter() {
        override fun getCount(): Int = labels.size
        override fun getItem(position: Int): Any = labels[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun isEnabled(position: Int): Boolean = !isHeader[position] && enabled[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.spinner_item_bot, parent, false)
            v.findViewById<TextView>(R.id.spinnerItemText).apply {
                text = labels[position]
                if (isHeader[position]) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(themeAccent())
                    alpha = 0.85f
                } else {
                    setTypeface(typeface, android.graphics.Typeface.NORMAL)
                    setTextColor(android.graphics.Color.argb(0xFF, 0xE0, 0xE0, 0xE0))
                    alpha = if (enabled[position]) 1f else 0.4f
                }
            }
            return v
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.spinner_dropdown_bot, parent, false)
            v.findViewById<TextView>(R.id.spinnerDropdownText).apply {
                text = labels[position]
                if (isHeader[position]) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(themeAccent())
                    setBackgroundColor(getColor(R.color.surface_light))
                } else {
                    setTypeface(typeface, android.graphics.Typeface.NORMAL)
                    setTextColor(android.graphics.Color.argb(0xFF, 0xE0, 0xE0, 0xE0))
                    alpha = if (enabled[position]) 1f else 0.4f
                }
            }
            return v
        }
    }

    private enum class State(val labelRes: Int, val colorRes: Int) {
        READY(R.string.state_ready, R.color.accent),
        LISTENING(R.string.state_listening, R.color.state_listening),
        RECOGNIZING(R.string.state_recognizing, R.color.state_recognizing),
        SENDING(R.string.state_sending, R.color.state_sending),
        SENT(R.string.state_sent, R.color.state_sent),
        ERROR(R.string.state_error, R.color.state_error),
        MODEL_MISSING(R.string.model_missing, R.color.state_recognizing),
        // Bugfix 1.0.1: Modell wird im Hintergrund geladen (kein ANR mehr auf dem
        // UI-Thread) — der Nutzer sieht, warum die Aufnahme noch nicht bereit ist.
        MODEL_LOADING(R.string.model_loading, R.color.state_recognizing);

        fun label(ctx: MainActivity): String = ctx.getString(labelRes)
        fun color(ctx: MainActivity): Int =
            if (this == READY || this == SENT) ctx.themeAccent()
            else ctx.getColor(colorRes)
    }

    override fun onDestroy() {
        // Laufende Streaming-Anfrage beim Beenden abbrechen, damit der Thread
        // nicht weiter in die (zerstörte) Activity schreibt.
        activeStreamCancel?.cancel()
        StreamKeepAliveService.stop(this)
        // Sprachausgabe-Engine freigeben und TTS-Dienst beenden.
        if (speechOutput != null) speechOutput!!.shutdown()
        super.onDestroy()
        val rec = recognizer
        recognizer = null
        recording = false
        // Recognizer/Stream erst freigeben, NACHDEM der Audio-Thread beendet ist
        // (sonst feed() auf freigegebenes natives Objekt → Absturz), und unter Lock,
        // damit ein parallel laufender Erkennungs-Thread nicht dazwischenfunkt.
        audioSource.stop {
            synchronized(recognizerLock) {
                recognizerReleased = true
                val st = streamRef
                streamRef = null
                try { st?.release() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
            }
        }
    }
}