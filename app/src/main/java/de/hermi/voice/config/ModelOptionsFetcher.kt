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

    /** Reasoning-Fähigkeiten eines Modells laut Gateway (`/api/model/options`). */
    data class ModelCaps(
        /** Modell kann denken. */
        val reasoning: Boolean,
        /** Denken lässt sich abschalten (Cloud-Modelle liefern das mit; Default true). */
        val canDisableReasoning: Boolean
    )

    /** Ein Provider mit seinem Modell-Label in der Anzeige: "name (slug)". */
    class Provider(
        val slug: String,
        val name: String,
        var models: List<String>,
        /** true = lokal betriebener Endpunkt (user-config/api_url im eigenen LAN). */
        val local: Boolean = false,
        /** Modell-ID → Fähigkeiten (leer, wenn das Gateway nichts dazu liefert). */
        val capabilities: Map<String, ModelCaps> = emptyMap()
    )

    private var cached: List<Provider>? = null

    /** Liefert die (ggf. gecachte) Provider-/Modellliste. */
    fun getProviders(): List<Provider>? = cached?.map {
        Provider(it.slug, it.name, it.models, it.local, it.capabilities)
    }

    /**
     * true/false, wenn der Provider aus der Gateway-Liste bekannt ist (lokal
     * betriebener Endpunkt = `is_user_defined`/`source: "user-config"`), sonst
     * null (= unbekannt, der Aufrufer entscheidet über eine Ersatz-Heuristik).
     */
    fun isLocalProvider(slug: String): Boolean? =
        cached?.firstOrNull { it.slug == slug }?.local

    /** Fähigkeiten eines Modells, sofern das Gateway sie gemeldet hat. */
    fun capabilitiesFor(providerSlug: String, model: String): ModelCaps? =
        cached?.firstOrNull { it.slug == providerSlug }?.capabilities?.get(model)

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
                    // Lokal betriebener Endpunkt: eigener Provider aus der config.yaml
                    // (ollama, llama.cpp, Strata …) — im Gegensatz zu den eingebauten
                    // Cloud-Anbietern. Nur so kann die App den lokalen vom Cloud-Schalter
                    // trennen, ohne Anbieter-Namen fest einzubauen.
                    val local = p.optBoolean("is_user_defined", false) ||
                        p.optString("source", "").trim() == "user-config"
                    // Reasoning-Fähigkeiten je Modell (reasoning / can_disable_reasoning).
                    val caps = mutableMapOf<String, ModelCaps>()
                    val capsObj = p.optJSONObject("capabilities")
                    if (capsObj != null) {
                        val keys = capsObj.keys()
                        while (keys.hasNext()) {
                            val modelId = keys.next()
                            val c = capsObj.optJSONObject(modelId) ?: continue
                            caps[modelId] = ModelCaps(
                                reasoning = c.optBoolean("reasoning", false),
                                canDisableReasoning = c.optBoolean("can_disable_reasoning", true)
                            )
                        }
                    }
                    result.add(Provider(slug, name, modelList, local, caps))
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