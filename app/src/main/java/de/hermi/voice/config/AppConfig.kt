package de.hermi.voice.config

import android.content.Context
import android.content.SharedPreferences
import de.hermi.voice.AppLocale
import de.hermi.voice.R
import de.hermi.voice.crypto.CryptoManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Zugriff auf die lokale Konfiguration (privater App-Speicher, kein hartcodiertes Secret).
 * Server-URL + API-Key des Hermes-API-Servers werden vom Nutzer in den Einstellungen eingetragen.
 * Der API-Key wird AES-256-GCM-verschlüsselt abgelegt (Schlüssel im Android-Keystore),
 * sämtliche übrigen Werte sind unkritisch und bleiben Klartext.
 */
class AppConfig(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hermi_config", Context.MODE_PRIVATE)

    // Bugfix 1.0.1: merkt sich, dass ein gespeicherter Secret-Wert nicht mehr
    // entschlüsselbar war (Keystore-Key weg, z. B. nach Restore/Werksreset). Die UI
    // fragt das einmalig ab und zeigt einen Hinweis, statt still "nicht konfiguriert"
    // zu wirken, obwohl ein Backup existiert.
    private val unreadableSecretSeen = java.util.concurrent.atomic.AtomicBoolean(false)

    /** true, sobald mindestens ein Secret nicht mehr entschlüsselbar war. */
    fun wasSecretUnreadable(): Boolean = unreadableSecretSeen.get()

    /**
     * Prüft beim App-Start alle gespeicherten Secrets (globaler Key, Bot-Keys,
     * Cloud-Keys) auf Entschlüsselbarkeit. Die Getter setzen das Warn-Flag selbst.
     * Liefert true, wenn mindestens ein Wert nicht mehr lesbar ist — dann zeigt die
     * UI einmalig einen Hinweis statt still „nicht konfiguriert“ zu wirken.
     */
    fun detectUnreadableSecrets(): Boolean {
        apiKey
        getBots()
        getCloudProviders()
        return unreadableSecretSeen.get()
    }

    init {
        // Bestehende Bestandsdaten (Klartext aus Versionen vor 1.21) einmalig verschlüsseln.
        migrateSecretsIfNeeded()
    }

    // ── App-Sprache (nur App-intern, AppCompat-Weg) ────────────────────────
    // "auto" = System folgen (Englisch als Fallback), "de"/"en" = erzwingen.
    var uiLanguageMode: String
        get() = prefs.getString(KEY_UI_LANGUAGE, AppLocale.MODE_AUTO) ?: AppLocale.MODE_AUTO
        set(value) = prefs.edit().putString(KEY_UI_LANGUAGE, value).apply()

    // ── Aktive Spracherkennungs-Sprache (gekoppelt an die UI-Sprache) ─────
    // "de" = Modell-Paket `models/de`, "en" = `models/en`. Wird beim
    // Sprachwechsel mit umgestellt und steuert ModelManager + MainActivity.
    var activeSttLanguage: String
        get() = prefs.getString(KEY_ACTIVE_STT_LANGUAGE, "de") ?: "de"
        set(value) = prefs.edit().putString(KEY_ACTIVE_STT_LANGUAGE, value).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    /** Öffentliche Gateway-Domain für den externen Zugriff auf Profile/Bots über
     *  Pfad-Routing (…/p/<profil>). Leer → es greift die globale Server-URL. */
    var externalDomain: String
        get() = prefs.getString(KEY_EXTERNAL_DOMAIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EXTERNAL_DOMAIN, value.trim()).apply()

    /** Externe Domain für Pfad-Routing; fällt auf die globale Server-URL zurück. */
    fun resolvedExternalDomain(): String = externalDomain.ifBlank { serverUrl }

    var apiKey: String
        get() = try {
            val raw = prefs.getString(KEY_API_KEY, "") ?: ""
            if (raw.isEmpty()) "" else CryptoManager.decrypt(raw)
        } catch (e: Exception) {
            // Nicht (mehr) entschlüsselbar (anderer Keystore z. B.) → als leer
            // behandeln, aber für den UI-Hinweis vermerken.
            unreadableSecretSeen.set(true)
            ""
        }
        set(value) {
            val v = value.trim()
            prefs.edit()
                .putString(KEY_API_KEY, if (v.isEmpty()) "" else CryptoManager.encrypt(v))
                .apply()
        }

    var messagePrefix: String
        get() = prefs.getString(KEY_PREFIX, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PREFIX, value.trim()).apply()

    /** Grüne Trennlinie zwischen den Frage-Antwort-Blöcken anzeigen. Deaktiviert
     *  wird die Linie nur ausgeblendet (INVISIBLE), der Block-Abstand bleibt. */
    var showMessageSeparator: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SEPARATOR, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SEPARATOR, value).apply()

    /** Nach längerer Inaktivität automatisch eine neue Konversation starten,
     *  damit der mitgeschickte Kontext nicht unbegrenzt wächst. Der Reset erfolgt
     *  still, nur eine kurze Systemzeile erscheint im Chat. */
    var autoNewSession: Boolean
        get() = prefs.getBoolean(KEY_AUTO_NEW_SESSION, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_NEW_SESSION, value).apply()

    /** Vibration/Haptik-Feedback beim Aufnahme-Start/Stopp (Standard: an). */
    var hapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()

    /** Antwort nach dem Senden per Mikrofon automatisch vorlesen
     *  (systemweite Sprachausgabe). Standard: aus (still, bis aktiviert). */
    var ttsReadAnswer: Boolean
        get() = prefs.getBoolean(KEY_TTS_READ_ANSWER, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_READ_ANSWER, value).apply()

    /** Startmodus der App: true = Sprach-Eingabe (Mic), false = Tastatur-Eingabe.
     *  Default bei Erstinstallation: Sprach-Eingabe. */
    var startInputMode: Boolean
        get() = prefs.getBoolean(KEY_START_INPUT_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_START_INPUT_MODE, value).apply()

    /** Ob die Tastatur nach einer Antwort im Vordergrund bleibt (Standard: an =
     *  bisheriges Verhalten). Deaktiviert: Tastatur wird nach der Antwort
     *  ausgeblendet, das Eingabefeld bleibt fokussiert. */
    var keyboardInForeground: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD_AFTER_ANSWER, true)
        set(value) = prefs.edit().putBoolean(KEY_KEYBOARD_AFTER_ANSWER, value).apply()

    /** Benachrichtigung, sobald die KI-Antwort fertig ist, während die App nicht
     *  im Vordergrund ist. Standard: an. */
    var notifyOnAnswer: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ON_ANSWER, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_ON_ANSWER, value).apply()

    /** Gewählter Akzentfarben-Name (Enum-Name, Default Grün). Beim App-Start
     *  wird das zum Akzent gehörende Overlay-Theme per setTheme() angewendet. */
    var accentColorName: String
        get() = prefs.getString(KEY_ACCENT_COLOR, AccentColor.GREEN.name) ?: AccentColor.GREEN.name
        set(value) =
            prefs.edit().putString(KEY_ACCENT_COLOR, value).apply()

    /** Aktueller Akzent als Enum (unbekannte/leere Werte → Grün). */
    fun accentColor(): AccentColor =
        runCatching { AccentColor.valueOf(accentColorName) }
            .getOrDefault(AccentColor.GREEN)

    /** Theme-Ressource des aktuellen Akzents (für setTheme vor setContentView). */
    fun accentTheme(): Int = accentColor().themeRes

    /** Chat-Hintergrund-Typ (Enum-Name). "default" = bisherige Einfärbung (kein Bild),
     *  "gradient" = Farbverlauf, "image" = gewähltes Bild. Default: weiterhin die
     *  aktuelle Einfärbung, also kein Bild. */
    var chatBackgroundName: String
        get() = prefs.getString(KEY_CHAT_BACKGROUND, CHAT_BG_DEFAULT) ?: CHAT_BG_DEFAULT
        set(value) = prefs.edit().putString(KEY_CHAT_BACKGROUND, value).apply()

    /** Aktueller Hintergrund-Typ als Enum (unbekannte Werte → Default). */
    fun chatBackground(): ChatBackground =
        runCatching { ChatBackground.valueOf(chatBackgroundName) }
            .getOrDefault(ChatBackground.DEFAULT)

    /** Id des gewählten Farbverlaufs (siehe ChatBackground.gradients() im Settings). */
    var chatGradientId: String
        get() = prefs.getString(KEY_CHAT_GRADIENT, "bg_gradient_teal") ?: "bg_gradient_teal"
        set(value) = prefs.edit().putString(KEY_CHAT_GRADIENT, value).apply()

    /** Absoluter Pfad des gewählten Hintergrund-Bilds (leer = keins gewählt). */
    var chatImagePath: String
        get() = prefs.getString(KEY_CHAT_IMAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHAT_IMAGE, value).apply()

    /** Ablageort des Chat-Hintergrund-Bildes im internen Speicher. */
    fun chatBackgroundImageFile(): File = File(appContext.filesDir, "chat_bg_image.png")

    /** Absoluter Pfad des eigenen Avatars für Nutzer-Nachrichten ("Du"), leer = Standard. */
    var userAvatarPath: String
        get() = prefs.getString(KEY_USER_AVATAR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_AVATAR, value).apply()

    /** Absoluter Pfad des eigenen Avatars für Bot-Nachrichten ("Hermes"), leer = Standard. */
    var botAvatarPath: String
        get() = prefs.getString(KEY_BOT_AVATAR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOT_AVATAR, value).apply()

    /** Ablageort des eigenen Nutzer-Avatars im internen Speicher. */
    fun userAvatarFile(): File = File(appContext.filesDir, "avatar_user.png")

    /** Ablageort des eigenen Bot-Avatars im internen Speicher. */
    fun botAvatarFile(): File = File(appContext.filesDir, "avatar_bot.png")

    /** Anzeigename für Nutzer-Nachrichten (Standard: "Du"), leer = Standard. */
    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    /** Anzeigename für Bot-Nachrichten (Standard: "Hermes"), leer = Standard. */
    var botName: String
        get() = prefs.getString(KEY_BOT_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOT_NAME, value).apply()

    /** Verfügbare Farbverläufe (Name → Drawable-Ressource) für den Chat-Hintergrund. */
    fun chatGradients(): Map<String, Int> = mapOf(
        "bg_gradient_teal" to R.drawable.chat_bg_gradient_teal,
        "bg_gradient_green" to R.drawable.chat_bg_gradient_green,
        "bg_gradient_blue" to R.drawable.chat_bg_gradient_blue,
        "bg_gradient_warm" to R.drawable.chat_bg_gradient_warm,
    )

    /** Pausen-Schwelle in Minuten, nach der ein Auto-Reset greift. */
    var autoNewSessionMinutes: Int
        get() = prefs.getInt(KEY_AUTO_NEW_SESSION_MINUTES, DEFAULT_AUTO_NEW_SESSION_MINUTES)
        set(value) = prefs.edit().putInt(KEY_AUTO_NEW_SESSION_MINUTES, value).apply()

    /** Verstärkung des Mikrofonsignals vor der Spracherkennung (1.0 = unverändert). */
    var micGain: Float
        get() = prefs.getFloat(KEY_MIC_GAIN, DEFAULT_MIC_GAIN)
        set(value) = prefs.edit().putFloat(KEY_MIC_GAIN, value.coerceIn(MIN_MIC_GAIN, MAX_MIC_GAIN)).apply()

    /** Mindest-Anzahl Nachlauf-Blöcke (100 ms) nach dem Loslassen. */
    var minTailBlocks: Int
        get() = prefs.getInt(KEY_MIN_TAIL, DEFAULT_MIN_TAIL)
        set(value) = prefs.edit().putInt(KEY_MIN_TAIL, value.coerceIn(MIN_TAIL, MAX_TAIL)).apply()

    /** Anzahl anhaltend stiller Blöcke (100 ms) bis „Rede zu Ende“. */
    var silenceBlocks: Int
        get() = prefs.getInt(KEY_SILENCE_BLOCKS, DEFAULT_SILENCE_BLOCKS)
        set(value) = prefs.edit().putInt(KEY_SILENCE_BLOCKS, value.coerceIn(MIN_SILENCE, MAX_SILENCE)).apply()

    /** Harte Obergrenze der Nachlauf-Blöcke (100 ms). */
    var maxTailBlocks: Int
        get() = prefs.getInt(KEY_MAX_TAIL, DEFAULT_MAX_TAIL)
        set(value) = prefs.edit().putInt(KEY_MAX_TAIL, value.coerceIn(MIN_MAX_TAIL, MAX_MAX_TAIL)).apply()

    /** Stille-Schwelle relativ zum Sprech-Peak (z. B. 0.06 = 6 %). */
    var silenceFactor: Float
        get() = prefs.getFloat(KEY_SILENCE_FACTOR, DEFAULT_SILENCE_FACTOR)
        set(value) = prefs.edit().putFloat(KEY_SILENCE_FACTOR, value.coerceIn(MIN_SILENCE_FACTOR, MAX_SILENCE_FACTOR)).apply()

    /** Absoluter Stille-Floor (Fallback für sehr leise Räume). */
    var silenceFloorAbs: Float
        get() = prefs.getFloat(KEY_SILENCE_FLOOR, DEFAULT_SILENCE_FLOOR)
        set(value) = prefs.edit().putFloat(KEY_SILENCE_FLOOR, value.coerceIn(MIN_SILENCE_FLOOR, MAX_SILENCE_FLOOR)).apply()

    /** Zeitpunkt (epochMillis) des letzten ERFOLGREICHEN Modell-Update-Checks.
     *  Wird NUR bei Erfolg gesetzt — ein fehlgeschlagener Check (offline etc.)
     *  verschiebt das nächste Fenster nicht. */
    var lastModelCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_MODEL_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_MODEL_CHECK, value).apply()

    // ── KI-Modell-Auswahl (Antwort) ─────────────────────────────────────────
    // Primäres Modell-Paar (Provider + Modell-ID). Leer = „Standard (Agent-Default)",
    // d. h. es wird hermes-agent ohne provider/model-Override verwendet.
    var primaryProvider: String
        get() = prefs.getString(KEY_PRIMARY_PROVIDER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PRIMARY_PROVIDER, value.trim()).apply()

    var primaryModel: String
        get() = prefs.getString(KEY_PRIMARY_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PRIMARY_MODEL, value.trim()).apply()

    // Fallback-Modell-Paar. Leer = kein Fallback.
    var fallbackProvider: String
        get() = prefs.getString(KEY_FALLBACK_PROVIDER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FALLBACK_PROVIDER, value.trim()).apply()

    var fallbackModel: String
        get() = prefs.getString(KEY_FALLBACK_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FALLBACK_MODEL, value.trim()).apply()

    /** true, sobald ein primäres Modell konfiguriert ist. */
    fun hasPrimaryModel(): Boolean = primaryProvider.isNotBlank() && primaryModel.isNotBlank()

    /** true, sobald ein Fallback-Modell konfiguriert ist. */
    fun hasFallbackModel(): Boolean = fallbackProvider.isNotBlank() && fallbackModel.isNotBlank()

    /** Das vollständige Modell-Paar als Pair(provider, model) oder null bei Standard. */
    fun primaryModelPair(): Pair<String, String>? =
        if (hasPrimaryModel()) Pair(primaryProvider, primaryModel) else null

    fun fallbackModelPair(): Pair<String, String>? =
        if (hasFallbackModel()) Pair(fallbackProvider, fallbackModel) else null

    /** Anzahl aufeinanderfolgender Primär-Fehlschläge (primär scheitert, Fallback
     *  antwortet), nach denen künftige Anfragen DIESER Session direkt ans
     *  Fallback-Modell gehen, statt jedes Mal ~30 s auf das nicht erreichbare
     *  Primärmodell zu warten. Nur In-Memory wirksam; Bereich 1–5, Default 2. */
    var primaryFailThreshold: Int
        get() = prefs.getInt(KEY_PRIMARY_FAIL_THRESHOLD, DEFAULT_PRIMARY_FAIL_THRESHOLD)
        set(value) = prefs.edit()
            .putInt(KEY_PRIMARY_FAIL_THRESHOLD, value.coerceIn(MIN_PRIMARY_FAIL_THRESHOLD, MAX_PRIMARY_FAIL_THRESHOLD))
            .apply()

    /** Timeout bis zum ersten Token der Antwort (10 s .. 1 min). */
    var firstTokenTimeoutSec: Int
        get() = prefs.getInt(KEY_FIRST_TOKEN_TIMEOUT_SEC, DEFAULT_FIRST_TOKEN_TIMEOUT_SEC)
        set(value) = prefs.edit()
            .putInt(KEY_FIRST_TOKEN_TIMEOUT_SEC, value.coerceIn(MIN_FIRST_TOKEN_TIMEOUT_SEC, MAX_FIRST_TOKEN_TIMEOUT_SEC))
            .apply()

    /** Timeout für die gesamte Antwort/Stream (1 .. 15 min). */
    var streamTimeoutMin: Int
        get() = prefs.getInt(KEY_STREAM_TIMEOUT_MIN, DEFAULT_STREAM_TIMEOUT_MIN)
        set(value) = prefs.edit()
            .putInt(KEY_STREAM_TIMEOUT_MIN, value.coerceIn(MIN_STREAM_TIMEOUT_MIN, MAX_STREAM_TIMEOUT_MIN))
            .apply()

    /** Setzt die Modell-Auswahl über den vollständigen Satz auf einmal zurück
     *  (primär + Fallback → Standard). */
    fun resetModelSelection() {
        primaryProvider = ""
        primaryModel = ""
        fallbackProvider = ""
        fallbackModel = ""
    }

    // ── Bot-Profile (mehrere Hermes-Bots) ────────────────────────────────
    // Jeder Bot besitzt einen eigenen API-Key (verschlüsselt abgelegt) und eine
    // eigene Modellwahl. Die Server-URL ist geteilt (globales Feld). Gespeichert
    // wird die Liste als JSON-Array in einem Prefs-Key.
    fun getBots(): List<BotProfile> {
        val raw = prefs.getString(KEY_BOTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(BotProfile(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", ""),
                        apiKey = decryptOrEmpty(o.optString("apiKey", "")),
                        serverBase = o.optString("serverBase", ""),
                        primaryProvider = o.optString("primaryProvider", ""),
                        primaryModel = o.optString("primaryModel", ""),
                        fallbackProvider = o.optString("fallbackProvider", ""),
                        fallbackModel = o.optString("fallbackModel", "")
                    ))
                }
            }
        } catch (e: Exception) {
            emptyList() // beschädigte Liste → leer behandeln, kein Absturz
        }
    }

    /** Speichert die komplette Bot-Liste; API-Keys werden dabei verschlüsselt. */
    fun saveBots(bots: List<BotProfile>) {
        val arr = JSONArray()
        for (b in bots) {
            val o = JSONObject()
            o.put("id", b.id)
            o.put("name", b.name)
            o.put("apiKey", if (b.apiKey.isEmpty()) "" else CryptoManager.encrypt(b.apiKey))
            o.put("serverBase", b.serverBase)
            o.put("primaryProvider", b.primaryProvider)
            o.put("primaryModel", b.primaryModel)
            o.put("fallbackProvider", b.fallbackProvider)
            o.put("fallbackModel", b.fallbackModel)
            arr.put(o)
        }
        prefs.edit().putString(KEY_BOTS, arr.toString()).apply()
    }

    /** ID des aktuell aktiven Bots (leerer String = Standard-Agent). */ 
    fun getActiveBotId(): String = prefs.getString(KEY_ACTIVE_BOT, "") ?: ""

    fun setActiveBotId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_BOT, id).apply()
    }

    // ── Cloud-KI-Anbieter (mehrere OpenAI-kompatible Dienste) ────────────
    // Jeder Anbieter besitzt einen eigenen API-Key (verschlüsselt abgelegt) und
    // je ein primäres/Fallback-Modell. Die Basis-URL kommt fix aus der
    // Provider-Tabelle (CloudProviders), kein Nutzer-URL-Feld. Gespeichert wird
    // die Liste als JSON-Array in einem Prefs-Key (unbegrenzt, wie die Bots).
    fun getCloudProviders(): List<CloudProvider> {
        val raw = prefs.getString(KEY_CLOUD_PROVIDERS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(CloudProvider(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", ""),
                        provider = o.optString("provider", ""),
                        apiKey = decryptOrEmpty(o.optString("apiKey", "")),
                        primaryModel = o.optString("primaryModel", ""),
                        fallbackModel = o.optString("fallbackModel", ""),
                        tools = readToolSlugs(o.optJSONArray("tools"))
                    ))
                }
            }
        } catch (e: Exception) {
            emptyList() // beschädigte Liste → leer behandeln, kein Absturz
        }
    }

    /** Speichert die komplette Cloud-Provider-Liste; API-Keys werden verschlüsselt. */
    fun saveCloudProviders(providers: List<CloudProvider>) {
        val arr = JSONArray()
        for (p in providers) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("provider", p.provider)
            o.put("apiKey", if (p.apiKey.isEmpty()) "" else CryptoManager.encrypt(p.apiKey))
            o.put("primaryModel", p.primaryModel)
            o.put("fallbackModel", p.fallbackModel)
            o.put("tools", JSONArray(p.tools))
            arr.put(o)
        }
        prefs.edit().putString(KEY_CLOUD_PROVIDERS, arr.toString()).apply()
    }

    /** ID des aktuell aktiven Cloud-Anbieters (leerer String = Standard-Agent/Bot). */
    fun getActiveCloudId(): String = prefs.getString(KEY_ACTIVE_CLOUD, "") ?: ""

    fun setActiveCloudId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_CLOUD, id).apply()
    }

    private fun decryptOrEmpty(raw: String): String =
        if (raw.isEmpty()) "" else
            try {
                CryptoManager.decrypt(raw)
            } catch (e: Exception) {
                // Nicht (mehr) entschlüsselbar → als leer behandeln, aber für den
                // einmaligen UI-Hinweis vermerken (Bugfix 1.0.1).
                unreadableSecretSeen.set(true)
                ""
            }

    /** Liest die aktivierten Tool-Slugs eines Cloud-Providers aus dem JSON-Array
     *  (fehlt/leer → leere Menge; falsewerte Slugs werden herausgefiltert). */
    private fun readToolSlugs(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    /** true, wenn das wöchentliche Prüfzeitfenster abgelaufen ist (bzw. noch
     *  nie erfolgreich geprüft wurde) — dann darf der Start-Check laufen. */
    fun shouldCheckModelUpdate(): Boolean =
        System.currentTimeMillis() - lastModelCheckMillis >= MODEL_CHECK_INTERVAL_MS

    /** Bündelt die Aufnahme-Feintuning-Werte in einem Objekt für die Audio-Schicht. */
    fun recordingParams(): RecordingParams = RecordingParams(
        gain = micGain,
        minTailBlocks = minTailBlocks,
        silenceBlocks = silenceBlocks,
        maxTailBlocks = maxTailBlocks,
        silenceFactor = silenceFactor,
        silenceFloorAbs = silenceFloorAbs
    )

    /** Setzt alle Aufnahme-Feintuning-Werte (inkl. Gain) auf die bewährten Defaults zurück. */
    fun resetRecordingDefaults() {
        micGain = DEFAULT_MIC_GAIN
        minTailBlocks = DEFAULT_MIN_TAIL
        silenceBlocks = DEFAULT_SILENCE_BLOCKS
        maxTailBlocks = DEFAULT_MAX_TAIL
        silenceFactor = DEFAULT_SILENCE_FACTOR
        silenceFloorAbs = DEFAULT_SILENCE_FLOOR
    }

    fun isConfigured(): Boolean = serverUrl.isNotBlank() && apiKey.isNotBlank()

    // --- Favoriten der KI-Modell-Auswahl (pro Geltungsbereich getrennt) ---
    // Scope-Schlüssel: MODEL_SCOPE_STANDARD für die Standard-Auswahl, sonst bot.id.
    // Primär- und Fallback-Auswahl desselben Bots teilen sich denselben Bereich.
    private fun favoriteKey(scope: String) = "favorite_models_$scope"

    fun getModelFavorites(scope: String): Set<String> =
        prefs.getStringSet(favoriteKey(scope), emptySet()) ?: emptySet()

    fun setModelFavorites(scope: String, keys: Set<String>) {
        prefs.edit().putStringSet(favoriteKey(scope), keys.toSet()).apply()
    }

    fun isModelFavorite(scope: String, provider: String, model: String): Boolean =
        "$provider/$model" in getModelFavorites(scope)

    fun toggleModelFavorite(scope: String, provider: String, model: String) {
        val key = "$provider/$model"
        val set = getModelFavorites(scope).toMutableSet()
        if (!set.remove(key)) set.add(key) // Umschalten: war Favorit → entfernen, sonst hinzufügen
        setModelFavorites(scope, set)
    }

    /**
     * Migriert den API-Key aus früheren Versionen (gelesen im Klartext) einmalig zu
     * verschlüsseltem Speichern. Läuft im init, ist idempotent über das Migrations-Flag.
     */
    private fun migrateSecretsIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val raw = prefs.getString(KEY_API_KEY, "") ?: ""
        val newVal = when {
            raw.isEmpty() -> ""                       // nichts zu migrieren
            CryptoManager.isEncryptedText(raw) -> raw // bereits verschlüsselt (Doppelstart-Sicherheit)
            else -> CryptoManager.encrypt(raw)        // alter Klartext → verschlüsseln
        }
        prefs.edit()
            .putString(KEY_API_KEY, newVal)
            .putBoolean(KEY_MIGRATED, true)
            .apply()
    }

    companion object {
        private const val KEY_UI_LANGUAGE = "ui_language_mode"
        private const val KEY_ACTIVE_STT_LANGUAGE = "active_stt_language"
        private const val KEY_URL = "server_url"
        private const val KEY_EXTERNAL_DOMAIN = "external_domain"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MIGRATED = "crypto_migrated"
        private const val KEY_PREFIX = "message_prefix"
        private const val KEY_MIC_GAIN = "mic_gain"
        private const val KEY_MIN_TAIL = "min_tail_blocks"
        private const val KEY_SILENCE_BLOCKS = "silence_blocks"
        private const val KEY_MAX_TAIL = "max_tail_blocks"
        private const val KEY_SILENCE_FACTOR = "silence_factor"
        private const val KEY_SILENCE_FLOOR = "silence_floor_abs"
        private const val KEY_SHOW_SEPARATOR = "show_message_separator"
        private const val KEY_AUTO_NEW_SESSION = "auto_new_session"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        private const val KEY_TTS_READ_ANSWER = "tts_read_answer"
        private const val KEY_START_INPUT_MODE = "start_input_mode"
        private const val KEY_KEYBOARD_AFTER_ANSWER = "keyboard_after_answer"
        private const val KEY_NOTIFY_ON_ANSWER = "notify_on_answer"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_CHAT_BACKGROUND = "chat_background"
        private const val KEY_CHAT_GRADIENT = "chat_gradient"
        private const val KEY_CHAT_IMAGE = "chat_image_path"
        private const val KEY_USER_AVATAR = "user_avatar_path"
        private const val KEY_BOT_AVATAR = "bot_avatar_path"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_BOT_NAME = "bot_name"
        private const val KEY_AUTO_NEW_SESSION_MINUTES = "auto_new_session_minutes"
        private const val KEY_LAST_MODEL_CHECK = "last_model_check_millis"
        private const val KEY_PRIMARY_PROVIDER = "primary_provider"
        private const val KEY_PRIMARY_MODEL = "primary_model"
        private const val KEY_FALLBACK_PROVIDER = "fallback_provider"
        private const val KEY_FALLBACK_MODEL = "fallback_model"
        private const val KEY_PRIMARY_FAIL_THRESHOLD = "primary_fail_threshold"
        private const val KEY_BOTS = "bots"
        private const val KEY_ACTIVE_BOT = "active_bot"
        private const val KEY_CLOUD_PROVIDERS = "cloud_providers"
        private const val KEY_ACTIVE_CLOUD = "active_cloud"
        private const val KEY_FIRST_TOKEN_TIMEOUT_SEC = "first_token_timeout_sec"
        private const val KEY_STREAM_TIMEOUT_MIN = "stream_timeout_min"

        /** Geltungsbereich-Schlüssel für die Favoriten der Standard-Modellauswahl. */
        const val MODEL_SCOPE_STANDARD = "standard"

        /** Pausen-Schwellenwert (Minuten), der in den Einstellungen angeboten wird. */
        val AUTO_NEW_SESSION_OPTIONS = listOf(5, 10, 15, 30, 60)
        const val DEFAULT_AUTO_NEW_SESSION_MINUTES = 15
        const val CHAT_BG_DEFAULT = "default"

        /** Prüfzeitfenster: Versionsabgleich höchstens 1× pro Woche. */
        const val MODEL_CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

        /** Fallback-Schwelle: Anzahl aufeinanderfolgender Primär-Fehlschläge. */
        const val DEFAULT_PRIMARY_FAIL_THRESHOLD = 2
        const val MIN_PRIMARY_FAIL_THRESHOLD = 1
        const val MAX_PRIMARY_FAIL_THRESHOLD = 5
        // Timeout „Zeit bis zur ersten Antwort" (First-Token): 10 s .. 1 min, Default 30 s.
        const val DEFAULT_FIRST_TOKEN_TIMEOUT_SEC = 30
        const val MIN_FIRST_TOKEN_TIMEOUT_SEC = 10
        const val MAX_FIRST_TOKEN_TIMEOUT_SEC = 60
        // Timeout „Gesamte Antwortzeit" (Stream): 1 .. 15 min, Default 5 min.
        const val DEFAULT_STREAM_TIMEOUT_MIN = 5
        const val MIN_STREAM_TIMEOUT_MIN = 1
        const val MAX_STREAM_TIMEOUT_MIN = 15
        const val DEFAULT_MIC_GAIN = 4.0f
        const val MIN_MIC_GAIN = 1.0f
        const val MAX_MIC_GAIN = 12.0f

        // Bewährte Tail/Stille-Defaults (vorher hart in MicrophoneSource.kt)
        const val DEFAULT_MIN_TAIL = 8
        const val MIN_TAIL = 1
        const val MAX_TAIL = 20

        const val DEFAULT_SILENCE_BLOCKS = 5
        const val MIN_SILENCE = 1
        const val MAX_SILENCE = 15

        const val DEFAULT_MAX_TAIL = 30
        const val MIN_MAX_TAIL = 5
        const val MAX_MAX_TAIL = 80

        const val DEFAULT_SILENCE_FACTOR = 0.06f
        const val MIN_SILENCE_FACTOR = 0.01f
        const val MAX_SILENCE_FACTOR = 0.25f

        const val DEFAULT_SILENCE_FLOOR = 0.003f
        const val MIN_SILENCE_FLOOR = 0.001f
        const val MAX_SILENCE_FLOOR = 0.02f
    }
}

