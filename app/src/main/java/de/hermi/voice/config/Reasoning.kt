package de.hermi.voice.config

import org.json.JSONObject

/**
 * Reasoning-Steuerung (An/Aus + Denk-Stufe) für die beiden Wege der App:
 *
 *  - Hermes-Kanal (Gateway, `/v1/chat/completions`): Der Request trägt
 *    `model_options: {"reasoning": {"enabled": …, "effort": …}}`. Das Gateway
 *    übersetzt das pro Anbieter (lokale Endpunkte: `reasoning_effort` bzw.
 *    Ollama `think`; Cloud: anbieterspezifische Denk-Parameter).
 *  - Direkter Cloud-Kanal (`sendCloudStream`): Die App spricht den Anbieter
 *    selbst an und muss deshalb das anbieterspezifische Wire-Format bauen
 *    (OpenRouter `reasoning{}`, DeepSeek `thinking{}`, sonst `reasoning_effort`).
 *
 * Grundsätze:
 *  - Ein leeres [LEVEL_DEFAULT] heißt „Standard": es wird KEIN Effort
 *    mitgeschickt, das Modell bzw. der Agent entscheidet. Damit bleibt das
 *    Verhalten unverändert, bis der Nutzer bewusst eine Stufe wählt.
 *  - „Aus" wird nur gesendet, wo der Kanal/das Modell es beherrscht. Kann ein
 *    Modell Reasoning nicht abschalten (`can_disable_reasoning = false` bzw.
 *    Anbieter ohne Aus-Schalter), bleibt es an — nichts wird geschickt.
 *  - Lokal- und Cloud-Schalter sind unabhängig: welcher greift, entscheidet das
 *    Modell, das den Turn tatsächlich fährt (auch beim Fallback-Modell).
 */
object Reasoning {

    /** Geltungs-Modus (Bots/Cloud-Einträge): globalen Schalter erben. */
    const val MODE_INHERIT = "inherit"
    const val MODE_ON = "on"
    const val MODE_OFF = "off"

    /** Sentinel „Standard": kein Effort mitschicken (Agent-/Modell-Default). */
    const val LEVEL_DEFAULT = "default"

    /** Vorbelegung, solange der Nutzer keine Stufe gewählt hat. */
    const val DEFAULT_LEVEL = "medium"

    /** true = Stufe wird nicht gesendet (leer oder Sentinel). */
    fun isDefaultLevel(effort: String): Boolean = effort.isBlank() || effort == LEVEL_DEFAULT

    /**
     * Stufen des Hermes-Kanals. Das Gateway kennt die volle OpenAI-kompatible
     * Leiter und klemmt sie pro Anbieter auf das, was dort ankommt (`none` fehlt
     * bewusst: „kein Reasoning" wird über enabled=false gesteuert).
     */
    val HERMES_LEVELS: List<String> = listOf("minimal", "low", "medium", "high", "xhigh", "max")

    /**
     * Ergebnis der Auflösung für genau einen Request.
     * [enabled] = Denken an/aus, [effort] = Stufe ("" = Standard).
     */
    data class Decision(val enabled: Boolean, val effort: String = LEVEL_DEFAULT)

    // ── Anbieter-Tabelle des direkten Cloud-Kanals ─────────────────────────
    // Quellen: OpenRouter-Reasoning-Parameter (effort/enabled), DeepSeek
    // `thinking.type`, Groq `reasoning_effort` (modellabhängig), Mistral
    // `reasoning_effort` mit nur "high"/"none", OpenAI `reasoning_effort`
    // (kein dokumentiertes „aus" → Schalter bleibt an).
    /** Was ein Cloud-Anbieter bei einem Modell an Reasoning-Steuerung kann. */
    data class Support(
        /** false = Denken lässt sich dort nicht abschalten. */
        val canDisable: Boolean,
        /** Angebotene Stufen; leer = nur An/Aus (Anbieter kennt keine Leiter). */
        val levels: List<String> = emptyList(),
        /** Fester Effort-Wert, wenn „An" ohne Stufe gesendet wird (Mistral: high). */
        val onEffort: String = ""
    )

    private val DIRECT_SUPPORT: Map<String, Support> = mapOf(
        "openrouter" to Support(
            canDisable = true,
            levels = listOf("minimal", "low", "medium", "high", "xhigh", "max")
        ),
        "openai" to Support(
            canDisable = false,
            levels = listOf("minimal", "low", "medium", "high")
        ),
        "mistral" to Support(canDisable = true, onEffort = "high"),
        "deepseek" to Support(canDisable = true)
    )

    /**
     * Was der Anbieter bei diesem Modell kann. Groq ist modellabhängig: `none`
     * (aus) nur bei Qwen-3.8-27B, Stufen nur bei gpt-oss. Liefert null, wenn
     * für den Anbieter gar keine Steuerung bekannt ist.
     */
    fun directSupport(providerSlug: String, model: String): Support? {
        val m = model.lowercase()
        return when (providerSlug) {
            "groq" -> when {
                m.contains("gpt-oss") -> Support(
                    canDisable = false, levels = listOf("low", "medium", "high", "xhigh")
                )
                m.contains("qwen") -> Support(canDisable = true)
                else -> Support(canDisable = false)
            }
            else -> DIRECT_SUPPORT[providerSlug]
        }
    }

    /** Stufen, die der direkte Cloud-Kanal für dieses Modell anbietet. */
    fun directLevels(providerSlug: String, model: String): List<String> =
        directSupport(providerSlug, model)?.levels ?: emptyList()

    /** true, wenn der direkte Kanal für dieses Modell überhaupt etwas kann. */
    fun directConfigurable(providerSlug: String, model: String): Boolean =
        directSupport(providerSlug, model) != null

