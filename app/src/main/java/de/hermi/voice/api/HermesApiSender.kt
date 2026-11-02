package de.hermi.voice.api

import org.json.JSONArray
import org.json.JSONObject
import de.hermi.voice.session.ChatMessage
import de.hermi.voice.config.CloudTools
import java.io.BufferedReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sendet transkribierten Text direkt an den OpenAI-kompatiblen Hermes-API-Server
 * (http://<hermes-host>:8642/v1/chat/completions) als echte Nutzereingabe (role:"user").
 *
 * Hintergrund: Eine Telegram-Bot-Nachricht wird über die Bot-API immer dem Bot (Hermes)
 * zugeschrieben und erscheint damit nie als Nutzereingabe; zudem empfängt ein Bot seine
 * eigenen Nachrichten nicht über getUpdates. Der Hermes-API-Server behandelt den Text
 * dagegen als echte User-Message und liefert die Antwort unter choices[0].message.content.
 *
 * Kein stiller Verlust: Fehler werden als Exception nach oben gereicht.
 */
/**
 * Abbruch-Token für eine laufende Streaming-Anfrage an [HermesApiSender.sendMessageStream].
 * [cancel] setzt das Flag und trennt die Verbindung sofort - damit wird auch ein
 * blockiertes `readLine()` (Server-Stall während des Denkprozesses o. Ä.) unmittelbar
 * beendet, statt bis zum readTimeout zu warten. Über [cancelled] unterscheidet die
 * UI einen echten Nutzer-Abbruch von einem Verbindungsfehler.
 */
class StreamCancellation {
    @Volatile var cancelled = false
    @Volatile internal var connection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }
}

object HermesApiSender {

    /**
     * Timeout bis zum ERSTEN Token (Inhalt ODER Denk-Token) im Streaming.
     * Liefert der Server in dieser Zeit nichts (z. B. nicht verfügbares Modell,
     * das einen stummen Stream offen lässt statt einen Fehler zu melden), wirft
     * `readLine()` eine SocketTimeoutException → die Fallback-Schleife des
     * Aufrufers (MainActivity) schaltet dann automatisch aufs Fallback-Modell.
     * Public: die UI nutzt denselben Wert für den sichtbaren First-Token-Countdown.
     */
    const val FIRST_TOKEN_READ_TIMEOUT_MS = 30_000L

    /** Großzügiges Timeout pro Stream-Read NACH dem ersten Token. Deckt lange
     *  Denkphasen (Reasoning) ab, bei denen zwischen Tokens viel Zeit vergeht. */
    private const val STREAM_READ_TIMEOUT_MS = 300_000L

    /**
     * Sendet eine Konversations-Historie als User-/Assistant-Nachrichten an den
     * Hermes-API-Server (OpenAI-kompatibler /v1/chat/completions-Endpoint).
     *
     * Der Endpoint ist STATELESS: die volle Konversation muss in jeder Anfrage
     * über das `messages`-Array mitgeschickt werden. Nur so behält Hermes den
     * Kontext für Rückfragen und stößt nicht pro Frage eine neue Session an
     * (der Server leitet aus dem konstanten ersten User-Turn deterministisch
     * dieselbe Session-ID ab → ein Eintrag in der Hermes-Desktop-App).
     *
     * @param baseUrl Basis-Endpunkt, z. B. "http://192.168.1.100:8642"
     * @param apiKey  API-Schlüssel des Hermes-API-Servers
     * @param history Abwechselnde "user"/"assistant"-Turns in chronologischer
     *                Reihenfolge; der letzte Eintrag muss role="user" sein
     *                (die aktuelle Frage), die übrigen sind der Vorkontext.
     * @return der Antworttext von Hermes (choices[0].message.content)
     * @throws HermesApiException bei Fehlern (Netz, Auth, API)
     */
    @Throws(HermesApiException::class)
        fun sendMessage(
            baseUrl: String,
            apiKey: String,
            history: List<Pair<String, String>>,
            modelPair: Pair<String, String>? = null
        ): String {
            if (baseUrl.isBlank() || apiKey.isBlank()) {
                throw HermesApiException("Server-URL oder API-Key fehlt")
            }
            if (history.isEmpty() || history.last().first != "user") {
                throw HermesApiException("Ungültige Nachrichten-Historie")
            }

            val endpoint = baseUrl.trimEnd('/') + "/v1/chat/completions"

            val messages = JSONArray()
            for ((role, content) in history) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }

