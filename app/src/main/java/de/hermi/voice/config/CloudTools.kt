package de.hermi.voice.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry der verfügbaren Cloud-Tools (Anbieter-seitige Server-Tools).
 *
 * Jedes Tool hat einen eindeutigen [slug] (persistiert im CloudProvider als
 * aktivierbare Tool-Liste), einen Anzeigenamen für die UI und — entscheidend —
 * eine anbieterspezifische Zuordnung [providerType]: Anbieter-Slug → nativer
 * Server-Tool-Typ, mit dem das Tool im "tools"-Array des Requests angeboten wird.
 *
 * Nicht jeder Anbieter unterstützt jedes Tool über den /v1/chat/completions-
 * Endpoint (den die App ausschließlich nutzt). Anbieter ohne passendes Server-
 * Tool tauchen im Mapping schlicht nicht auf — für sie wird das Tool im Request
 * NICHT verschickt (kein kaputter Request) und in der UI ausgegraut.
 *
 * Erweiterung um weitere Tools: hier nur einen neuen Eintrag zu [available]
 * hinzufügen (slug, label, hint + Anbieter-Typen). UI (Checkboxen im
 * Cloud-Editor), Persistenz (AppConfig/ConfigBackup) und der Request-Bau
 * (HermesApiSender) arbeiten alle über diese Tabelle und brauchen KEINEN
 * weiteren Eingriff — neue Tools tauchen damit automatisch überall auf.
 */
object CloudTools {

    /**
     * Eine Tool-Definition. [providerType] mappt Anbieter-Slug (aus
     * [CloudProviders.providers]) auf den nativen Server-Tool-Typ; ein Anbieter
     * ohne Mapping unterstützt das Tool über diesen Endpoint nicht.
     * [labelRes] / [hintRes] sind die UI-Ressourcen (Techname + Kurzhinweis).
     */
    data class CloudToolDef(
        val slug: String,
        val labelRes: Int,
        val hintRes: Int,
        val providerType: Map<String, String>
    )

    /** Alle verfügbaren Cloud-Tools. */
    val available: List<CloudToolDef> = listOf(
        CloudToolDef(
            slug = "web_search",
            labelRes = de.hermi.voice.R.string.cloud_tool_web_search_label,
            hintRes = de.hermi.voice.R.string.cloud_tool_web_search_hint,
            // Server-Tool-Typen pro Anbieter (nur /v1/chat/completions-tauglich):
            // OpenRouter: openrouter:web_search (Server-Tool)
            // DeepSeek: web_search (nur deepseek-v4-pro; bei anderen Modellen ignoriert)
            providerType = mapOf(
                "openrouter" to "openrouter:web_search",
                "deepseek" to "web_search"
            )
        )
    )

    val bySlug: Map<String, CloudToolDef> = available.associateBy { it.slug }

    /** Ob das Tool über den [providerSlug]-Endpoint überhaupt verfügbar ist. */
    fun isEnabledFor(providerSlug: String, toolSlug: String): Boolean =
        (bySlug[toolSlug]?.providerType?.get(providerSlug)?.isNotBlank()) ?: false

    /** Anbieter-Slugs, die das Tool unterstützen (mindestens einen). */
    fun supportingProviders(toolSlug: String): Set<String> =
        bySlug[toolSlug]?.providerType?.keys ?: emptySet()

    /**
     * Baut das anbieterseitige "tools"-Array für die aktivierten Tool-Slugs.
     * Pro aktiviertem Tool wird NUR ein Eintrag erzeugt, wenn der [providerSlug]
     * es unterstützt (sonst still übersprungen — dieser Anbieter kann das Tool
     * über seinen /v1/chat/completions-Endpoint nicht). Liefert null, wenn nichts
     * aktiviert oder nichts sendbar ist — dann wird kein "tools"-Feld gesetzt.
     */
    fun buildTools(enabledSlugs: Collection<String>, providerSlug: String): JSONArray? {
        val result = JSONArray()
        for (slug in enabledSlugs) {
            val def = bySlug[slug] ?: continue
            val nativeType = def.providerType[providerSlug]
                ?.takeIf { it.isNotBlank() } ?: continue // Anbieter ohne Support
            result.put(JSONObject().put("type", nativeType))
        }
        return if (result.length() == 0) null else result
    }
}