/** Feste Akzentfarben-Palette (in den Einstellungen wählbar). Jede Farbe bringt
 *  ihr Overlay-Theme sowie Basis-/Dunkel-/Bubble-Farbressourcen für die
 *  Anzeige im Palette-Wähler mit. */
enum class AccentColor(
    val themeRes: Int,
    val base: Int,
    val dark: Int,
    val bubble: Int
) {
    GREEN(R.style.Theme_HermiVoice_AccentGreen, R.color.accent_green, R.color.accent_green_dark, R.color.accent_green_bubble),
    DIM_GRAY(R.style.Theme_HermiVoice_AccentDimGray, R.color.accent_dimgray, R.color.accent_dimgray_dark, R.color.accent_dimgray_bubble),
    TEAL(R.style.Theme_HermiVoice_AccentTeal, R.color.accent_teal, R.color.accent_teal_dark, R.color.accent_teal_bubble),
    CADET_BLUE(R.style.Theme_HermiVoice_AccentCadetBlue, R.color.accent_cadetblue, R.color.accent_cadetblue_dark, R.color.accent_cadetblue_bubble),
    CHOCOLATE(R.style.Theme_HermiVoice_AccentChocolate, R.color.accent_chocolate, R.color.accent_chocolate_dark, R.color.accent_chocolate_bubble),
    DARK_SLATE_GRAY(R.style.Theme_HermiVoice_AccentDarkSlateGray, R.color.accent_darkslategray, R.color.accent_darkslategray_dark, R.color.accent_darkslategray_bubble)
}

/** Chat-Hintergrund-Typ. DEFAULT = bisherige Einfärbung (Standard, kein Bild). */
enum class ChatBackground {
    DEFAULT, GRADIENT, IMAGE
}