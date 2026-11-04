package de.hermi.voice.config

import de.hermi.voice.crypto.CryptoManager
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.SecretKey

/**
 * Export/Import der kompletten Konfiguration als verschlüsseltes JSON-Backup
 * (Gerätewechsel, allowBackup=false → alle Bots/Keys/URLs gehen sonst verloren).
 *
 * Sicherheitsmodell (identisch zum restlichen App-Ansatz):
 *  - Unkritische Werte (Server-URL, Prefix, Toggles, Recording-Params, Modellwahl,
 *    Bot-Name, active_bot) liegen im Backup als Klartext — wie auch privat im Speicher.
 *  - Alle API-Keys (global + je Bot) werden NICHT im Klartext übernommen: sie werden
 *    lokal (per Keystore) entschlüsselt und dann mit einem aus einer Passphrase
 *    abgeleiteten AES-256-GCM-Schlüssel (PBKDF2-HMAC-SHA256, frisches Salz je Export)
 *    neu verschlüsselt. Der Keystore-Key ist nicht exportierbar — ohne Passphrase-
 *    Re-Encryption wäre ein Backup auf einem anderen Gerät unlesbar.
 *  - Beim Import wird mit derselben Passphrase entschlüsselt und über die AppConfig-
 *    Setter geschrieben, die die Keys wieder mit dem Keystore-Key des neuen Geräts
 *    verschlüsseln. Der Klartext liegt nie dauerhaft im Backup.
 *
 * Format (JSON):
 *   {
 *     "format": "hermi-config-backup",
 *     "version": 1,
 *     "salt": Base64,
 *     "api_key": Base64(IV||Ciphertext),          // ggf. leer
 *     "server_url", "message_prefix", ...         // Klartext
 *     "bots": [ { "id", "name", "api_key"(verschl.), "primaryProvider", ... } ]
 *     "active_bot"
 *   }
 */
object ConfigBackup {

    private const val FORMAT = "hermi-config-backup"
    private const val VERSION = 1

    fun export(config: AppConfig, passphrase: CharArray): String {
        val salt = CryptoManager.newExportSalt()
        val key = CryptoManager.derivePassphraseKey(passphrase, salt)

        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("salt", CryptoManager.toBase64(salt))

        // Unkritische Klartext-Werte.
        root.put("server_url", config.serverUrl)
        root.put("external_domain", config.externalDomain)
        root.put("message_prefix", config.messagePrefix)
        root.put("show_message_separator", config.showMessageSeparator)
        root.put("auto_new_session", config.autoNewSession)
        root.put("auto_new_session_minutes", config.autoNewSessionMinutes)
        root.put("haptic_feedback", config.hapticFeedback)
        root.put("tts_read_answer", config.ttsReadAnswer)
        root.put("start_input_mode", config.startInputMode)
        root.put("keyboard_after_answer", config.keyboardInForeground)
        root.put("first_token_timeout_sec", config.firstTokenTimeoutSec)
        root.put("stream_timeout_min", config.streamTimeoutMin)
        root.put("accent_color", config.accentColorName)
        root.put("mic_gain", config.micGain.toDouble())
        root.put("min_tail_blocks", config.minTailBlocks)
        root.put("silence_blocks", config.silenceBlocks)
        root.put("max_tail_blocks", config.maxTailBlocks)
        root.put("silence_factor", config.silenceFactor.toDouble())
        root.put("silence_floor_abs", config.silenceFloorAbs.toDouble())
        root.put("primary_provider", config.primaryProvider)
        root.put("primary_model", config.primaryModel)
        root.put("fallback_provider", config.fallbackProvider)
        root.put("fallback_model", config.fallbackModel)
        root.put("active_bot", config.getActiveBotId())

        // App-Update-Einstellungen (Toggle, weggetippte/erzwungene Version).
        // Der Zeitstempel der letzten Prüfung wird mit exportiert, beim Import
        // aber bewusst NICHT übernommen (neues Gerät prüft sofort).
        root.put("update_auto_check", config.updateAutoCheck)
        root.put("update_skipped_version", config.updateSkippedVersion)
        root.put("update_pending_force_version", config.updatePendingForceVersion)
        root.put("update_last_success_millis", config.updateLastSuccessMillis)

        // Secrets: globaler API-Key + jeder Bot-Key transportverschlüsselt.
        root.put("api_key", if (config.apiKey.isEmpty()) ""
            else CryptoManager.encryptWithKey(key, config.apiKey))

        val botsArr = JSONArray()
        for (b in config.getBots()) {
            val o = JSONObject()
            o.put("id", b.id)
            o.put("name", b.name)
            o.put("api_key", if (b.apiKey.isEmpty()) ""
                else CryptoManager.encryptWithKey(key, b.apiKey))
            o.put("server_base", b.serverBase)
            o.put("primary_provider", b.primaryProvider)
            o.put("primary_model", b.primaryModel)
            o.put("fallback_provider", b.fallbackProvider)
            o.put("fallback_model", b.fallbackModel)
            botsArr.put(o)
        }
        root.put("bots", botsArr)

        // Cloud-KI-Anbieter (verschlüsselte Keys, wie die Bots; getrennt vom reinen Config-Export).
        val cloudArr = JSONArray()
        for (p in config.getCloudProviders()) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("provider", p.provider)
            o.put("api_key", if (p.apiKey.isEmpty()) ""
                else CryptoManager.encryptWithKey(key, p.apiKey))
            o.put("primary_model", p.primaryModel)
            o.put("fallback_model", p.fallbackModel)
            o.put("tools", JSONArray(p.tools))
            cloudArr.put(o)
        }
        root.put("cloud_providers", cloudArr)

