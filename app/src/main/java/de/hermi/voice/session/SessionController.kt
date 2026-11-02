package de.hermi.voice.session

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import de.hermi.voice.api.ChatSender
import de.hermi.voice.api.HermesApiSender
import java.util.Locale
import java.util.UUID

data class SearchHit(
    val session: ChatSession,
    val score: Int,
    val matchCount: Int,
    val snippet: CharSequence,
)

/** Ergebnis des Gateway-Abgleichs: lokal zu entfernende Sessions, Titeländerungen
 *  und neu vom Server importierte Sessions (Server gewinnt als Quelle). */
class GatewaySyncResult(
    val deletedIds: Set<String>,
    val renames: List<Pair<ChatSession, String>>,
    val newSessions: List<ChatSession> = emptyList(),
    /** Bekannte Sessions, deren Server-Gesamt-Historie die lokale ersetzt
     *  (Server gewinnt/source of truth): (Session, Server-Nachrichten). */
    val historyOverrides: List<Pair<ChatSession, List<ChatMessage>>> = emptyList()
)

/**
 * Session-Logik ohne UI-Zugriff (Suche, Reverse-Sync gegen den Gateway). Aus
 * MainActivity herausgelöst; Verhalten unverändert. Die Activity wendet Ergebnisse an
 * (Entfernen, Umbenennen, Persistieren, Neuzeichnen).
 */
class SessionController(private val chatSender: ChatSender) {

