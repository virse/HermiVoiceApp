package de.hermi.voice.session

import android.content.Context
import de.hermi.voice.crypto.CryptoManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Eine eigene Nachricht in einer Session, inklusive Zeitstempel (Epoch-Millis).
 * Wird chronologisch in der Session-Historie gehalten. Altsessions kennen pro
 * Nachricht keine Zeit — dort wird beim Laden der Session-eigene `createdAt`
 * als Schätzwert übertragen (keine Migration der Bestandstexte).
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long
)

/**
 * Eine abgeschlossene oder laufende Konversation.
 *
 * Jede Session hält ihre eigene Nachrichten-Historie (role/content-Zeitstempel,
 * chronologisch). Die Historie wird bei jeder Anfrage komplett über das
 * `messages`-Array an den stateless Hermes-API-Endpoint geschickt; eine
 * Session entspricht damit genau einer Konversation in der Hermes-App.
 */
data class ChatSession(
    val id: String,
    /** Vom Nutzer sichtbarer Kurztitel (z. B. erster User-Turn, gekürzt). */
    var title: String,
    /** true, wenn der Titel vom Nutzer manuell gesetzt wurde. Ein manuell
     *  gesetzter Titel wird durch die Auto-Ableitung beim ersten User-Turn
     *  NICHT überschrieben. */
    var manualTitle: Boolean = false,
    /** Epoch-Millis der Erzeugung, für die Sortierung nach "neueste zuerst". */
    val createdAt: Long,
    /** Epoch-Millis des letzten Austauschs (echte letzte Nachricht); Altsessions
     *  ohne bekannten Austausch fallen beim Laden auf `createdAt` zurück. */
    var lastMessageAt: Long = createdAt,
    /** true, wenn die Session favorisiert (angepinnt) ist. Favorisierte Sessions
     *  bleiben im Sessions-Dialog oben (nach letztem Austausch sortiert) statt im
     *  Datumsstrom zu versinken. Ein Stern-Icon links in der Zeile toggelt das. */
    var pinned: Boolean = false,
    /** Gateway-Session-ID des Hermes-API-Servers (Header `X-Hermes-Session-Id` der
     *  letzten Antwort). Nullenfrei, wenn noch keine Anfrage für diese Session
     *  gesendet wurde. Wird nur für die Gateway-Löschung per
     *  `DELETE /api/sessions/{id}` benötigt — hat keinen Einfluss auf teilen,
     *  umbenennen oder favorisieren. */
    var gatewaySessionId: String? = null,
    /** Server-Basis (URL/Port) des Bots, mit dem diese Session geführt wurde.
     *  Für Docker-Bots mit eigener Basis (Modus B) gesetzt; null/leer, wenn die
     *  Session über den globalen Gateway läuft. Wird nur für die Gateway-Löschung
     *  (`DELETE /api/sessions/{id}`) gegen das richtige Gateway benötigt. */
    var serverBase: String? = null,
    /** true, wenn die Session zuletzt über einen Bot (Profil) geführt wurde. Bot-
     *  Sessions liegen auf dem Server des Bots (Profil-Prefix / eigenes Gateway) und
     *  sind gegen den globalen Gateway NICHT abgleichbar — der Reverse-Sync überspringt
     *  sie, wenn für den Bot keine direkte Gateway-Adresse bekannt ist. */
    var viaBot: Boolean = false,
    /** ID des Bot-Profils, mit dem diese Session gestartet wurde (null = Standard-
     *  Agent / globale Konfiguration). Beim Öffnen der Session aus der Historie wird
     *  das Profil auf diesen Bot zurückgesetzt, damit die Fortsetzung wieder mit dem
     *  Start-Profil läuft. Existiert der Bot nicht mehr oder hat er keinen API-Key,
     *  fällt die App auf den Standard-Agent zurück. */
    var botId: String? = null,
    /** ID des Cloud-KI-Anbieters, mit dem diese Session gestartet wurde (null = kein
     *  Cloud-Kanal → Hermes-Profil/Bot). Cloud-Sessions sind zustandslos: kein
     *  Gateway-Session-Key, kein Reverse-Sync, keine Gateway-Löschung. Beim Öffnen
     *  der Session aus der Historie wird der Anbieter wiederhergestellt. */
    var cloudId: String? = null,
    /** Inhalte lokal verworfener User-Turns (abgebrochen/fehlgeschlagen/leere Antwort).
     *  Hat der Server den Turn trotzdem gespeichert, unterdrückt der Sync ihn beim
     *  Übernehmen der Server-Historie (lokale Absicht gewinnt). Auf 20 Einträge begrenzt. */
    val suppressedTurns: MutableList<String> = mutableListOf(),
    val history: MutableList<ChatMessage> = mutableListOf()
)

