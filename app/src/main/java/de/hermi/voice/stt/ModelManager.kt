package de.hermi.voice.stt

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stellt das deutsche Sprachmodell im App-Verzeichnis bereit.
 * - Liegt das Modell bereits vollständig lokal vor? -> nichts tun.
 * - Sonst: einmaliger Download vom offiziellen sherpa-onnx Release (GitHub).
 *
 * Das Modell ist keine geheime Information, nur ~57 MB groß (einmalig).
 *
 * Robustheit:
 * - Nur EIN Download/Entpacken läuft gleichzeitig (Reentranz-Sperre) —
 *   ein doppelter Aufruf (z. B. durch erneutes `onResume`) darf den laufenden
 *   Vorgang nicht stören.
 * - Download in eine EINDEUTIGE Temp-Datei pro Versuch: zwei Aufrufe können
 *   einander nie übertragen, abschneiden oder die Datei löschen.
 * - Entpacken in ein EINDEUTIGES Temp-Verzeichnis, erst nach Erfolg (alle
 *   Pflichtdateien vorhanden) wird es atomar nach `models/` verschoben.
 *   Ein halb extrahiertes Modell (z. B. Abbruch/App-Kill durch Hitze) kann so
 *   nie als "installiert" gelten und nie eine Endlosschleife auslösen.
 */
object ModelManager {

    private const val TAG = "ModelManager"
    private const val MODEL_DIR_NAME = "models"
    private const val LANGUAGE_DE = "de"
    private const val LANGUAGE_EN = "en"

    /** Alle unterstützten STT-Sprachen (Unterordner unter [MODEL_DIR_NAME]).
     *  Reihenfolge = Anzeige-/Installationsreihenfolge. */
    fun supportedLanguages(): List<String> = listOf(LANGUAGE_DE, LANGUAGE_EN)

    /** Default-Modell je Sprache: Fallback für Installationen ohne
     *  Versions-Metadaten. Gepaarte, zeitgleiche Releases. */
    private val DEFAULT_MODEL_BY_LANG = mapOf(
        LANGUAGE_DE to "sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06",
        LANGUAGE_EN to "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06"
    )

    /** Nur die Präfixe je Sprache sind mit der OnlineRecognizer-Integration
     *  kompatibel. NeMo-Canary / fast-conformer sind andere Architekturen mit
     *  anderer Dateistruktur — hart darauf filtern. */
    private val COMPATIBLE_PREFIX_BY_LANG = mapOf(
        LANGUAGE_DE to "sherpa-onnx-streaming-zipformer-de-",
        LANGUAGE_EN to "sherpa-onnx-streaming-zipformer-en-"
    )

    private const val VERSION_FILE = "model-version.txt"

    /** Fallback-Sprache, falls ein unbekannter Wert übergeben wird. */
    private const val FALLBACK_LANG = LANGUAGE_DE

    /** GitHub-API des asr-models-Releases (liest die Asset-Liste). */
    private const val GH_RELEASE_API =
        "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models"

    /** Baut die Download-URL eines Modell-Assets (GitHub-Release). */
    private fun modelUrl(name: String): String =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$name.tar.bz2"

    /** Eine im asr-models-Release gefundene, kompatible Modellversion. */
    data class ModelInfo(
        val name: String,
        val downloadUrl: String,
        val versionDate: String
    )

    /** Extrahiert das Datum `YYYY-MM-DD` aus einem Modellnamen (z. B.
     *  kroko-2025-08-06). Toleriert optionalen `.tar.bz2`-Suffix, wie ihn die
     *  GitHub-Asset-Namen tragen. */
    fun versionDate(modelName: String): String? =
        Regex("""-(\d{4}-\d{2}-\d{2})(?:\.tar\.bz2)?$""").find(modelName)?.groupValues?.get(1)

    /** true, wenn [latest] neuer als die installierte Version ist. */
    fun isNewer(latest: ModelInfo, installedName: String): Boolean {
        val installedDate = versionDate(installedName) ?: return true
        return latest.versionDate > installedDate
    }

    /** Löst eine angeforderte Sprache auf (Fallback bei unbekannten Werten). */
    private fun resolveLang(lang: String): String =
        if (COMPATIBLE_PREFIX_BY_LANG.containsKey(lang)) lang else FALLBACK_LANG

    /**
     * Liefert die aktuell installierte Modellversion (Name) der Sprache [lang].
     * Alte Installationen ohne `model-version.txt` fallen auf das Default-Modell
     * dieser Sprache zurück.
     */
    fun installedVersion(context: Context, lang: String): String {
        val fallback = DEFAULT_MODEL_BY_LANG.getValue(resolveLang(lang))
        val meta = File(modelDir(context, lang), VERSION_FILE)
        return if (meta.exists()) meta.readText().trim().ifEmpty { fallback }
        else fallback
    }

