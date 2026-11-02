package de.hermi.voice.config

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Liest die am Hermes-Gateway verfügbaren Provider + Modelle über den
 * Endpoint `GET /api/model/options` (Bearer-Auth) ein und hält sie als
 * einfachen In-Memory-Cache vor.
 *
 * Antwortformat (live verifiziert):
 *   { providers: [ { slug, name, authenticated, models: [ ... ] }, ... ] }
 *
 * Nur authentifizierte Provider mit mindestens einem Modell werden übernommen;
 * nicht authentifizierte (z. B. ein späterer Ollama-Provider ohne Key) werden
 * gefiltert, damit die Auswahlliste nie tote Einträge zeigt.
 */
class ModelOptionsFetcher private constructor() {

    /** Ein Provider mit seinem Modell-Label in der Anzeige: "name (slug)". */
    class Provider(val slug: String, val name: String, var models: List<String>)

    private var cached: List<Provider>? = null

    /** Liefert die (ggf. gecachte) Provider-/Modellliste. */
    fun getProviders(): List<Provider>? = cached?.map { Provider(it.slug, it.name, it.models) }

    /**
     * Lädt die Liste frisch vom Gateway. Bei Erfolg wird der Cache ersetzt.
     *
     * @throws Exception bei Netz-/Parsing-/Auth-Fehlern — der Caller entscheidet
     *                   über die Fehlerdarstellung (Statuszeile o. Ä.).
     */
    @Throws(Exception::class)
    fun refresh(baseUrl: String, apiKey: String) {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            throw IllegalArgumentException("Server-URL oder API-Key fehlt")
        }
        val endpoint = baseUrl.trimEnd('/') + "/api/model/options"
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
            val arr = root.optJSONArray("providers") ?: throw IllegalStateException("kein 'providers'-Feld")
            val result = ArrayList<Provider>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val authenticated = p.optBoolean("authenticated", false)
                if (!authenticated) continue
                val slug = p.optString("slug", "").trim()
                val name = p.optString("name", slug).trim()
                val models = p.optJSONArray("models")
                val modelList = ArrayList<String>()
                if (models != null) {
                    for (j in 0 until models.length()) {
                        val m = models.optString(j, "").trim()
                        if (m.isNotEmpty()) modelList.add(m)
                    }
                }
                if (slug.isNotEmpty() && modelList.isNotEmpty()) {
                    result.add(Provider(slug, name, modelList))
                }
            }
            if (result.isEmpty()) {
                throw IllegalStateException("Keine Modelle vom Gateway geliefert")
            }
            cached = result
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

    companion object {
        /** Prozessweiter Singleton; der Cache lebt so lange wie die App. */
        @Volatile
        private var instance: ModelOptionsFetcher? = null
        fun get(): ModelOptionsFetcher =
            instance ?: synchronized(this) {
                instance ?: ModelOptionsFetcher().also { instance = it }
            }
    }
}