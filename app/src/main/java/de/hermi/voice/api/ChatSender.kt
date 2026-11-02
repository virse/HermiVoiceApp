package de.hermi.voice.api

import de.hermi.voice.config.AppConfig
import de.hermi.voice.config.BotProfile
import de.hermi.voice.session.ChatSession
import java.net.URLEncoder
import java.util.Locale

data class ActiveSendProfile(
    val serverUrl: String,
    val apiKey: String,
    val primary: Pair<String, String>?,
    val fallback: Pair<String, String>?
)

/**
 * Sende-Routing: bestimmt, an welchen Server/Key/Modelle ein Chat bzw. eine
 * Gateway-Operation geht. Aus MainActivity herausgelöst (ohne UI-Zugriff);
 * Verhalten unverändert.
 */
class ChatSender(private val config: AppConfig) {

    /** Strippt das veraltete führende [provider/model]-Präfix (z. B.
     *  "[openrouter/nvidia/...] " oder "[custom:ollama/...] ") von einem
     *  assistant-Turn, das frühere App-Versionen vor Antworten stellten.
     *  Wird nur beim Senden angewendet, damit das Modell den Stil der
     *  Althistorie nicht imitiert; die Anzeige bleibt unverändert. */
    fun stripModelPrefix(content: String): String =
        content.replace(Regex("^\\[[^\\]]+\\]\\s*"), "")

    /** Löst die Ziel-Basis (URL) und den passenden API-Key für Gateway-Operationen
     *  (Löschen, Umbenennen) einer Session auf. Läuft die Session über einen
     *  Docker-Bot mit eigener Basis (Modus B), wird gegen genau diese Basis + den
     *  Key des passenden Bots geschickt; sonst gegen den globalen Gateway + den
     *  globalen Key. Liefert ein Paar aus URL und Key. */
    fun gatewayTarget(session: ChatSession): Pair<String, String> {
        val base = effectiveBase(session)
        val url = base ?: config.serverUrl
        val key = if (base != null) {
            // Docker-Bot (eigene Basis) ODER Profil-Bot (…/p/<name>) über seine aufgelöste Basis finden.
            config.getBots().firstOrNull {
                it.serverBase.trimEnd('/') == base.trimEnd('/') || botServerBase(it).trimEnd('/') == base.trimEnd('/')
            }?.apiKey ?: config.apiKey
        } else config.apiKey
        return url to key
    }

    /** Basis, gegen die die Session-Verwaltung (Liste/Löschen/Umbenennen) läuft:
     *  gespeicherte Docker-Basis, sonst bei Profil-Bots deren …/p/<name>-Basis
     *  (Profil-Sessions liegen NICHT in der globalen Session-Liste), sonst null = global. */
    fun effectiveBase(session: ChatSession): String? {
        session.serverBase?.takeIf { it.isNotBlank() }?.let { return it }
        val id = session.botId ?: return null
        return config.getBots().firstOrNull { it.id == id }?.let { botServerBase(it) }
    }

    /** Sende-Credentials/Modell des aktiven Bots; Standard-Agent → globale Konfig. */
    fun activeSendProfile(bot: BotProfile?): ActiveSendProfile {
        return if (bot == null) {
            ActiveSendProfile(
                serverUrl = config.serverUrl,
                apiKey = config.apiKey,
                primary = config.primaryModelPair(),
                fallback = config.fallbackModelPair()
            )
        } else {
            ActiveSendProfile(
                // Bots sprechen standardmäßig über ihren Profil-Prefix. Hat ein Bot
                // eine eigene Basis-URL/Port (Docker-Standalone-Gateway, Modus B),
                // wird diese direkt verwendet. Der HermesApiSender hängt
                // "/v1/chat/completions" an die Basis an.
                serverUrl = botServerBase(bot),
                apiKey = bot.apiKey,
                primary = bot.primaryModelPair(),
                fallback = bot.fallbackModelPair()
            )
        }
    }

    /** Basis-URL eines Bots: eigene Docker-Basis (Modus B) falls gesetzt, sonst
     *  geteilte Host-URL + Profil-Prefix (…/p/<name>). Der Sender ergänzt danach
     *  "/v1/chat/completions". */
    fun botServerBase(bot: BotProfile): String {
        val custom = bot.serverBase.trim()
        if (custom.isNotEmpty()) {
            // Direkte Adresse (URL mit Schema „://" oder ip/host:port) → unverändert
            // nutzen (LAN/Docker-Standalone, Modus B). Ein bloßer Profilname ohne
            // Schema/Port → extern über die Externe Gateway-Domain (…/p/<profil>).
            if (isDirectGateway(custom)) return custom.trimEnd('/')
            val domain = config.resolvedExternalDomain()
            return domain.trimEnd('/') + "/p/" + urlSegment(custom)
        }
        // Standard: geteilter Host + Profil-Prefix (…/p/<name>).
        return config.serverUrl.trimEnd('/') + "/p/" + urlSegment(bot.name)
    }

    /** Normalisiert einen Profilnamen für den URL-Pfad: trim + lowercase + URL-Encoding.
     *  Die Schreibweise im Settings-Feld ist damit egal („Coder" oder „coder"), der
     *  Anzeigename im Dropdown bleibt unverändert. */
    private fun urlSegment(name: String): String =
        URLEncoder.encode(name.trim().lowercase(Locale.ROOT), "UTF-8").replace("+", "%20")

    /** true, wenn der Eintrag eine direkte Adresse ist (Schema „://" oder Port
     *  „:N"); false, wenn es nur ein Profilname ist. */
    fun isDirectGateway(input: String): Boolean {
        val s = input.trim()
        return s.contains("://") || s.contains(':')
    }
}
