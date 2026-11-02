package de.hermi.voice.config

/**
 * Ein Bot-Profil: eigener API-Key (ein Hermes-Bot) plus eigener Modellwahl.
 *
 * Der API-Key wird hier im Klartext gehalten, solange er sich im Speicher
 * bewegt; die AES-256-GCM-Verschlüsselung an der Persistenz-Grenze übernimmt
 * ausschließlich AppConfig (über CryptoManager) — diese Datenklasse selbst
 * ist bewusst frei davon.
 *
 * Mit einem Bot wird gegenüber dem Gateway der API-Key gewechselt. Die
 * Anbindung ist standardmäßig der globale Gateway-Präfix /p/<name>; optional
 * kann ein Bot eine eigene Basis-URL/Port (Docker-Standalone-Gateway, Modus B)
 * über `serverBase` mitbringen. Ein Bot ohne eingetragenen Key ist nicht
 * nutzbar (`isUsable() == false`) und erscheint im Wechsel-Dropdown deaktiviert.
 */
class BotProfile(
    val id: String,
    var name: String,
    var apiKey: String = "",
    /** Eigene Basis-URL/Port eines Docker-Standalone-Gateways (Modus B), z. B.
     *  "http://192.168.1.101:8643". Leer = globale Prefix-Route /p/<name>. */
    var serverBase: String = "",
    var primaryProvider: String = "",
    var primaryModel: String = "",
    var fallbackProvider: String = "",
    var fallbackModel: String = "",
    /** Anzeige-Avatar der Bot-Antworten: "face" (Standard) oder "robot". */
    var avatar: String = "robot"
) {

    /** true, sobald ein primäres Modell für diesen Bot konfiguriert ist. */
    fun hasPrimaryModel(): Boolean = primaryProvider.isNotBlank() && primaryModel.isNotBlank()

    /** true, sobald ein Fallback-Modell für diesen Bot konfiguriert ist. */
    fun hasFallbackModel(): Boolean = fallbackProvider.isNotBlank() && fallbackModel.isNotBlank()

    /** Das vollständige primäre Modell-Paar als Pair(provider, model) oder null. */
    fun primaryModelPair(): Pair<String, String>? =
        if (hasPrimaryModel()) Pair(primaryProvider, primaryModel) else null

    /** Das vollständige Fallback-Modell-Paar oder null. */
    fun fallbackModelPair(): Pair<String, String>? =
        if (hasFallbackModel()) Pair(fallbackProvider, fallbackModel) else null

    /** Ein Bot ist erst nutzbar, wenn ein API-Key eingetragen wurde. */
    fun isUsable(): Boolean = apiKey.isNotBlank()
}