        // Favoriten der Modell-Auswahl je Geltungsbereich (standard + je Bot).
        val favs = JSONObject()
        favs.put(
            AppConfig.MODEL_SCOPE_STANDARD,
            JSONArray(config.getModelFavorites(AppConfig.MODEL_SCOPE_STANDARD))
        )
        for (b in config.getBots()) {
            favs.put(b.id, JSONArray(config.getModelFavorites(b.id)))
        }
        root.put("model_favorites", favs)

        return root.toString()
    }

    /**
     * Importiert ein Backup. Setzt alle Werte über die AppConfig-Setter (dadurch
     * werden API-Keys automatisch mit dem lokalen Keystore-Key neu verschlüsselt).
     *
     * @throws IllegalArgumentException bei Formatfehlern (kein Hermi-Backup, falsche
     *         Version) — eine falsche Passphrase führt zu einer javax.crypto
     *         AEADBadTagException beim Entschlüsseln.
     */
    fun import(json: String, passphrase: CharArray, config: AppConfig) {
        val root = JSONObject(json)
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("Keine hermi-Konfig-Backup-Datei")
        }
        val version = root.optInt("version", -1)
        if (version != VERSION) {
            throw IllegalArgumentException("Nicht unterstützte Backup-Version: $version")
        }
        val salt = try {
            CryptoManager.fromBase64(root.getString("salt"))
        } catch (e: Exception) {
            throw IllegalArgumentException("Backup-Datei fehlerhaft (Salt)")
        }
        val key = CryptoManager.derivePassphraseKey(passphrase, salt)

        // PHASE 1 (nur lesen/entschlüsseln, NICHTS schreiben): Alle verschlüsselten Werte
        // werden zuerst entschlüsselt und die Bots geparst. Schlägt hier etwas fehl
        // (falsche Passphrase, defekter Eintrag), bleibt die bestehende Konfiguration
        // vollständig unverändert — kein halb importierter Zustand.
        val encKey = root.optString("api_key")
        val importedApiKey = if (encKey.isNotEmpty()) CryptoManager.decryptWithKey(key, encKey) else ""

        val botsArr = root.optJSONArray("bots")
        val importedBots: List<BotProfile>? = if (botsArr != null) {
            val list = mutableListOf<BotProfile>()
            for (i in 0 until botsArr.length()) {
                val o = botsArr.getJSONObject(i)
                val apiKey = o.optString("api_key").let { enc ->
                    if (enc.isEmpty()) "" else CryptoManager.decryptWithKey(key, enc)
                }
                list.add(BotProfile(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    apiKey = apiKey,
                    serverBase = o.optString("server_base"),
                    primaryProvider = o.optString("primary_provider"),
                    primaryModel = o.optString("primary_model"),
                    fallbackProvider = o.optString("fallback_provider"),
                    fallbackModel = o.optString("fallback_model")
                ))
            }
            list
        } else null

        // Cloud-KI-Anbieter wie Bots in Phase 1 entschlüsseln/parsen.
        val cloudArr = root.optJSONArray("cloud_providers")
        val importedCloud: List<CloudProvider>? = if (cloudArr != null) {
            val list = mutableListOf<CloudProvider>()
            for (i in 0 until cloudArr.length()) {
                val o = cloudArr.getJSONObject(i)
                val apiKey = o.optString("api_key").let { enc ->
                    if (enc.isEmpty()) "" else CryptoManager.decryptWithKey(key, enc)
                }
                list.add(CloudProvider(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    provider = o.optString("provider"),
                    apiKey = apiKey,
                    primaryModel = o.optString("primary_model"),
                    fallbackModel = o.optString("fallback_model"),
                    tools = readToolSlugs(o.optJSONArray("tools"))
                ))
            }
            list
        } else null

        // PHASE 2 (schreiben). Felder, die im Backup FEHLEN (z. B. Backup einer älteren
        // App-Version), behalten ihren aktuellen Wert. Früher überschrieb ein fehlender
        // Boolean/String die Einstellung mit false bzw. "" (z. B. Haptik/Trenner standen
        // dann nach dem Import auf false statt dem Standard true).
        fun str(k: String, current: String) = if (root.has(k)) root.optString(k) else current
        fun bool(k: String, current: Boolean) = if (root.has(k)) root.optBoolean(k, current) else current

        // Unkritische Klartext-Werte übernehmen.
        config.serverUrl = str("server_url", config.serverUrl)
        config.externalDomain = str("external_domain", config.externalDomain)
        config.messagePrefix = str("message_prefix", config.messagePrefix)
        config.showMessageSeparator = bool("show_message_separator", config.showMessageSeparator)
        config.autoNewSession = bool("auto_new_session", config.autoNewSession)
        config.autoNewSessionMinutes = root.optInt("auto_new_session_minutes", config.autoNewSessionMinutes)
        config.hapticFeedback = bool("haptic_feedback", config.hapticFeedback)
        config.ttsReadAnswer = bool("tts_read_answer", config.ttsReadAnswer)
        config.startInputMode = root.optBoolean("start_input_mode", config.startInputMode)
        config.keyboardInForeground = bool("keyboard_after_answer", config.keyboardInForeground)
        config.firstTokenTimeoutSec = root.optInt("first_token_timeout_sec", config.firstTokenTimeoutSec)
        config.streamTimeoutMin = root.optInt("stream_timeout_min", config.streamTimeoutMin)
        root.optString("accent_color").takeIf { it.isNotBlank() }?.let {
            config.accentColorName = it
        }
        config.micGain = root.optDouble("mic_gain", config.micGain.toDouble()).toFloat()
        config.minTailBlocks = root.optInt("min_tail_blocks", config.minTailBlocks)
        config.silenceBlocks = root.optInt("silence_blocks", config.silenceBlocks)
        config.maxTailBlocks = root.optInt("max_tail_blocks", config.maxTailBlocks)
        config.silenceFactor = root.optDouble("silence_factor", config.silenceFactor.toDouble()).toFloat()
        config.silenceFloorAbs = root.optDouble("silence_floor_abs", config.silenceFloorAbs.toDouble()).toFloat()
        config.primaryProvider = str("primary_provider", config.primaryProvider)
        config.primaryModel = str("primary_model", config.primaryModel)
        config.fallbackProvider = str("fallback_provider", config.fallbackProvider)
        config.fallbackModel = str("fallback_model", config.fallbackModel)

        // Globaler API-Key: bereits in Phase 1 entschlüsselt, hier per Setter neu verschlüsseln.
        config.apiKey = importedApiKey

        // Bots übernehmen; Keys dabei neu verschlüsseln.
        if (importedBots != null) config.saveBots(importedBots)

        // Cloud-KI-Anbieter übernehmen; Keys dabei neu verschlüsseln.
        if (importedCloud != null) config.saveCloudProviders(importedCloud)

        config.setActiveBotId(root.optString("active_bot"))

        // Update-Einstellungen: Toggle und gemerkte Versionen übernehmen.
        // Der Zeitstempel wird absichtlich auf 0 gesetzt, damit ein frisch
        // wiederhergestelltes Gerät beim ersten Start sofort prüft, statt bis
        // zu 24 h zu warten.
        config.updateAutoCheck = bool("update_auto_check", config.updateAutoCheck)
        config.updateSkippedVersion = root.optInt("update_skipped_version", config.updateSkippedVersion)
        config.updatePendingForceVersion = root.optInt("update_pending_force_version", config.updatePendingForceVersion)
        config.updateLastSuccessMillis = 0L
        // Transienten Download-/Install-Zustand nicht aus einem Backup übernehmen.
        config.updateDownloadId = -1L
        config.updateInstallReady = false
        config.updateCachedInfoJson = ""

        // Favoriten der Modell-Auswahl je Geltungsbereich übernehmen.
        val favs = root.optJSONObject("model_favorites")
        if (favs != null) {
            val scopes = favs.keys()
            while (scopes.hasNext()) {
                val scope = scopes.next() as String
                val arr = favs.optJSONArray(scope) ?: continue
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                config.setModelFavorites(scope, set)
            }
        }
    }

    /** Liest die aktivierten Tool-Slugs eines Cloud-Providers aus dem JSON-Array
     *  des Backups (fehlt/leer → leere Menge). */
    private fun readToolSlugs(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }
}