    /**
     * Fragt die neueste kompatible Modellversion der Sprache [lang] über die
     * GitHub-API ab.
     * Blockiert auf dem aufrufenden Thread (Netzwerk, Timeout ~10 s).
     * Wirft bei Netz-/API-Fehlern — der Aufrufer entscheidet, ob er den
     * Check-Zeitstempel setzt (nur bei Erfolg!). null = Release nicht erreichbar
     * bzw. kein kompatibles Modell gefunden (unwahrscheinlich).
     */
    fun latestModel(lang: String): ModelInfo? {
        val prefix = COMPATIBLE_PREFIX_BY_LANG.getValue(resolveLang(lang))
        val conn = URL(GH_RELEASE_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "HermiVoice")
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code bei der GitHub-API")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val root = org.json.JSONObject(body)
            val assets = root.getJSONArray("assets")
            var best: ModelInfo? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.getString("name")
                if (!assetName.startsWith(prefix)) continue
                if (!assetName.endsWith(".tar.bz2")) continue
                val date = versionDate(assetName) ?: continue
                if (best == null || date > best.versionDate) {
                    best = ModelInfo(
                        name = assetName.removeSuffix(".tar.bz2"),
                        downloadUrl = asset.getString("browser_download_url"),
                        versionDate = date
                    )
                }
            }
            return best
        } finally {
            conn.disconnect()
        }
    }

    private val REQUIRED_FILES = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

    /** Phasen des einmaligen Modell-Setups. */
    enum class Phase { DOWNLOADING, EXTRACTING }

    /** Sperrt parallele Downloads — nur ein Modell-Setup darf laufen. */
    private val lock = Any()
    @Volatile private var active = false

    /** Liefert true, während gerade ein Modell-Setup (Download/Entpacken) läuft. */
    fun isDownloading(): Boolean = active

    /** Liefert den Installationsordner der Sprache [lang] (`models/<lang>`).
     *  Einmalige Legacy-Migration: ein vorkonfiguriertes Modell im bisherigen
     *  Root `models/` (Deutsch, einzige Sprache vor der i18n-Umstellung) wird
     *  nach `models/de/` verschoben, damit keine erneute Installation nötig ist. */
    fun modelDir(context: Context, lang: String): File {
        val root = File(context.filesDir, MODEL_DIR_NAME)
        val dir = File(root, resolveLang(lang))
        synchronized(lock) {
            if (!dir.exists() && lang == LANGUAGE_DE) {
                migrateLegacyLangDe(context, root)
            }
        }
        dir.mkdirs()
        return dir
    }

    /** Verschiebt ein Modell, das noch direkt unter `models/` liegt (frühere
     *  Einzelsprach-Struktur), nach `models/de/`. Idempotent: nur wenn der
     *  Zielordner fehlt und der Root vollständig aussieht (encoder.decoder, …). */
    private fun migrateLegacyLangDe(context: Context, root: File) {
        if (!root.isDirectory) return
        val target = File(root, LANGUAGE_DE)
        if (target.exists()) return
        // Root enthält die Modell-Dateien direkt (Regeln wie isInstalled).
        val lookedLegacy = REQUIRED_FILES.all { File(root, it).exists() }
        if (!lookedLegacy) return
        val moved = root.renameTo(target) ||
            // Fallback bei Cross-Filesystem-Restriktionen: Inhalt kopieren.
            (root.listFiles()?.all { it.renameTo(File(target, it.name)) } ?: false)
        if (moved) {
            android.util.Log.i(TAG, "Legacy-Modell von models/ nach models/de/ verschoben")
        } else {
            android.util.Log.w(TAG, "Legacy-Modell-Migration nach models/de/ fehlgeschlagen")
        }
    }

    fun isInstalled(context: Context, lang: String): Boolean {
        val dir = modelDir(context, lang)
        return REQUIRED_FILES.all { File(dir, it).exists() }
    }

    /**
     * Lädt das Default-Modell der Sprache [lang] herunter und entpackt es nach
     * [modelDir]. Kompatibilitäts-Wrapper für [downloadVersion] mit dem
     * Standardmodell dieser Sprache (Erstinstallation / Fallback).
     */
    fun download(context: Context, lang: String, onProgress: (Float) -> Unit, onPhase: (Phase) -> Unit): Boolean {
        val resolved = resolveLang(lang)
        val name = DEFAULT_MODEL_BY_LANG.getValue(resolved)
        return downloadVersion(context, resolved, name, modelUrl(name), onProgress, onPhase)
    }

    /**
     * Lädt ein benanntes Modell der Sprache [lang] herunter und entpackt es
     * nach [modelDir].
     * Fortschritt (0..1) und Phase werden über die Callbacks gemeldet.
     * Läuft auf dem aufrufenden Thread. Der Fortschritt läuft auch während des
     * Entpackens weiter, damit die UI nie unbestimmt wirkend hängt.
     *
     * Ist bereits ein Setup im Gange, kehrt der Aufruf sofort zurück (Callback-
     * Meldungen des laufenden Vorgangs bleiben gültig) und liefert `false` —
     * der Aufrufer darf dann NICHT auf "fertig" schalten; das erledigt der
     * laufende Vorgang. `true` bedeutet: Modell ist jetzt sauber installiert.
     */
    fun downloadVersion(
        context: Context,
        lang: String,
        modelName: String,
        modelUrl: String,
        onProgress: (Float) -> Unit,
        onPhase: (Phase) -> Unit
    ): Boolean {
        val resolved = resolveLang(lang)
        synchronized(lock) {
            if (active) return false
            active = true
        }
        // Pro Versuch eindeutige Datei/Ordner: kein geteilter Zustand, den
        // ein zweiter Lauf oder ein früherer finally-Block zerstören könnte.
        val nonce = System.nanoTime()
        // Dateiname bewusst OHNE modelName: nur nonce + Literal. So hängt der
        // Pfad von keiner externen Eingabe ab (CodeQL path-injection-Sink ist
        // dann unabhängig vom Modellnamen); modelName bleibt nur für Meldungen.
        val tarFile = File(context.cacheDir, "model-$nonce.tar.bz2")
        val extractDir = File(context.cacheDir, "models-extract-$nonce")
        // SOFORT herunterladen melden — BEVOR der (bis zu 30 s dauernde)
        // Verbindungsaufbau in fetch() blockiert. Sonst bliebe die UI die ganze
        // Zeit im model_missing-Text stehen und wirkte eingefroren.
        onPhase(Phase.DOWNLOADING)
        onProgress(0.0f)
        try {
            val destDir = modelDir(context, resolved)

            fetch(tarFile, modelName, modelUrl, onProgress, onPhase)
            onPhase(Phase.EXTRACTING)
            extractBz2Tar(tarFile, extractDir) { frac ->
                onProgress(0.90f + frac * 0.10f)
            }

            val missing = REQUIRED_FILES.filter { !File(extractDir, it).exists() }
            if (missing.isNotEmpty()) {
                throw java.io.IOException(
                    "Entpacken unvollständig, fehlend: ${missing.joinToString()} " +
                        "(Quelle evtl. beschädigt — bitte erneut versuchen)"
                )
            }

            // Erst JETZT das alte Ziel ersetzen — atomar (auf derselben Partition
            // ist renameTo ein Rename, kein Kopieren). Ein halbes Modell erreicht
            // isInstalled() nie.
            val tmpDest = File(context.cacheDir, "models-final-$nonce")
            tmpDest.deleteRecursively()
            if (!extractDir.renameTo(tmpDest)) {
                throw java.io.IOException("Modellordner konnte nicht bereitgestellt werden")
            }
            destDir.deleteRecursively()
            destDir.mkdirs()
            if (!tmpDest.renameTo(destDir)) {
                // Fallback: Inhalt kopieren, damit das Modell trotzdem ankommt.
                val ok = tmpDest.listFiles()?.all { it.renameTo(File(destDir, it.name)) } ?: false
                if (!ok) throw java.io.IOException("Modellordner konnte nicht bereitgestellt werden")
            }
            // Versions-Metadaten schreiben (Grundlage des Update-Checks).
            // Fehler hier sind unkritisch — Fallback bleibt DEFAULT_MODEL_NAME.
            try {
                File(destDir, VERSION_FILE).writeText(modelName)
            } catch (_: Exception) {
            }
            return true
        } finally {
            synchronized(lock) { active = false }
            // Temp-Artefakte (eindeutig pro Versuch) erst JETZT entfernen,
            // nachdem das Entpacken abgeschlossen ist — NICHT schon in fetch().
            val cleanup = arrayOf(
                tarFile,
                extractDir
            )
            for (f in cleanup) {
                try { f.deleteRecursively() } catch (_: Exception) { }
            }
        }
    }

    /** Öffnet [startUrl] und folgt Weiterleitungen manuell (max. 5), aber nur auf
     *  https-Ziele. Liefert die verbundene Verbindung (Statuscode bereits gelesen,
     *  Aufrufer schließt sie). */
    private fun openFollowingHttps(startUrl: String): HttpURLConnection {
        var current = URL(startUrl)
        for (hop in 0..5) {
            if (current.protocol != "https") {
                throw java.io.IOException("Unverschlüsselte Quelle abgelehnt: ${current.protocol}://${current.host}")
            }
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location.isNullOrBlank()) {
                    throw java.io.IOException("HTTP $code ohne Weiterleitungsziel")
                }
                current = URL(current, location) // löst auch relative Ziele auf
                continue
            }
            return c
        }
        throw java.io.IOException("Zu viele Weiterleitungen beim Modell-Download")
    }

    private fun fetch(
        tarFile: File,
        modelName: String,
        modelUrl: String,
        onProgress: (Float) -> Unit,
        onPhase: (Phase) -> Unit
    ) {
        // Weiterleitungen (GitHub → Release-CDN) werden manuell und NUR über https
        // verfolgt; ein Downgrade auf http wird abgelehnt. Timeouts/GET sind im
        // Helfer gesetzt.
        val conn = openFollowingHttps(modelUrl)
        try {
            // Nicht-2xx-Stati (z. B. 404 wegen falschem Modellnamen) früh und
            // verständlich melden — sonst wird die Fehlerseite als "Modell"
            // abgelegt und die Extraktion scheitert unspezifisch.
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException(
                    "HTTP $code beim Abruf von $modelName.tar.bz2 (URL prüfen)"
                )
            }

            val total = conn.contentLengthLong
            var downloaded = 0L
            val input = BufferedInputStream(conn.inputStream)
            val output = FileOutputStream(tarFile)
            val buf = ByteArray(64 * 1024)
            var lastReport = 0f
            onPhase(Phase.DOWNLOADING)
            input.use { i ->
                output.use { o ->
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val p = downloaded.toFloat() / total
                            if (p - lastReport >= 0.02f) {
                                lastReport = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            // Download fertig; 90 % liegt vor dem (langen) Entpacken.
            onProgress(0.90f)

            // Abbruch mit verständlicher Meldung, falls der Download trotz
            // 2xx keine brauchbare Datei hinterlassen hat (leere / fehlende
            // Antwort). Das verhindert das unspezifische "open failed ENOENT".
            if (!tarFile.exists() || tarFile.length() == 0L) {
                throw java.io.IOException(
                    "Download lieferte keine Datei ($modelName.tar.bz2 fehlt/leer)"
                )
            }
        } finally {
            conn.disconnect()
            // NICHT hier die Tar-Datei löschen: extractBz2Tar liest sie direkt
            // nach fetch() (siehe download()). Die Temp-Datei wird erst nach
            // erfolgreichem Entpacken in download() aufgeräumt.
        }
    }

    /**
     * Entpackt bzip2+tar nach [destDir] (eine Stufe, keine Zwischendatei).
     * [onFraction] meldet den Fortschritt 0..1, abgeleitet aus dem tatsächlich
     * konsumierten bz2-Quellstream relativ zur Dateigröße.
     */
    private fun extractBz2Tar(archive: File, destDir: File, onFraction: (Float) -> Unit) {
        val totalBytes = archive.length()
        var lastF = 0f
        val report = { sourceConsumed: Long ->
            if (totalBytes > 0) {
                val f = (sourceConsumed.toFloat() / totalBytes).coerceIn(0f, 1f)
                if (f - lastF >= 0.02f) {
                    lastF = f
                    onFraction(f)
                }
            }
        }

        // Ein Zählstrom misst, wie viele komprimierte Bytes die bz2-Dekompression
        // wirklich verbraucht hat -> ehrlicher Fortschritt während der CPU-Phase.
        val counting = CountingInputStream(BufferedInputStream(archive.inputStream()))
        // Tar wird direkt aus dem bz2-Stream gelesen; keine ~70 MB Zwischendatei.
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(counting)
        ).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                // Links im Archiv werden nie angelegt (Pfad-Umleitung aus dem Zielordner).
                val isLink = (entry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry)
                    ?.let { it.isSymbolicLink || it.isLink } ?: false
                if (!entry.isDirectory && !isLink) {
                    // Pfad relativ zum Modellordner: sherpa-onnx-.../encoder.onnx
                    val name = entry.name.substringAfter('/')
                    val target = File(destDir, name)
                    // Zip-Slip-Schutz: Der aufgelöste Zielpfad muss INNERHALB von destDir
                    // liegen. Ein Eintrag wie "x/../../evil" würde sonst außerhalb schreiben.
                    val destRoot = destDir.canonicalFile
                    val canonicalTarget = target.canonicalFile
                    if (!canonicalTarget.path.startsWith(destRoot.path + File.separator)) {
                        throw java.io.IOException("Ungültiger Pfad im Archiv: ${entry.name}")
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        // Bytes in Blöcken kopieren und zwischendurch melden,
                        // damit der Fortschritt WÄHREND des Lesens steigt —
                        // nicht erst nach jedem (evtl. 70 MB großen) Tar-Entry.
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = tar.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            report(counting.count)
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    /** Zählt die aus einem [nested] gelesenen Bytes. */
    private class CountingInputStream(
        private val nested: java.io.InputStream
    ) : java.io.InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val b = nested.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = nested.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun close() = nested.close()
    }
}