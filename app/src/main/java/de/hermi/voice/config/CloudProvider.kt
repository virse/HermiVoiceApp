package de.hermi.voice.config

/**
 * Ein Cloud-KI-Anbieter (OpenAI-kompatibel, /v1/chat/completions): eigener
 * API-Key plus je eine primäre und eine Fallback-Modellwahl.
 *
 * Der API-Key wird hier im Klartext gehalten, solange er sich im Speicher
 * bewegt; die AES-256-GCM-Verschlüsselung an der Persistenz-Grenze übernimmt
 * ausschließlich AppConfig (über CryptoManager) — diese Datenklasse selbst
 * ist bewusst frei davon.
 *
 * Die Basis-URL wird NICHT vom Nutzer eingegeben, sondern kommt fix aus der
 * Provider-Tabelle ([CloudProviders.providers]); das Dropdown wählt nur den
 * Anbieter. Cloud-Kanäle sind zustandslos: keine Session-Verwaltung, kein
 * Gateway-Sync, kein Session-Speicher des Hermes-Profils.
 */
class CloudProvider(
    val id: String,
    var name: String,
    /** Slug des Anbieters in [CloudProviders.providers], z. B. "openrouter". */
    var provider: String,
    var apiKey: String = "",
    /** Primäres Modell (Modell-ID des Anbieters). Leer = kein Cloud-Request möglich. */
    var primaryModel: String = "",
    /** Fallback-Modell; leer = kein Fallback. */
    var fallbackModel: String = "",
    /** Aktivierte Server-Tools (Slugs aus [CloudTools.available]). Leer = ohne Tools.
     *  Der Cloud-Direktkanal sendet dann KEIN "tools"-Array (unverändertes Verhalten). */
    var tools: Set<String> = emptySet()
) {

    /** Basis-URL des Anbieters aus der fixen Provider-Tabelle. */
    fun baseUrl(): String = CloudProviders.baseUrl(provider)

    /** Anzeigename des Anbieters (aus der Tabelle). */
    fun providerName(): String = CloudProviders.providerName(provider)

    /** Primäres Modell-Paar (provider, model) als Pair oder null. */
    fun primaryPair(): Pair<String, String>? =
        if (primaryModel.isNotBlank()) Pair(provider, primaryModel) else null

    /** Fallback-Modell-Paar oder null. */
    fun fallbackPair(): Pair<String, String>? =
        if (fallbackModel.isNotBlank()) Pair(provider, fallbackModel) else null

    /** Ein Cloud-Anbieter ist erst nutzbar, wenn API-Key UND primäres Modell
     *  gesetzt sind (ohne Modell ist kein Cloud-Request möglich). */
    fun isUsable(): Boolean = apiKey.isNotBlank() && primaryModel.isNotBlank()
}

/** Feste Tabelle der OpenAI-kompatiblen Cloud-Anbieter: Slug → Anzeigename + Basis-URL.
 *  Bewusst nur Anbieter mit `/v1/chat/completions` (Anthropic fällt raus: andere API). */
object CloudProviders {

    /** Slug → (Anzeigename, Basis-URL bis vor "/chat/completions"). */
    val providers: LinkedHashMap<String, Pair<String, String>> = linkedMapOf(
        "openrouter" to ("OpenRouter" to "https://openrouter.ai/api/v1"),
        "openai" to ("OpenAI" to "https://api.openai.com/v1"),
        "groq" to ("Groq" to "https://api.groq.com/openai/v1"),
        "mistral" to ("Mistral" to "https://api.mistral.ai/v1"),
        "deepseek" to ("DeepSeek" to "https://api.deepseek.com/v1"),
    )

    /** Anzeigename zu einem Slug; unbekannter Slug → der Slug selbst. */
    fun providerName(slug: String): String = providers[slug]?.first ?: slug

    /** Basis-URL zu einem Slug; unbekannter Slug → leer. */
    fun baseUrl(slug: String): String = providers[slug]?.second?.trimEnd('/') ?: ""

    /** Slugs in Tabellen-Reihenfolge. */
    fun allSlugs(): List<String> = providers.keys.toList()

    /** Pfad der OpenAI-kompatiblen Modell-Liste (öffnet /v1/models). */
    private const val MODELS_PATH = "/models"

    /** Voller /models-Endpoint eines Anbieters. */
    fun modelsEndpoint(slug: String): String = baseUrl(slug).trimEnd('/') + MODELS_PATH
}