            val json = JSONObject()
                .put("stream", false)
                .put("messages", messages)
            // Modell-Auswahl: Das Paar (provider + model) wird IMMER gemeinsam
            // gesendet. Ein bloßes `model` ohne `provider` wird vom Gateway auf
            // /v1/chat/completions standardmäßig ignoriert — nur das vollständige
            // Paar ist deterministisch. Ohne Auswahl bleibt es beim Agent-Default.
            val pair = modelPair?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
            if (pair != null) {
                json.put("model", pair.second).put("provider", pair.first)
            } else {
                json.put("model", "hermes-agent")
            }
            val body = json.toString()

            val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            conn.outputStream.use { out: OutputStream ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val raw = readBody(conn)
            if (code in 200..299) {
                try {
                    val json = JSONObject(raw)
                    json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                } catch (e: Exception) {
                    throw HermesApiException("Antwort nicht lesbar: $raw", e)
                }
            } else {
                throw HermesApiException("HTTP $code: ${raw.take(500)}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Wie [sendMessage], aber mit `stream: true` (SSE): Der Antworttext kommt
     * tokenweise und wird live über [onDelta] gemeldet. Denk-/Reasoning-Tokens
     * (DeepSeek-artige Modelle) werden getrennt über [onReasoning] geliefert
     * und dürfen NICHT in die Antwort gemischt werden.
     *
     * Der Endpoint liefert SSE-Frames im Format `data: {json}\n\n`. Übersprungen
     * werden: Keepalive-Kommentare (`: keepalive`), benannte Events
     * (`event: hermes.tool.progress`, `hermes.status`, `approval.request`) und
     * Chunks ohne Content (der erste trägt nur die Rolle). Ende: Finish-Chunk
     * mit `finish_reason` bzw. `data: [DONE]`.
     *
     * Die Callbacks laufen auf dem aufrufenden (Hintergrund-)Thread — der
     * Aufrufer muss UI-Updates selbst per runOnUiThread heben.
     *
     * @return der vollständig akkumulierte Antworttext (identisch mit der
     *         Summe aller [onDelta]-Aufrufe)
     * @throws HermesApiException bei HTTP-Fehlern, Fehlern im Stream oder
     *         Netzabbrüchen mitten im Stream
     */
    @Throws(HermesApiException::class)
    fun sendMessageStream(
            baseUrl: String,
            apiKey: String,
            history: List<Pair<String, String>>,
            modelPair: Pair<String, String>? = null,
            sessionKey: String? = null,
            cancellationToken: StreamCancellation? = null,
            firstTokenTimeoutMs: Long = FIRST_TOKEN_READ_TIMEOUT_MS,
            streamTimeoutMs: Long = STREAM_READ_TIMEOUT_MS,
            onDelta: (String) -> Unit,
            onReasoning: (String) -> Unit,
            onSessionId: (String?) -> Unit = {}
        ): String {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            throw HermesApiException("Server-URL oder API-Key fehlt")
        }
        if (history.isEmpty() || history.last().first != "user") {
            throw HermesApiException("Ungültige Nachrichten-Historie")
        }

        val endpoint = baseUrl.trimEnd('/') + "/v1/chat/completions"

        val messages = JSONArray()
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }

        val json = JSONObject()
            .put("stream", true)
            .put("messages", messages)
        // Wie in sendMessage: Das Paar (provider + model) IMMER gemeinsam senden.
        val pair = modelPair?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
        if (pair != null) {
            json.put("model", pair.second).put("provider", pair.first)
        } else {
            json.put("model", "hermes-agent")
        }
        val body = json.toString()

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        // Verbindung früh ans Token hängen, damit cancel() auch Connect/Write und
        // einen blockierten readLine sofort beenden kann.
        cancellationToken?.connection = conn
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            // Stabiles Gateway-Session-Key pro Chat: dient dem Server als
            // Memory-Scope (Konversationsname) und ändert die stateless
            // History-/Continuation-Logik nicht. Der Key bleibt über
            // Umbenennen/Favorisieren stabil, ist aber NICHT der Lösch-ID.
            if (!sessionKey.isNullOrBlank()) {
                conn.setRequestProperty("X-Hermes-Session-Key", sessionKey)
            }

            conn.outputStream.use { out: OutputStream ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val raw = readBody(conn)
                throw HermesApiException("HTTP $code: ${raw.take(500)}")
            }
            // Antwort-Header: Die vom Gateway vergebene, LÖSCHBARE Session-ID.
            // Sie ist der Identifikator für DELETE /api/sessions/{id}, wird aber
            // erst hier (also nach der ersten erfolgreichen Antwort) bekannt.
            // Kein Fehlerfall, wenn sie fehlt — dann bleibt die Session lokal.
            onSessionId(conn.getHeaderField("X-Hermes-Session-Id")?.takeIf { it.isNotBlank() })

            val result = StringBuilder()
            val reasoning = StringBuilder()
            val reader = conn.inputStream.bufferedReader()
            // Bis zum ersten Token gilt das kurze First-Token-Timeout, danach das
            // großzügige Stream-Timeout für längere Denk-/Antwortphasen.
            var firstTokenReceived = false
            // true, sobald der Stream REGULÄR endete ([DONE] oder finish_reason). Endet die
            // Verbindung ohne eines von beiden (EOF mitten im Text, Proxy-Abbruch, Netz
            // weg), ist die Antwort abgeschnitten und darf nicht als Erfolg gelten —
            // sonst würde ein Teiltext gespeichert, vorgelesen und gemeldet.
            var completed = false
            // Echtzeit-Frist bis zum ersten Token. Wird bei JEDER Schleifen-
            // iteration geprüft — greift auch dann, wenn der Server die
            // Verbindung mit Keepalive-/leeren Frames offen hält (dann blockiert
            // readLine() nie und das readTimeout unten würde nie feuern).
            val firstTokenDeadline = System.currentTimeMillis() + firstTokenTimeoutMs
            try {
                while (true) {
                    // Nutzer hat die Antwort abgebrochen -> sofort raus, auch ohne
                    // neues Frame. Bei einem Stall beendet cancel()-disconnect()
                    // das blockierte readLine als Exception (Socket closed/reset).
                    if (cancellationToken?.cancelled == true) {
                        throw HermesApiException("Antwort durch Nutzer abgebrochen")
                    }
                    // Kein Token binnen der First-Token-Frist -> Fallback auslösen,
                    // auch wenn der Server nur leere Keepalives liefert. Das gilt nur
                    // bis zum ERSTEN Token; danach greift wieder das Stream-Timeout.
                    if (!firstTokenReceived &&
                        System.currentTimeMillis() > firstTokenDeadline
                    ) {
                        throw HermesApiException(
                            "Keine Antwort vom Modell innerhalb von " +
                                "${firstTokenTimeoutMs / 1000}s"
                        )
                    }
                    conn.readTimeout =
                        if (firstTokenReceived) streamTimeoutMs.toInt() else firstTokenTimeoutMs.toInt()
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    // Keepalive-Kommentar ("") bzw. ": keepalive" überspringen.
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                    // Benannte Events (hermes.tool.progress, hermes.status,
                    // approval.request) haben keinen Inhalt für uns.
                    if (trimmed.startsWith("event:")) continue
                    if (!trimmed.startsWith("data:")) continue
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") {
                        completed = true
                        break
                    }

                    val chunk = try {
                        JSONObject(payload)
                    } catch (e: Exception) {
                        continue // nicht-parsbares Fragment überspringen
                    }
                    // Fehler mitten im Stream (z. B. Tool-Abbruch) als Exception melden.
                    if (chunk.has("error")) {
                        val err = chunk.optJSONObject("error")
                        val msg = err?.optString("message") ?: chunk.toString()
                        throw HermesApiException("Stream-Fehler: $msg")
                    }

                    val choices = chunk.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: continue

                    if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                        val r = delta.optString("reasoning_content")
                        if (r.isNotEmpty()) {
                            firstTokenReceived = true
                            reasoning.append(r)
                            onReasoning(r)
                        }
                    }
                    if (delta.has("content") && !delta.isNull("content")) {
                        val c = delta.optString("content")
                        if (c.isNotEmpty()) {
                            firstTokenReceived = true
                            result.append(c)
                            onDelta(c)
                        }
                    }

                    if (!choice.isNull("finish_reason")) {
                        // Fehlgeschlagener Turn (Modell nicht verfügbar): Das
                        // Gateway markiert das Finish-Chunk mit hermes.failed=true.
                        // Das ist das zuverlässige Signal — KEIN Textvergleich mit
                        // der englischen Server-Notiz nötig. Die Notiz wurde zwar
                        // schon als content-Delta gestreamt, die UI ersetzt sie
                        // aber anhand dieser Exception durch eine deutsche Meldung.
                        if (chunk.optJSONObject("hermes")?.optBoolean("failed", false) == true) {
                            throw HermesFailedTurnException(
                                "KI-Modell nicht verfügbar (Failed Turn vom Gateway)"
                            )
                        }
                        if (choice.optString("finish_reason") == "error") {
                            throw HermesApiException("Stream vom Gateway mit finish_reason=error beendet")
                        }
                        completed = true
                        break
                    }
                }
                if (!completed) {
                    throw HermesApiException(
                        "Stream vorzeitig beendet (Antwort unvollständig, ${result.length} Zeichen empfangen)"
                    )
                }
            } finally {
                reader.close()
            }
            result.toString()
        } finally {
            cancellationToken?.connection = null
            conn.disconnect()
        }
    }

    /**
     * Löscht eine Gateway-Session über den API-Server (DELETE /api/sessions/{id}).
     * Benutzt denselben Base-URL/Auth-Pfad wie das reguläre Senden. Rückgabewert:
     * true = erfolgreich gelöscht (2xx) ODER bereits gelöscht/nicht vorhanden (404,
     * dann ist die Server-Session ebenfalls weg); false = anderer Fehler (z. B.
     * 409 = gerade aktiver Turn oder Netzwerkproblem) - die lokale Session wird
     * in dem Fall trotzdem entfernt, der Aufruf ist bewusst best-effort.
     */
    @Throws(HermesApiException::class)
    fun deleteGatewaySession(baseUrl: String, apiKey: String, sessionId: String): Boolean {
        if (baseUrl.isBlank() || apiKey.isBlank()) return false
        if (sessionId.isBlank()) return false
        val endpoint = baseUrl.trimEnd('/') + "/api/sessions/" + sessionId
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            // 2xx und 404 (nicht mehr vorhanden) gelten als Erfolg.
            (code in 200..299) || code == 404
        } catch (e: Exception) {
            throw HermesApiException("DELETE /api/sessions fehlgeschlagen: ${e.message}", e)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Benennt eine Gateway-Session über den API-Server um (PATCH /api/sessions/{id})
     * mit dem Feld `title`. Ein leerer Titel löscht den Namen serverseitig (zurück
     * zur Auto-Benennung). Gleiche Base-URL-/Auth-Auflösung wie beim Löschen.
     * Rückgabewert: true = erfolgreich umbenannt (2xx) ODER die Server-Session
     * existiert nicht mehr (404, dann gibt es nichts umzubenennen); false = anderer
     * Fehler (z. B. 400 Titel bereits vergeben oder Netzwerkproblem). Bewusst
     * best-effort wie beim Löschen.
     */
    @Throws(HermesApiException::class)
    fun renameGatewaySession(baseUrl: String, apiKey: String, sessionId: String, title: String): Boolean {
        if (baseUrl.isBlank() || apiKey.isBlank()) return false
        if (sessionId.isBlank()) return false
        val endpoint = baseUrl.trimEnd('/') + "/api/sessions/" + sessionId
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "PATCH"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.outputStream.use { out ->
                out.write(JSONObject().put("title", title).toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code == 400) {
                // Der Server lehnt den Titel ab (Duplikat). Als eigenen Typ werfen,
                // damit die UI den Nutzer gezielt hinweisen kann statt still zu bleiben.
                throw TitleConflictException(readBody(conn).take(500))
            }
            (code in 200..299) || code == 404
        } catch (e: TitleConflictException) {
            throw e
        } catch (e: Exception) {
            throw HermesApiException("PATCH /api/sessions fehlgeschlagen: ${e.message}", e)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * OpenAI-kompatibler Cloud-Stream (`/chat/completions`, `stream: true`).
     *
     * Unterschied zur Hermes-Variante ([sendMessageStream]): Der Body enthält das
     * Modell DIREKT als `model` (Cloud-Anbieter kennen kein `provider`-Feld), es
     * werden weder `X-Hermes-Session-Key`- noch Session-Id-Header gesetzt (stateless
     * Cloud-Chat) und es gibt kein Gateway-spezifisches `hermes.failed`-Signal.
     *
     * Das SSE-Parsing ist identisch (data-JSON, `delta.content` / `delta.reasoning_content`,
     * finish_reason, [DONE], keepalive). Callbacks laufen auf dem aufrufenden Thread —
     * der Aufrufer muss UI-Updates per runOnUiThread heben.
     *
     * @param baseUrl Basis-NUR bis vor "/chat/completions", z. B. "https://openrouter.ai/api/v1"
     * @param model   Modell-ID des Anbieters (Pflicht; ohne Modell kein Cloud-Request)
     * @return der vollständig akkumulierte Antworttext
     * @throws HermesApiException bei HTTP-Fehlern, Fehlern im Stream oder Netzabbrüchen
     */
    @Throws(HermesApiException::class)
    fun sendCloudStream(
        baseUrl: String,
        apiKey: String,
        history: List<Pair<String, String>>,
        model: String,
        provider: String = "",
        enabledToolSlugs: Set<String> = emptySet(),
        cancellationToken: StreamCancellation? = null,
        firstTokenTimeoutMs: Long = FIRST_TOKEN_READ_TIMEOUT_MS,
        streamTimeoutMs: Long = STREAM_READ_TIMEOUT_MS,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): String {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw HermesApiException("Anbieter-URL, API-Key oder Modell fehlt")
        }
        if (history.isEmpty() || history.last().first != "user") {
            throw HermesApiException("Ungültige Nachrichten-Historie")
        }

        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

        val messages = JSONArray()
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }

        val json = JSONObject()
            .put("stream", true)
            .put("model", model)
            .put("messages", messages)
        // Aktivierte Server-Tools (z. B. Websuche) aus der Registry anhängen.
        // Anbieterabhängig: Nur Tools, die der Anbieter über /v1/chat/completions
        // unterstützt, verschickt buildTools — nicht unterstützte werden übersprungen.
        // Wenn nichts SENDbares übrig bleibt (kein Tool aktiv oder Anbieter ohne
        // Support), bleibt der Request unverändert (kein "tools"-Feld), damit
        // OpenAI-kompatible Ziele ohne Tool-Unterstützung nicht scheitern.
        CloudTools.buildTools(enabledToolSlugs, provider)?.let { json.put("tools", it) }
        val body = json.toString()

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        cancellationToken?.connection = conn
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            conn.outputStream.use { out: OutputStream ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val raw = readBody(conn)
                throw HermesApiException("HTTP $code: ${raw.take(500)}")
            }

            val result = StringBuilder()
            val reasoning = StringBuilder()
            val reader = conn.inputStream.bufferedReader()
            var firstTokenReceived = false
            var completed = false
            val firstTokenDeadline = System.currentTimeMillis() + firstTokenTimeoutMs
            try {
                while (true) {
                    if (cancellationToken?.cancelled == true) {
                        throw HermesApiException("Antwort durch Nutzer abgebrochen")
                    }
                    if (!firstTokenReceived &&
                        System.currentTimeMillis() > firstTokenDeadline
                    ) {
                        throw HermesApiException(
                            "Keine Antwort vom Modell innerhalb von " +
                                "${firstTokenTimeoutMs / 1000}s"
                        )
                    }
                    conn.readTimeout =
                        if (firstTokenReceived) streamTimeoutMs.toInt() else firstTokenTimeoutMs.toInt()
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                    if (!trimmed.startsWith("data:")) continue
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") {
                        completed = true
                        break
                    }

                    val chunk = try {
                        JSONObject(payload)
                    } catch (e: Exception) {
                        continue
                    }
                    if (chunk.has("error")) {
                        val err = chunk.optJSONObject("error")
                        val msg = err?.optString("message") ?: chunk.toString()
                        throw HermesApiException("Stream-Fehler: $msg")
                    }

                    val choices = chunk.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: continue

                    if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                        val r = delta.optString("reasoning_content")
                        if (r.isNotEmpty()) {
                            firstTokenReceived = true
                            reasoning.append(r)
                            onReasoning(r)
                        }
                    }
                    if (delta.has("content") && !delta.isNull("content")) {
                        val c = delta.optString("content")
                        if (c.isNotEmpty()) {
                            firstTokenReceived = true
                            result.append(c)
                            onDelta(c)
                        }
                    }

                    if (!choice.isNull("finish_reason")) {
                        if (choice.optString("finish_reason") == "error") {
                            throw HermesApiException("Stream vom Anbieter mit finish_reason=error beendet")
                        }
                        completed = true
                        break
                    }
                }
                if (!completed) {
                    throw HermesApiException(
                        "Stream vorzeitig beendet (Antwort unvollständig, ${result.length} Zeichen empfangen)"
                    )
                }
            } finally {
                reader.close()
            }
            // Leere Antwort (kein Text-Content): tritt bei einem Server-Tool-Aufruf
            // (finish_reason=tool_calls) auf, wenn der Anbieter die Tool-Ausführung
            // nicht fortsetzt bzw. getrennt, oder bei reiner Reasoning-Ausgabe.
            // Keine gültige Nutzerantwort → als Fehler melden, damit der Aufrufer
            // (MainActivity) in den Fallback-Modellwechsel gehen kann, statt die
            // Nutzerfrage still zu verwerfen.
            if (result.length == 0) {
                throw HermesEmptyResponseException()
            }
            result.toString()
        } finally {
            cancellationToken?.connection = null
            conn.disconnect()
        }
    }

    /** Herkunfts-Kennung, unter der der Gateway Sessions aus /v1/chat/completions
     *  ablegt (= Sessions, die mit Hermi entstanden sind). */
    const val HERMI_SESSION_SOURCE = "api_server"

    /**
     * Prüft gezielt (`GET /api/sessions/{id}`), ob eine Gateway-Session existiert.
     * true = vorhanden (2xx), false = definitiv nicht vorhanden (404), null = unklar
     * (Netz-/Auth-/Serverfehler). Dient als Gegenprobe, bevor der Reverse-Sync eine
     * Session lokal löscht, weil sie in der (paginierten/gefilterten) Liste fehlt —
     * "nicht in der Liste" ist kein Beweis für "gelöscht" (archiviert, Limit, Filter).
     */
    fun sessionExists(baseUrl: String, apiKey: String, sessionId: String): Boolean? {
        if (baseUrl.isBlank() || apiKey.isBlank() || sessionId.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(baseUrl.trimEnd('/') + "/api/sessions/" + sessionId).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            when (conn.responseCode) {
                in 200..299 -> true
                404 -> false
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Metadaten einer Gateway-Session (ID + Titel), wie sie der API-Server über
     *  die Session-Liste (`GET /api/sessions`) liefert. Dient dem Reverse-Sync:
     *  beim Öffnen der Listenansicht gleicht die App ihre lokalen Sessions gegen
     *  diesen Server-Stand ab (Server gewinnt). */
    data class GatewaySession(
        val id: String,
        val title: String,
        val lastActive: Long = 0L,
        /** Herkunft der Session laut Gateway (`source`, z. B. "api_server", "telegram",
         *  "cli"). Hermi-Sessions entstehen über /v1/chat/completions → "api_server".
         *  null, wenn der Gateway das Feld nicht liefert (ältere Versionen). */
        val source: String? = null,
        /** Vom Gateway ausgeblendet/intern (hidden, delegate-Kind) → nicht importieren. */
        val hidden: Boolean = false,
        /** Nachrichtenanzahl laut Gateway; zusammen mit [lastActive] erkennt der
         *  Sync, ob sich eine Session geändert hat, ohne ihre Nachrichten zu laden. */
        val messageCount: Int = -1,
    )

    /**
     * Holt die Session-Liste des Gateways (`GET /api/sessions`) und liefert ID und
     * Titel jeder Session zurück. Folgt der Pagination (limit/offset/has_more), bis
     * alle Seiten geholt sind (maximal 10 Seiten als Schutzschleife). Ein Netz-
     * /HTTP-Fehler wird als HermesApiException geworfen — der Aufrufer entscheidet,
     * den Abgleich best-effort zu überspringen.
     */
    @Throws(HermesApiException::class)
    fun listGatewaySessions(baseUrl: String, apiKey: String): List<GatewaySession> {
        // Fehlende URL/fehlender Key ist KEINE „leere Server-Liste“: Der Reverse-Sync
        // würde sonst alle lokalen Sessions als serverseitig gelöscht werten und
        // entfernen (z. B. wenn der Key nicht entschlüsselbar ist). Daher als Fehler
        // melden — der Aufrufer überspringt den Abgleich dann.
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            throw HermesApiException("GET /api/sessions nicht möglich: URL oder API-Key fehlt")
        }
        val result = mutableListOf<GatewaySession>()
        var offset = 0
        val pageSize = 200
        var page = 0
        while (page < 10) {
            val endpoint = baseUrl.trimEnd('/') + "/api/sessions?limit=$pageSize&offset=$offset&source=$HERMI_SESSION_SOURCE"
            var conn: HttpURLConnection? = null
            val (items, hasMore) = try {
                conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw HermesApiException("GET /api/sessions fehlgeschlagen (HTTP $code)")
                }
                val root = JSONObject(readBody(conn))
                val arr = root.optJSONArray("data") ?: JSONArray()
                val pageItems = mutableListOf<GatewaySession>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isBlank()) continue
                    pageItems.add(
                        GatewaySession(
                            id = id,
                            title = o.optString("title", ""),
                            lastActive = serverTimeToMillis(o.optDouble("last_active", 0.0)),
                            source = o.optString("source", "").ifBlank { null },
                            hidden = o.optBoolean("hidden", false) || o.optBoolean("is_internal_child", false),
                            messageCount = o.optInt("message_count", -1),
                        )
                    )
                }
                pageItems to root.optBoolean("has_more", false)
            } catch (e: HermesApiException) {
                throw e
            } catch (e: Exception) {
                throw HermesApiException("GET /api/sessions fehlgeschlagen: ${e.message}", e)
            } finally {
                conn?.disconnect()
            }
            result.addAll(items)
            if (!hasMore || items.isEmpty()) break
            offset += pageSize
            page++
        }
        return result
    }

    /**
     * Holt die Nachrichten einer Gateway-Session (`GET /api/sessions/{id}/messages`,
     * `order=oldest`) und liefert sie chronologisch als ChatMessage-Liste. Dient dem
     * History-Backfill neu importierter Server-Sessions (Server gewinnt: die
     * komplette Historie der Quelle wird übernommen). Folgt der Genauigkeit des
     * Antwort-Objects: `data[]` wird Zeile für Zeile gelesen; die Reihenfolge ist
     * bereits aufsteigend. Bis zu 500 Nachrichten pro Seite, Pagination wird bis
     * `has_more=false` bzw. einer leeren Seite verfolgt (maximal 10 Seiten als
     * Schutzschleife). Netz-/HTTP-Fehler werden als HermesApiException geworfen.
     */
    @Throws(HermesApiException::class)
    fun fetchSessionMessages(baseUrl: String, apiKey: String, sessionId: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        var offset = 0
        val pageSize = 500
        var page = 0
        while (page < 10) {
            val endpoint = baseUrl.trimEnd('/') + "/api/sessions/$sessionId/messages?limit=$pageSize&offset=$offset&order=oldest"
            var conn: HttpURLConnection? = null
            var rawCount = 0 // Roh-Anzahl der Seite (vor dem Filtern) für die Paginierung
            val items = try {
                conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw HermesApiException("GET /api/sessions/$sessionId/messages fehlgeschlagen (HTTP $code)")
                }
                val root = JSONObject(readBody(conn))
                val arr = root.optJSONArray("data") ?: JSONArray()
                val pageItems = mutableListOf<ChatMessage>()
                rawCount = arr.length()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    // Nur echte Konversationsrollen übernehmen: tool-/system-Einträge
                    // würden sonst als Hermes-Bubble erscheinen und bei der nächsten
                    // Anfrage im messages-Array an das Modell zurückgehen.
                    val role = o.optString("role", "assistant")
                    if (role != "user" && role != "assistant") continue
                    val content = o.optString("content", "").trim()
                    // Leere Inhalte (z. B. reine Tool-/System-Einträge) würden die
                    // lokale Historie aufblähen und im MessageAdapter sinnlos
                    // rendern → überspringen.
                    if (content.isEmpty()) continue
                    pageItems.add(
                        ChatMessage(
                            role = role,
                            content = content,
                            timestamp = serverTimeToMillis(o.optDouble("timestamp", 0.0)),
                        )
                    )
                }
                pageItems
            } catch (e: HermesApiException) {
                throw e
            } catch (e: Exception) {
                throw HermesApiException("GET /api/sessions/$sessionId/messages fehlgeschlagen: ${e.message}", e)
            } finally {
                conn?.disconnect()
            }
            result.addAll(items)
            // Paginierung anhand der ROH-Seitengröße: gefilterte (leere/Tool-)Einträge
            // dürfen eine volle Seite nicht wie die letzte aussehen lassen.
            if (rawCount < pageSize) break
            offset += pageSize
            page++
        }
        return result
    }

    /** Wandelt einen Server-Zeitstempel (epoch Sekunden als Float, z. B. 1792320660.78)
     *  in Epoch-Millis um, wie sie das App-Modell durchgehend verwendet. Bereits in
     *  Millis vorliegende Werte (Betrag >= 100_000_000_000 ≈ Nov 1973) bleiben unverändert. */
    private fun serverTimeToMillis(seconds: Double): Long {
        if (seconds <= 0) return System.currentTimeMillis()
        return if (seconds < 100_000_000_000L) (seconds * 1000).toLong() else seconds.toLong()
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        } catch (e: Exception) {
            null
        }
        if (stream == null) return ""
        return stream.bufferedReader().use(BufferedReader::readText)
    }

    open class HermesApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Antwort endete ohne Text-Content (nur Reasoning und/oder finish_reason=
     *  tool_calls, kein delta.content). Keine gültige Nutzerantwort: muss trotz
     *  evtl. angekommener Reasoning-Token wie ein Fehler für den Fallback-
     *  Modellwechsel behandelt werden (Reasoning allein ist kein Text). Wird in
     *  MainActivity von generischen Stream-Fehlern unterschieden, damit es nicht
     *  als „Teilantwort behalten“ endet. */
    class HermesEmptyResponseException : HermesApiException(
        "Anbieter lieferte eine leere Antwort (kein Text-Inhalt)"
    )

    /** Vom Gateway per `hermes.failed=true` signalisierter fehlgeschlagener Turn
     *  (Modell nicht verfügbar). Trennt diesen Fall von einem echten Verbindungs-
     *  /Stream-Fehler, damit die UI eine deutsche Meldung mit „Erneut senden“-
     *  Button zeigen kann statt die englische Server-Notiz als Antwort stehen
     *  zu lassen. */
    class HermesFailedTurnException(message: String) : HermesApiException(message)

    /** Vom Gateway per HTTP 400 signalisierter abgelehnter Titel (Duplikat bzw.
     *  Validierungsfehler). Trennt diesen Fall von einem generischen Netzwerk-
     *  fehler, damit die UI den Nutzer gezielt hinweisen kann. */
    class TitleConflictException(message: String) : HermesApiException(message)
}