package de.hermi.voice.session

import android.content.Context
import de.hermi.voice.crypto.CryptoManager
import org.json.JSONArray
import org.json.JSONObject

/** Lokal beabsichtigtes, aber noch NICHT vom Gateway bestätigtes Löschen
 *  (Tombstone). Solange der Eintrag existiert, darf der Reverse-Sync die Session
 *  nicht wieder importieren. */
data class PendingDelete(
    val gatewaySessionId: String,
    /** Server-Basis der Session (null = globaler Gateway); der API-Key wird erst
     *  beim Senden aufgelöst und nie hier abgelegt. */
    val serverBase: String?,
    val createdAt: Long,
)

/** Lokal vergebener, noch nicht vom Gateway bestätigter Titel. Solange der Eintrag
 *  existiert, gewinnt der lokale Titel gegen den Server-Titel. */
data class PendingRename(
    val gatewaySessionId: String,
    val serverBase: String?,
    val title: String,
    val createdAt: Long,
)

/**
 * Persistente Warteschlange für lokale Absichten (Löschen/Umbenennen), die der Gateway
 * noch nicht bestätigt hat — "lokale Absicht gewinnt, bis der Server bestätigt".
 * Überlebt App-Neustarts; Titel sind Nutzertext, daher wird der gesamte Inhalt wie
 * sessions.json verschlüsselt abgelegt. Thread-sicher (alle Zugriffe synchronisiert).
 */
object PendingSyncStore {

    private const val PREFS_NAME = "hermi_pending_sync"
    private const val KEY_DATA = "ops"
    /** Einträge, die so lange nicht bestätigt werden konnten, werden verworfen
     *  (z. B. Server dauerhaft weg), damit die Warteschlange nicht ewig wächst. */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    /** Wie lange eine bestätigt gelöschte Gateway-ID im Speicher gegen
     *  Import-Rennen (Liste vor dem DELETE geholt) abgeschirmt bleibt. */
    private const val RECENT_DELETE_MS = 10L * 60 * 1000

    private val deletes = mutableListOf<PendingDelete>()
    private val renames = mutableListOf<PendingRename>()
    private val recentlyDeleted = mutableMapOf<String, Long>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DATA, null) ?: return
        try {
            val root = JSONObject(CryptoManager.decrypt(raw))
            val now = System.currentTimeMillis()
            root.optJSONArray("deletes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val d = PendingDelete(
                        o.getString("gid"),
                        if (o.has("base")) o.optString("base") else null,
                        o.optLong("at", now)
                    )
                    if (now - d.createdAt < MAX_AGE_MS) deletes.add(d)
                }
            }
            root.optJSONArray("renames")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val r = PendingRename(
                        o.getString("gid"),
                        if (o.has("base")) o.optString("base") else null,
                        o.optString("title", ""),
                        o.optLong("at", now)
                    )
                    if (now - r.createdAt < MAX_AGE_MS) renames.add(r)
                }
            }
        } catch (_: Exception) {
            // Nicht lesbar → als leer behandeln (best-effort; der Sync bleibt lauffähig).
            deletes.clear()
            renames.clear()
        }
    }

    @Synchronized
    private fun persist(context: Context) {
        val root = JSONObject()
        val dArr = JSONArray()
        for (d in deletes) {
            dArr.put(JSONObject().put("gid", d.gatewaySessionId).put("at", d.createdAt)
                .apply { if (d.serverBase != null) put("base", d.serverBase) })
        }
        val rArr = JSONArray()
        for (r in renames) {
            rArr.put(JSONObject().put("gid", r.gatewaySessionId).put("title", r.title).put("at", r.createdAt)
                .apply { if (r.serverBase != null) put("base", r.serverBase) })
        }
        root.put("deletes", dArr).put("renames", rArr)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DATA, CryptoManager.encrypt(root.toString())).apply()
    }

    @Synchronized
    fun addDelete(context: Context, gatewaySessionId: String, serverBase: String?) {
        ensureLoaded(context)
        if (deletes.none { it.gatewaySessionId == gatewaySessionId }) {
            deletes.add(PendingDelete(gatewaySessionId, serverBase, System.currentTimeMillis()))
        }
        // Eine gelöschte Session braucht keine Umbenennung mehr.
        renames.removeAll { it.gatewaySessionId == gatewaySessionId }
        persist(context)
    }

    /** Server hat das Löschen bestätigt (2xx/404) → Tombstone entfernen, die ID bleibt
     *  noch kurz im Speicher gesperrt (siehe [isDeleted]). */
    @Synchronized
    fun confirmDelete(context: Context, gatewaySessionId: String) {
        ensureLoaded(context)
        if (deletes.removeAll { it.gatewaySessionId == gatewaySessionId }) persist(context)
        recentlyDeleted[gatewaySessionId] = System.currentTimeMillis()
    }

    /** Neuer lokaler Titel (ersetzt einen noch offenen für dieselbe Session). */
    @Synchronized
    fun setRename(context: Context, gatewaySessionId: String, serverBase: String?, title: String) {
        ensureLoaded(context)
        renames.removeAll { it.gatewaySessionId == gatewaySessionId }
        renames.add(PendingRename(gatewaySessionId, serverBase, title, System.currentTimeMillis()))
        persist(context)
    }

    /** Server hat genau diesen Titel bestätigt (oder dauerhaft abgelehnt) → erledigt.
     *  Ein zwischenzeitlich neu vergebener Titel bleibt unberührt. */
    @Synchronized
    fun confirmRename(context: Context, gatewaySessionId: String, title: String) {
        ensureLoaded(context)
        if (renames.removeAll { it.gatewaySessionId == gatewaySessionId && it.title == title }) persist(context)
    }

    @Synchronized
    fun pendingDeletes(context: Context): List<PendingDelete> {
        ensureLoaded(context)
        return deletes.toList()
    }

    @Synchronized
    fun pendingRenames(context: Context): List<PendingRename> {
        ensureLoaded(context)
        return renames.toList()
    }

    /** true, wenn die Gateway-ID zum Löschen vorgemerkt ist oder erst vor Kurzem
     *  bestätigt gelöscht wurde — der Import darf sie dann nicht (wieder) anlegen. */
    @Synchronized
    fun isDeleted(context: Context, gatewaySessionId: String): Boolean {
        ensureLoaded(context)
        if (deletes.any { it.gatewaySessionId == gatewaySessionId }) return true
        val at = recentlyDeleted[gatewaySessionId] ?: return false
        if (System.currentTimeMillis() - at > RECENT_DELETE_MS) {
            recentlyDeleted.remove(gatewaySessionId)
            return false
        }
        return true
    }

    @Synchronized
    fun hasPendingRename(context: Context, gatewaySessionId: String): Boolean {
        ensureLoaded(context)
        return renames.any { it.gatewaySessionId == gatewaySessionId }
    }
}
