package de.hermi.voice.update

import org.json.JSONObject

/**
 * Inhalt der öffentlich gehosteten update.json (GitHub Raw, öffentlicher Spiegel
 * des Gitea-Repos). Alle Felder werden mit org.json geparst — das Projekt nutzt
 * bewusst kein Retrofit/Moshi.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val forceUpdate: Boolean,
    val minSupportedVersionCode: Int,
    val sha256: String?
) {

    fun toJson(): String = JSONObject().apply {
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("apkUrl", apkUrl)
        put("changelog", changelog)
        put("forceUpdate", forceUpdate)
        put("minSupportedVersionCode", minSupportedVersionCode)
        put("sha256", sha256 ?: "")
    }.toString()

    companion object {
        /** Wirft bei fehlenden Pflichtfeldern (versionCode/versionName/apkUrl). */
        fun fromJson(json: String): UpdateInfo {
            val o = JSONObject(json)
            return UpdateInfo(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                apkUrl = o.getString("apkUrl"),
                changelog = o.optString("changelog", ""),
                forceUpdate = o.optBoolean("forceUpdate", false),
                minSupportedVersionCode = o.optInt("minSupportedVersionCode", 0),
                sha256 = o.optString("sha256", "").takeIf { it.isNotBlank() }
            )
        }
    }
}