    companion object {
        /** Gleiche Konversation = gleiche Rollen-/Inhaltsfolge. Zeitstempel zählen
         *  bewusst NICHT: lokale (Sendezeit) und Server-Zeitstempel weichen immer ab
         *  und würden sonst bei jedem Abgleich einen Override samt Neuzeichnen auslösen. */
        fun sameConversation(a: List<ChatMessage>, b: List<ChatMessage>): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) {
                if (a[i].role != b[i].role || a[i].content.trim() != b[i].content.trim()) return false
            }
            return true
        }
    }

    /**
     * Entfernt aus der Server-Historie User-Turns (samt direkt folgender Teilantwort),
     * die lokal bewusst verworfen wurden (abgebrochen/fehlgeschlagen). Pro Inhalt werden
     * nur die Vorkommen über die lokale Anzahl hinaus unterdrückt — erfolgreich erneut
     * gesendete identische Fragen bleiben erhalten.
     */
    fun applySuppression(server: List<ChatMessage>, local: List<ChatMessage>, suppressed: List<String>): List<ChatMessage> {
        if (suppressed.isEmpty()) return server
        val drop = mutableSetOf<Int>()
        for (content in suppressed.map { it.trim() }.distinct()) {
            val localCount = local.count { it.role == "user" && it.content.trim() == content }
            val serverIdx = server.indices.filter { server[it].role == "user" && server[it].content.trim() == content }
            val extra = serverIdx.size - localCount
            if (extra <= 0) continue
            for (idx in serverIdx.takeLast(extra)) {
                drop.add(idx)
                if (idx + 1 < server.size && server[idx + 1].role == "assistant") drop.add(idx + 1)
            }
        }
        return if (drop.isEmpty()) server else server.filterIndexed { i, _ -> i !in drop }
    }

    /** Zuletzt erfolgreich abgeglichener Server-Stand je Gateway-ID
     *  (last_active, message_count). Unveränderte Sessions werden nicht erneut
     *  vollständig geladen (statt N+1-Requests pro Tick). Nur im Speicher: nach dem
     *  App-Start findet einmal ein Vollabgleich statt. */
    private val syncedStamp = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    /** Erzwingt beim nächsten Tick einen erneuten Abruf dieser Session. */
    fun invalidateSyncStamp(gatewaySessionId: String) {
        syncedStamp.remove(gatewaySessionId)
    }

    /** Sessions, die am Reverse-Sync teilnehmen. */
    fun syncCandidates(sessions: List<ChatSession>): List<ChatSession> {
        // Bot-Sessions ohne direkte Gateway-Adresse (Profil-Prefix …/p/<name> hinter
        // dem Proxy) liegen auf einem anderen Server als der globale Gateway; ein
        // Abgleich gegen den globalen Gateway würde sie fälschlich als gelöscht
        // melden → vom Reverse-Sync ausnehmen. Seit effectiveBase() lösen Profil-Bot-
        // Sessions ihre eigene Basis (…/p/<name>) auf und werden dagegen abgeglichen;
        // ausgenommen bleiben nur Bot-Sessions, deren Basis nicht bestimmbar ist.
        return sessions.filter {
            !it.gatewaySessionId.isNullOrBlank() &&
                (!it.viaBot || chatSender.effectiveBase(it) != null)
        }
    }

    /** Netzwerkabgleich — MUSS auf einem Hintergrund-Thread laufen. */
    fun diffAgainstGateway(
        withGateway: List<ChatSession>,
        // Lokale Absichten, die der Gateway noch nicht bestätigt hat (Tombstones /
        // Pending-Rename): lokale Absicht gewinnt, bis der Server sie bestätigt.
        isPendingDelete: (String) -> Boolean = { false },
        hasPendingRename: (String) -> Boolean = { false },
    ): GatewaySyncResult {
        // Nach Ziel-Basis gruppieren: globaler Gateway + ggf. Docker-Bots (Modus B).
        val byUrl = withGateway.groupBy { chatSender.gatewayTarget(it).first.trimEnd('/') }.toMutableMap()
        // Globale Gateway-Basis IMMER in den Abgleich aufnehmen — auch wenn lokal noch
        // keine verknüpfte Session (gatewaySessionId) existiert. Sonst würden neu am
        // Gateway entstandene Sessions (z. B. von einem anderen Gerät) nie importiert,
        // weil withGateway bei einer frischen App leer ist. Der Platzhalter trägt keine
        // gatewaySessionId und löst daher weder Löschung noch Umbenennung aus; er dient
        // nur als Anker, damit der Import-Teil die globale Basis abfragt.
        val globalAnchor = ChatSession(id = UUID.randomUUID().toString(), title = "", createdAt = 0L)
        val globalUrl = chatSender.gatewayTarget(globalAnchor).first.trimEnd('/')
        if (!byUrl.containsKey(globalUrl)) {
            byUrl[globalUrl] = listOf(globalAnchor)
        }
        val deletedIds = mutableSetOf<String>()
        val renames = mutableListOf<Pair<ChatSession, String>>()
        val newSessions = mutableListOf<ChatSession>()
        val overrides = mutableListOf<Pair<ChatSession, List<ChatMessage>>>()

        for ((url, group) in byUrl) {
            // Globale Basis immer mit dem globalen Key abfragen (nicht mit dem Key eines
            // zufällig ersten Bot-Eintrags der Gruppe).
            val isGlobal = url == globalUrl
            val key = chatSender.gatewayTarget(if (isGlobal) globalAnchor else group.first()).second
            val serverById = try {
                HermesApiSender.listGatewaySessions(url, key).associateBy { it.id }
            } catch (_: Exception) {
                null // Liste nicht erreichbar: diese Basis beim Abgleich überspringen.
            } ?: continue

            // Leere Server-Liste trotz lokaler Sessions mit Gateway-ID ist allein kein
            // Beweis fürs Löschen (falsches Profil/Key-Scope, Proxy-Antwort). Deshalb
            // wird die Basis NICHT mehr übersprungen: jede Session wird unten einzeln
            // per sessionExists() geprüft, und nur ein echtes 404 löscht lokal
            // (Auth-/Netzfehler liefern null und löschen nichts). So wird auch die
            // letzte Session eines Bots/Profils nachgezogen.

            // Basis-Charakteristik der Gruppe übernehmen (viaBot/ServerBase), damit
            // importierte Sessions bei späteren Abgleichläufen über dieselbe Basis
            // wiedergefunden werden.
            val basis = group.first()
            // Importierte Sessions der globalen Basis laufen NIE über einen Bot —
            // unabhängig davon, welcher Eintrag zufällig zuerst in der Gruppe steht.
            val importViaBot = if (isGlobal) false else basis.viaBot
            val importServerBase = if (isGlobal) null else basis.serverBase
            // Profil-Bot-Sessions behalten ihren Bot, damit die Basis (…/p/<name>) später
            // wieder aufgelöst wird (effectiveBase) und nie gegen den globalen Gateway läuft.
            val importBotId = if (isGlobal) null else basis.botId

            // Bereits lokal verankerte Gateway-IDs → nur die löschen/umbenennen.
            val localGids = group.mapNotNull { it.gatewaySessionId }.toSet()
            for (s in group) {
                val gid = s.gatewaySessionId ?: continue
                // Zum Löschen vorgemerkt: weder Titel noch Historie vom Server holen.
                if (isPendingDelete(gid)) continue
                val server = serverById[gid]
                if (server == null) {
                    // Fehlt in der Liste → NICHT sofort löschen (Liste kann gefiltert,
                    // begrenzt oder die Session archiviert sein). Erst die Gegenprobe
                    // per GET /api/sessions/{id}: nur ein echtes 404 löscht lokal.
                    if (HermesApiSender.sessionExists(url, key, gid) == false) {
                        deletedIds.add(s.id)
                    }
                } else {
                    // Offene lokale Umbenennung gewinnt, bis der Server sie bestätigt hat.
                    if (server.title.isNotBlank() && server.title != s.title && !hasPendingRename(gid)) {
                        // Serverseitig umbenannt → Titel übernehmen (Server gewinnt),
                        // auch wenn er lokal manuell gesetzt war.
                        renames.add(s to server.title)
                    }
                    // Server gewinnt/source of truth: für bereits bekannte Sessions
                    // die Server-Gesamt-Historie übernehmen (neue Nachrichten von
                    // anderen Geräten/Clients werden so nachgezogen). Best-effort:
                    // scheitert der Abruf, bleibt die lokale Historie unverändert.
                    // Unverändert seit dem letzten Abgleich (last_active + message_count
                    // gleich) → Nachrichten nicht erneut laden. Ohne message_count
                    // (ältere Gateways) wird immer geladen.
                    val stamp = server.lastActive to server.messageCount
                    if (server.messageCount >= 0 && syncedStamp[gid] == stamp) continue
                    runCatching {
                        val serverHistory = applySuppression(
                            HermesApiSender.fetchSessionMessages(url, key, gid), s.history, s.suppressedTurns
                        )
                        if (serverHistory.isNotEmpty() && !sameConversation(serverHistory, s.history)) {
                            overrides.add(s to serverHistory)
                        }
                        syncedStamp[gid] = stamp
                    }
                }
            }

            // Richtung (C): Serverseitig neue Sessions, die lokal unbekannt sind,
            // importieren (nur in die Liste aufnehmen, nicht öffnen). Server gewinnt.
            for (server in serverById.values) {
                if (server.id in localGids) continue
                // Lokal gelöscht, Server-DELETE noch nicht bestätigt → nicht zurückholen.
                if (isPendingDelete(server.id)) continue
                // Nur Sessions importieren, die mit Hermi entstanden sind (Herkunft
                // "api_server"); Telegram/CLI/Cron etc. sowie ausgeblendete/interne
                // Sessions bleiben draußen. Fehlt das Feld (ältere Gateways), gilt
                // die Session als importierbar.
                if (server.hidden) continue
                if (server.source != null && server.source != HermesApiSender.HERMI_SESSION_SOURCE) continue
                try {
                    val history = HermesApiSender.fetchSessionMessages(url, key, server.id)
                    val now = System.currentTimeMillis()
                    val lastActive = if (server.lastActive > 0L) server.lastActive else now
                    newSessions.add(
                        ChatSession(
                            id = UUID.randomUUID().toString(),
                            title = server.title.ifBlank { "Neue Konversation" },
                            createdAt = lastActive,
                            lastMessageAt = lastActive,
                            gatewaySessionId = server.id,
                            viaBot = importViaBot,
                            serverBase = importServerBase,
                            botId = importBotId,
                            history = history.toMutableList(),
                        )
                    )
                } catch (_: Exception) {
                    // History nicht abrufbar → Session trotzdem mit aktuellem
                    // Zeitstempel aufnehmen, damit sie nicht dauerhaft übersprungen
                    // wird; die Historie wird beim nächsten Abgleich ergänzt.
                    val now = System.currentTimeMillis()
                    newSessions.add(
                        ChatSession(
                            id = UUID.randomUUID().toString(),
                            title = server.title.ifBlank { "Neue Konversation" },
                            createdAt = now,
                            lastMessageAt = if (server.lastActive > 0L) server.lastActive else now,
                            gatewaySessionId = server.id,
                            viaBot = importViaBot,
                            serverBase = importServerBase,
                            botId = importBotId,
                        )
                    )
                }
            }
        }
        return GatewaySyncResult(deletedIds, renames, newSessions, overrides)
    }

    /** In-Memory-Suche über Titel + alle Nachrichten beider Rollen, gewichtet. */
    fun searchSessions(sessions: List<ChatSession>, query: String, accent: Int): List<SearchHit> {
        val needle = fold(query)
        val hits = ArrayList<SearchHit>()
        for (s in sessions) {
            var score = 0
            var matchCount = 0
            var snippetSource: String? = null
            var firstIdx = 0

            val titleLower = fold(s.title)
            if (titleLower.contains(needle)) {
                score += 100
                if (titleLower.startsWith(needle)) score += 20
                matchCount += countOccurrences(titleLower, needle)
                snippetSource = s.title
                firstIdx = titleLower.indexOf(needle)
            }
            for (msg in s.history) {
                val c = msg.content
                if (c.isEmpty()) continue
                val cl = fold(c)
                if (!cl.contains(needle)) continue
                val n = countOccurrences(cl, needle)
                matchCount += n
                // Eigene Fragen wiegen mehr als KI-Antworten (Antworten sind lang
                // und liefern dadurch mehr Rauschtreffer).
                score += if (msg.role == "user") 45 else 20
                score += minOf(n, 5)
                if (snippetSource == null) {
                    snippetSource = c
                    firstIdx = cl.indexOf(needle)
                }
            }
            if (matchCount > 0 && snippetSource != null) {
                hits.add(
                    SearchHit(s, score, matchCount, buildSnippet(snippetSource, needle, firstIdx, accent))
                )
            }
        }
        hits.sortWith(
            compareByDescending<SearchHit> { it.score }
                .thenByDescending { it.session.lastMessageAt }
        )
        return hits
    }

    /** Längenerhaltende Kleinschreibung: String.lowercase() kann die Länge ändern
     *  (z. B. „İ“), wodurch Treffer-Indizes gegenüber dem Originaltext verrutschen. */
    private fun fold(text: String): String =
        String(CharArray(text.length) { Character.toLowerCase(text[it]) })

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = text.indexOf(needle, idx)
            if (idx < 0) break
            count++
            idx += needle.length
        }
        return count
    }

    /** Schneidet einen Ausschnitt um die erste Fundstelle und hebt Treffer hervor. */
    private fun buildSnippet(text: String, needle: String, firstIdx: Int, accent: Int): CharSequence {
        val W = 55
        val start = maxOf(0, firstIdx - W)
        val end = minOf(text.length, firstIdx + needle.length + W)
        val sb = StringBuilder()
        if (start > 0) sb.append("…")
        sb.append(text, start, end)
        if (end < text.length) sb.append("…")
        val snippet = sb.toString()
        val spannable = SpannableString(snippet)
        val lower = fold(snippet)
        var from = 0
        while (true) {
            val i = lower.indexOf(needle, from)
            if (i < 0) break
            runCatching {
                spannable.setSpan(
                    ForegroundColorSpan(accent), i, i + needle.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD), i, i + needle.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            from = i + needle.length
        }
        return spannable
    }
}