    // ── Auflösung ──────────────────────────────────────────────────────────

    /**
     * Entscheidung für den Hermes-Kanal. null = gar nichts senden (Modell kann
     * laut Gateway-Angaben kein Reasoning, oder die Angaben fehlen).
     *
     * @param providerSlug Provider aus der Modellwahl (z. B. "ollama")
     * @param model        Modell-ID des laufenden Versuchs
     * @param bot          aktiver Bot (Override) oder null = globaler Schalter
     */
    fun hermesDecision(
        config: AppConfig,
        fetcher: ModelOptionsFetcher,
        bot: BotProfile?,
        providerSlug: String,
        model: String
    ): Decision? {
        val caps = fetcher.capabilitiesFor(providerSlug, model)
        // Modell kann laut Gateway nicht denken → keine Reasoning-Parameter.
        if (caps != null && !caps.reasoning) return null
        val local = fetcher.isLocalProvider(providerSlug) ?: looksLocal(model)
        val canDisable = local || (caps?.canDisableReasoning ?: true)
        val mode = if (local) {
            bot?.localReasoningMode ?: MODE_INHERIT
        } else {
            bot?.cloudReasoningMode ?: MODE_INHERIT
        }
        val enabled = when (mode) {
            MODE_ON -> true
            MODE_OFF -> false
            else -> if (local) config.reasoningLocalEnabled else config.reasoningCloudEnabled
        }
        // „Aus" nicht sendbar, wenn der Anbieter das Denken nicht abschalten kann.
        val on = if (!canDisable) true else enabled
        val effort = if (local) {
            bot?.localReasoningEffort?.takeIf { it.isNotBlank() }
                ?: config.reasoningLocalEffort
        } else {
            bot?.cloudReasoningEffort?.takeIf { it.isNotBlank() }
                ?: config.reasoningCloudEffort
        }
        return Decision(enabled = on, effort = if (on) effort else LEVEL_DEFAULT)
    }

    /** Entscheidung für den direkten Cloud-Kanal (je Cloud-Eintrag übersteuerbar). */
    fun directDecision(config: AppConfig, entry: CloudProvider, model: String): Decision? {
        val support = directSupport(entry.provider, model) ?: return null
        val mode = entry.reasoningMode
        val enabled = when (mode) {
            MODE_ON -> true
            MODE_OFF -> false
            else -> config.reasoningDirectEnabled
        }
        val on = if (!support.canDisable) true else enabled
        // Stufen nur setzen, wenn der Anbieter sie kennt und das Modell sie stützt.
        val effort = if (!on) LEVEL_DEFAULT else when {
            support.levels.isEmpty() -> support.onEffort
            else -> entry.reasoningEffort.takeIf { it.isNotBlank() && it in support.levels }
                ?: config.reasoningDirectEffort.takeIf { it in support.levels }
                ?: LEVEL_DEFAULT
        }
        return Decision(enabled = on, effort = effort)
    }

    // ── Wire-Format ────────────────────────────────────────────────────────

    /**
     * `model_options`-Objekt für den Hermes-Kanal. null = Feld nicht setzen.
     * „An" ohne Stufe sendet nur `enabled: true`; mit Stufe zusätzlich `effort`.
     */
    fun hermesModelOptions(decision: Decision?): JSONObject? {
        if (decision == null) return null
        val reasoning = JSONObject().put("enabled", decision.enabled)
        if (decision.enabled && !isDefaultLevel(decision.effort)) {
            reasoning.put("effort", decision.effort)
        }
        return JSONObject().put("reasoning", reasoning)
    }

    /**
     * Anbieterspezifische Reasoning-Felder für den direkten Cloud-Kanal.
     * null = nichts mitsenden (Request bleibt unverändert).
     */
    fun directExtras(providerSlug: String, model: String, decision: Decision?): JSONObject? {
        if (decision == null) return null
        val support = directSupport(providerSlug, model) ?: return null
        return when (providerSlug) {
            // OpenRouter normalisiert über das reasoning-Objekt; "none" = aus.
            "openrouter" -> {
                val r = JSONObject()
                if (!decision.enabled) {
                    r.put("effort", "none")
                } else {
                    r.put("enabled", true)
                    if (!isDefaultLevel(decision.effort)) r.put("effort", decision.effort)
                }
                JSONObject().put("reasoning", r)
            }
            // DeepSeek schaltet über thinking.type; Stufen sind dort nicht dokumentiert.
            "deepseek" -> JSONObject().put(
                "thinking", JSONObject().put("type", if (decision.enabled) "enabled" else "disabled")
            )
            // Mistral kennt nur high/none.
            "mistral" -> JSONObject().put("reasoning_effort", if (decision.enabled) "high" else "none")
            // Groq: none nur bei Qwen, Stufen nur bei gpt-oss.
            "groq" -> {
                if (!decision.enabled) {
                    if (!support.canDisable) return null
                    JSONObject().put("reasoning_effort", "none")
                } else {
                    if (!isDefaultLevel(decision.effort)) JSONObject().put("reasoning_effort", decision.effort)
                    else null
                }
            }
            // OpenAI: nur Stufen, kein dokumentiertes „aus".
            "openai" -> if (!decision.enabled) null else {
                if (!isDefaultLevel(decision.effort)) JSONObject().put("reasoning_effort", decision.effort)
                else null
            }
            else -> null
        }
    }

    /** Grobe Lokal-Erkennung, wenn die Gateway-Modellliste (noch) nichts dazu sagt. */
    fun looksLocal(model: String): Boolean = model.lowercase().contains(".gguf")
}