/**
 * Persistiert alle Sessions als eine JSON-Datei im privaten App-Verzeichnis
 * (filesDir/sessions.json), AES-256-GCM-verschlüsselt (Schlüssel im Android-Keystore).
 * Synchron und main-Thread-sicher: die Datei ist klein (nur Text-Konversationen) und
 * wird bei jeder Änderung komplett überschrieben. Kein Netzzugriff, keine Berechtigungen.
 *
 * Enthält auch die Session-Titel (Text-Snippets) — die komplette Datei ist daher
 * verschlüsselt. Nicht entschlüsselbare Bestände (beschädigt, anderer Keystore nach
 * Restore) werden wie früher eine beschädigte Datei behandelt: als leer.
 */
object SessionStore {

    private const val FILE_NAME = "sessions.json"
    private const val PREFS_NAME = "hermi_sessions"
    private const val KEY_ACTIVE = "active_session_id"
    private const val KEY_MIGRATED = "crypto_migrated"

    /** Lädt alle Sessions (leer, wenn keine Datei existiert). Migriert Bestandsdaten. */
    fun load(context: Context): MutableList<ChatSession> {
        lastLoadFailed = false
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            markMigrated(context)
            return mutableListOf()
        }
        migrateFileIfNeeded(context)
        return try {
            val root = JSONObject(CryptoManager.decrypt(file.readText()))
            val arr = root.getJSONArray("sessions")
            val sessions = mutableListOf<ChatSession>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                // Pro Session abgesichert: ein einzelner defekter Eintrag darf nicht den
                // gesamten Bestand in die Quarantäne schicken (und dann leer überschreiben).
                try {
                    val o = arr.getJSONObject(i)
                    val history = mutableListOf<ChatMessage>()
                    val createdAt = o.optLong("createdAt", 0)
                    val hArr = o.optJSONArray("history") ?: JSONArray()
                    for (j in 0 until hArr.length()) {
                        val h = hArr.getJSONObject(j)
                        // Alte Einträge ohne Zeitstempel: auf createdAt zurückfallen
                        // (keine Migration der Bestandstexte, nur Schätzwert).
                        history.add(
                            ChatMessage(
                                role = h.getString("role"),
                                content = h.getString("content"),
                                timestamp = h.optLong("timestamp", createdAt)
                            )
                        )
                    }
                    sessions.add(
                        ChatSession(
                            id = o.getString("id"),
                            title = o.optString("title", ""),
                            manualTitle = o.optBoolean("manualTitle", false),
                            createdAt = createdAt,
                            lastMessageAt = o.optLong("lastMessageAt", createdAt),
                            pinned = o.optBoolean("pinned", false),
                            gatewaySessionId = if (o.has("gatewaySessionId")) o.optString("gatewaySessionId") else null,
                            serverBase = if (o.has("serverBase")) o.optString("serverBase") else null,
                            viaBot = o.optBoolean("viaBot", false),
                            botId = if (o.has("botId")) o.optString("botId") else null,
                            cloudId = if (o.has("cloudId")) o.optString("cloudId") else null,
                            suppressedTurns = o.optJSONArray("suppressedTurns")?.let { a ->
                                MutableList(a.length()) { idx -> a.optString(idx) }
                            } ?: mutableListOf(),
                            history = history
                        )
                    )
                } catch (_: Exception) {
                    skipped++
                }
            }
            // Defekte Einträge wurden übersprungen: Original sichern, bevor das nächste
            // save() es mit dem reduzierten Bestand überschreibt.
            if (skipped > 0) quarantine(file)
            sessions
        } catch (e: Exception) {
            // Beschädigte Datei nicht zum Absturz bringen — als leer behandeln.
            // ABER vorher sichern: Das nächste save() überschreibt sessions.json mit dem
            // (dann leeren) Bestand; ohne Kopie wäre ein nur vorübergehend lesbarer
            // Fehler (z. B. Keystore-Aussetzer) ein endgültiger Datenverlust.
            quarantine(file)
            lastLoadFailed = true
            mutableListOf()
        }
    }

    /** true, wenn das letzte [load] eine vorhandene, aber nicht lesbare Datei vorfand
     *  (Kopie wurde als `sessions.json.corrupt-<zeit>` abgelegt). Die UI zeigt dann
     *  einen Hinweis, statt den Bestand stillschweigend zu verlieren. */
    @Volatile
    var lastLoadFailed: Boolean = false
        private set

    /** Kopiert eine nicht lesbare Sessions-Datei zur Sicherung; behält nur die
     *  jüngsten 3 Sicherungen. Fehler dabei dürfen den Start nie verhindern. */
    private fun quarantine(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) return
            val copy = File(file.parentFile, "$FILE_NAME.corrupt-${System.currentTimeMillis()}")
            file.copyTo(copy, overwrite = false)
            file.parentFile?.listFiles { f -> f.name.startsWith("$FILE_NAME.corrupt-") }
                ?.sortedByDescending { it.name }
                ?.drop(3)
                ?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /** Schreibt alle Sessions atomar (tmp-Datei + rename). Synchron. */
    fun save(context: Context, sessions: List<ChatSession>) {
        val json = serialize(sessions)
        write(context, json, ++writeSeq)
    }

    // Ein einzelner Hintergrund-Thread für Schreibvorgänge: Verschlüsselung + Dateizugriff
    // laufen nicht mehr auf dem UI-Thread (bei jeder Nachricht/jedem Delta-Abschluss).
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "hermi-session-writer").apply { isDaemon = true }
    }

    // Laufende Nummer der Schreibaufträge. Ein veralteter Auftrag (kleinere Nummer als
    // der zuletzt geschriebene) wird verworfen, damit er nie neuere Daten überschreibt —
    // z. B. wenn onPause synchron speichert, während ein älterer Auftrag noch wartet.
    @Volatile private var writeSeq = 0L
    private var lastWrittenSeq = 0L

    /**
     * Wie [save], aber Serialisierung, Verschlüsselung und Dateizugriff im Hintergrund.
     * Nur ein schneller Snapshot (Listen-Kopien, kein Textaufbau) läuft auf dem Aufrufer
     * (UI-Thread), damit spätere Änderungen an den Listen den Schreibauftrag nicht
     * verfälschen. Die eigentliche JSON-Serialisierung ist bei großen Beständen
     * (org.json baut Sekunden-lange Zwischen-Strings) zu teuer für den UI-Thread —
     * vorher führte genau das beim Screen-Off (onPause) zum ANR.
     */
    fun saveAsync(context: Context, sessions: List<ChatSession>) {
        val seq = ++writeSeq
        val snapshot = sessions.map { s -> s.copy(history = s.history.toMutableList()) }
        val appContext = context.applicationContext
        writer.execute {
            try {
                write(appContext, serialize(snapshot), seq)
            } catch (_: Exception) {
                // Nächster Schreibauftrag versucht es erneut mit neuerem Stand.
            }
        }
    }

    @Synchronized
    private fun write(context: Context, root: String, seq: Long) {
        if (seq < lastWrittenSeq) return
        val encrypted = CryptoManager.encrypt(root)
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(encrypted)
        try {
            // Atomar ersetzen, ohne das Ziel vorher zu löschen (kein Zeitfenster ohne Datei).
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            // Letzter Ausweg: direkt überschreiben (Datei wurde nicht vorher gelöscht).
            file.writeText(encrypted)
            tmp.delete()
        }
        lastWrittenSeq = seq
    }

    private fun serialize(sessions: List<ChatSession>): String {
        val arr = JSONArray()
        for (s in sessions) {
            val hArr = JSONArray()
            for (m in s.history) {
                hArr.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("content", m.content)
                        .put("timestamp", m.timestamp)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("manualTitle", s.manualTitle)
                    .put("createdAt", s.createdAt)
                    .put("lastMessageAt", s.lastMessageAt)
                    .put("pinned", s.pinned)
                    .put("history", hArr)
                    .apply {
                        if (s.gatewaySessionId != null) put("gatewaySessionId", s.gatewaySessionId)
                        if (s.serverBase != null) put("serverBase", s.serverBase)
                        if (s.viaBot) put("viaBot", true)
                        if (s.botId != null) put("botId", s.botId)
                        if (s.cloudId != null) put("cloudId", s.cloudId)
                        if (s.suppressedTurns.isNotEmpty()) put("suppressedTurns", JSONArray(s.suppressedTurns))
                    }
            )
        }
        return JSONObject().put("sessions", arr).toString()
    }

    /**
     * Migriert eine bestehende Klartext-sessions.json (Versionen vor 1.21) einmalig
     * zu einem verschlüsselten Ciphertext. Idempotent über das Migrations-Flag; eine
     * bereits verschlüsselte Datei wird unangetastet übernommen.
     */
    private fun migrateFileIfNeeded(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists() && file.length() > 0L) {
            val raw = file.readText()
            if (!CryptoManager.isEncryptedText(raw)) {
                // alter Klartext-JSON → verschlüsseln; konvertiert auch harmlos
                // beschädigte Klartext-Bestände (werden danach als leer behandelt).
                file.writeText(CryptoManager.encrypt(raw))
            }
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun markMigrated(context: Context) {
        prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Erzeugt eine neue, leere Session mit generiertem ID und Zeitstempel. */
    fun create(title: String = "Neue Konversation"): ChatSession =
        ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis()
        )

    /** Merkt sich die zuletzt aktive Session für das Wiederherstellen beim Start. */
    fun saveActiveId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    /** ID der zuletzt aktiven Session (null, wenn keine gespeichert ist). */
    fun loadActiveId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE, null)

    /** Baut einen Kurztitel aus der ersten User-Nachricht (max. ~48 Zeichen). */
    fun deriveTitle(firstMessage: String): String {
        val cleaned = firstMessage.trim().replace(Regex("\\s+"), " ")
        return if (cleaned.length <= 48) cleaned else cleaned.take(48) + "…"
    }
}
