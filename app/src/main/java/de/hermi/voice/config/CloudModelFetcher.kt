package de.hermi.voice.config

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Liest die Modell-Liste eines OpenAI-kompatiblen Cloud-Anbieters über dessen
 * `/v1/models`-Endpoint ein (Standard-Format: `{ data: [ { id, ... }, ... ] }`).
 *
 * Liefert die Modelle als einzelner [ModelOptionsFetcher.Provider], damit der
 * bestehende [ModelPickerDialog] (Favoriten, Suche, Dedup) unverändert
 * wiederverwendet werden kann. Akzeptiert auch die per `models:`-Schema
 * eingeräumten Endpoints vieler Anbieter (OpenRouter, Groq …), die
 * `data[].id` liefern.
 */
object CloudModelFetcher {

    /** Slug des Anbieters, gegen den zuletzt erfolgreich geladen wurde. */
    @Volatile
    private var cachedFor: String = ""

    @Volatile
    private var cachedProvider: ModelOptionsFetcher.Provider? = null

    /** Die (ggf. gecachte) Modellliste des Anbieters. */
    fun getProvider(slug: String): ModelOptionsFetcher.Provider? =
        if (cachedFor == slug) cachedProvider else null

    /**
     * Lädt die Modell-Liste des Anbieters frisch vom Endpoint. Bei Erfolg wird der Cache
     * ersetzt; ein leerer Antwortdatensatz wirft einen Fehler.
     *
     * @throws Exception bei Netz-/Parsing-/Auth-Fehlern — der Caller entscheidet
     *                   über die Fehlerdarstellung.
     */
    @Throws(Exception::class)
    fun refresh(slug: String, apiKey: String) {
        val endpoint = CloudProviders.modelsEndpoint(slug)
        if (endpoint.isBlank() || apiKey.isBlank()) {
            throw IllegalArgumentException("Anbieter oder API-Key fehlt")
        }
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            val code = conn.responseCode
            val raw = readBody(conn)
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${raw.take(300)}")
            }

            val root = JSONObject(raw)
            val arr = root.optJSONArray("data")
                ?: throw IllegalStateException("kein 'data'-Feld in der Modell-Liste")
            val models = java.util.ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "").trim()
                if (id.isNotEmpty()) models.add(id)
            }
            if (models.isEmpty()) {
                throw IllegalStateException("Keine Modelle vom Anbieter geliefert")
            }
            // Dedup & sortieren: viele Endpoints liefern Modelle mehrfach oder unsortiert.
            val unique = models.distinct().sorted()
            cachedFor = slug
            cachedProvider = ModelOptionsFetcher.Provider(
                slug, CloudProviders.providerName(slug), unique
            )
        } finally {
            conn.disconnect()
        }
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
}