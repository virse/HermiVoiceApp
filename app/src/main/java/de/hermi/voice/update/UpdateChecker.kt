package de.hermi.voice.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lädt update.json vom öffentlichen GitHub-Spiegel. Gitea bleibt das primäre
 * Repo; die App kann aber nur öffentlich erreichbare URLs abrufen.
 *
 * Blockierend — immer auf einem Hintergrund-Thread aufrufen.
 */
object UpdateChecker {

    const val UPDATE_URL =
        "https://raw.githubusercontent.com/virse/HermiVoiceApp/main/update.json"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    @Throws(IOException::class)
    fun fetch(): UpdateInfo {
        val connection = (URL(UPDATE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return UpdateInfo.fromJson(body)
        } finally {
            connection.disconnect()
        }
    }
}
