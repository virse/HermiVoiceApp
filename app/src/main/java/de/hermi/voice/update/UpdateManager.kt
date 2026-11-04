package de.hermi.voice.update

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import de.hermi.voice.R
import de.hermi.voice.config.AppConfig
import java.io.File
import java.lang.ref.WeakReference

/**
 * Zentrale Update-Orchestrierung. Wird von MainActivity (Start/Auto-Check) und
 * von der Update-Backup-Activity (manuelle Prüfung) aufgerufen. Die gesamte
 * Logik liegt hier — die Activities bleiben schlank.
 *
 * Ablauf:
 *  - Start: Pflicht-Update durchsetzen, laufenden Download prüfen, sonst
 *    automatischer Check (Toggle + max. einmal alle 24 h).
 *  - Manuell: immer Netzwerk, Rückgabe an den Aufrufer.
 *  - Download: DownloadManager → SHA-256 → Install-Dialog.
 */
object UpdateManager {

    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private var currentActivity: WeakReference<Activity>? = null
    private var receiverRegistered = false
    private var checking = false
    private var processingDownload = false
    private var dialogShowing = false
    private var installDialogShowing = false
    private var installPromptDismissed = false

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo, val forced: Boolean) : CheckResult
        data class UpToDate(val versionName: String) : CheckResult
        data class Failure(val message: String) : CheckResult
    }

    /** Einmal pro Activity-onResume aufrufen. */
    fun onAppResume(activity: Activity) {
        // Neue Activity-Instanz (z. B. recreate() nach Akzentwechsel): Dialog-Flags
        // zurücksetzen, sonst würde nie wieder ein Dialog erscheinen.
        val previous = currentActivity?.get()
        if (previous !== activity) {
            dialogShowing = false
            installDialogShowing = false
        }
        currentActivity = WeakReference(activity)
        ensureDownloadReceiver(activity.applicationContext)

        if (dialogShowing || installDialogShowing || checking || processingDownload) return

        val config = AppConfig(activity)
        val installed = UpdateVersion.installedVersionCode(activity)

        // Pflicht-Update erledigt? Merker löschen.
        if (config.updatePendingForceVersion > 0 && installed >= config.updatePendingForceVersion) {
            config.updatePendingForceVersion = 0
        }

        // 1. Geprüfte APK aus früherem Lauf → Install-Dialog.
        if (config.updateInstallReady) {
            val info = cachedInfo(config)
            val file = UpdateDownloader.file(activity)
            if (info != null && file.exists() && info.versionCode > installed) {
                showInstallPrompt(activity, file, info)
                return
            } else {
                config.updateInstallReady = false
                config.updateDownloadId = -1L
            }
        }

        // 2. Laufender Download → Status prüfen (verifiziert und zeigt Dialog bei Erfolg).
        if (config.updateDownloadId > 0) {
            handleDownloadComplete(activity.applicationContext, config.updateDownloadId)
            return
        }

        // 3. Pflicht-Update aussteht → Check unabhängig vom Toggle.
        if (config.updatePendingForceVersion > 0 && installed < config.updatePendingForceVersion) {
            checkAuto(activity, forceContext = true)
            return
        }

        // 4. Auto-Check nur bei aktivem Toggle und abgelaufenem 24-h-Fenster.
        if (!config.updateAutoCheck) return
        val now = System.currentTimeMillis()
        if (now - config.updateLastSuccessMillis < CHECK_INTERVAL_MS) return
        checkAuto(activity, forceContext = false)
    }

    /**
     * Manuelle Prüfung: immer Netzwerk, Ergebnis per Callback (auf dem UI-Thread).
     * @return false, wenn bereits eine Prüfung läuft (Callback wird dann nicht
     *         aufgerufen).
     */
    fun checkManually(activity: Activity, callback: (CheckResult) -> Unit): Boolean {
        if (checking) return false
        checking = true
        Thread {
            val result = runCatching { UpdateChecker.fetch() }
            Handler(Looper.getMainLooper()).post {
                checking = false
                result.fold(
                    onSuccess = { info ->
                        val config = AppConfig(activity)
                        config.updateLastSuccessMillis = System.currentTimeMillis()
                        config.updateCachedInfoJson = info.toJson()
                        val installed = UpdateVersion.installedVersionCode(activity)
                        val forced = info.forceUpdate ||
                            (info.minSupportedVersionCode > 0 && installed < info.minSupportedVersionCode)
                        if (info.versionCode > installed) {
                            // Server kann ein früheres Pflicht-Update auch wieder
                            // auf optional zurückstufen.
                            config.updatePendingForceVersion =
                                if (forced) info.versionCode else 0
                            callback(CheckResult.Available(info, forced))
                        } else {
                            config.updatePendingForceVersion = 0
                            callback(CheckResult.UpToDate(UpdateVersion.installedVersionName(activity)))
                        }
                    },
                    onFailure = { e ->
                        callback(CheckResult.Failure(e.message ?: e.javaClass.simpleName))
                    }
                )
            }
        }.start()
        return true
    }

    /** Startet den Download der APK. */
    fun startDownload(activity: Activity, info: UpdateInfo) {
        val config = AppConfig(activity)

        // Bereits geprüfte Datei vorhanden → direkt Install-Dialog anbieten.
        if (config.updateInstallReady) {
            val file = UpdateDownloader.file(activity)
            val cached = cachedInfo(config)
            if (file.exists() && cached != null) {
                showInstallPrompt(activity, file, cached)
                return
            }
        }

        // Keinen zweiten Download starten, solange einer läuft.
        if (config.updateDownloadId > 0) {
            Toast.makeText(activity, R.string.update_download_running, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val id = UpdateDownloader.enqueue(activity, info)
            config.updateDownloadId = id
            config.updateInstallReady = false
            installPromptDismissed = false
            Toast.makeText(activity, R.string.update_download_started, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Prüft einen laufenden/abgeschlossenen Download (z. B. nach Prozess-Neustart). */
    fun handleDownloadComplete(context: Context, downloadId: Long) {
        if (processingDownload) return
        processingDownload = true
        Thread {
            val status = runCatching { UpdateDownloader.query(context, downloadId) }
                .getOrElse { UpdateDownloader.DownloadStatus.Unknown }
            var uiAction: (() -> Unit)? = null

            when (status) {
                is UpdateDownloader.DownloadStatus.Success -> {
                    val config = AppConfig(context)
                    val info = cachedInfo(config)
                    val file = status.file ?: UpdateDownloader.file(context)
                    if (info != null && file.exists()) {
                        val ok = Sha256.verify(file, info.sha256)
                        uiAction = {
                            if (ok) {
                                config.updateInstallReady = true
                                val activity = currentActivity?.get()
                                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                                    showInstallPrompt(activity, file, info)
                                }
                            } else {
                                file.delete()
                                config.updateInstallReady = false
                                config.updateDownloadId = -1L
                                val activity = currentActivity?.get()
                                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                                    Toast.makeText(
                                        activity,
                                        R.string.update_hash_mismatch,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    } else {
                        // Datei oder gecachte Info fehlt (z. B. App-Daten teilweise
                        // gelöscht) — Download-Zustand zurücksetzen, damit der
                        // Auto-Check nicht dauerhaft blockiert wird.
                        config.updateDownloadId = -1L
                        config.updateInstallReady = false
                    }
                }

                UpdateDownloader.DownloadStatus.Failed -> {
                    val config = AppConfig(context)
                    config.updateInstallReady = false
                    config.updateDownloadId = -1L
                    uiAction = {
                        val activity = currentActivity?.get()
                        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                            Toast.makeText(
                                activity,
                                R.string.update_download_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                UpdateDownloader.DownloadStatus.Running -> Unit

                UpdateDownloader.DownloadStatus.Unknown -> {
                    // Unbekannte Download-ID (z. B. nach Datenlöschung) — zurücksetzen.
                    val config = AppConfig(context)
                    config.updateDownloadId = -1L
                    config.updateInstallReady = false
                }
            }

            Handler(Looper.getMainLooper()).post {
                processingDownload = false
                uiAction?.invoke()
            }
        }.start()
    }

    // ── intern ───────────────────────────────────────────────────────────────

    private fun checkAuto(activity: Activity, forceContext: Boolean) {
        checking = true
        Thread {
            val result = runCatching { UpdateChecker.fetch() }
            Handler(Looper.getMainLooper()).post {
                checking = false
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.fold(
                    onSuccess = { info ->
                        val config = AppConfig(activity)
                        config.updateLastSuccessMillis = System.currentTimeMillis()
                        config.updateCachedInfoJson = info.toJson()
                        val installed = UpdateVersion.installedVersionCode(activity)
                        val forced = info.forceUpdate ||
                            (info.minSupportedVersionCode > 0 && installed < info.minSupportedVersionCode)
                        if (info.versionCode > installed) {
                            config.updatePendingForceVersion =
                                if (forced) info.versionCode else 0
                            val skipped = config.updateSkippedVersion
                            if (forceContext || forced || info.versionCode > skipped) {
                                showUpdateDialog(activity, info, forced)
                            }
                        } else {
                            config.updatePendingForceVersion = 0
                        }
                    },
                    onFailure = {
                        // Auto-Check scheitert still; der nächste Start versucht es erneut,
                        // weil updateLastSuccessMillis unverändert bleibt.
                    }
                )
            }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo, forced: Boolean) {
        if (dialogShowing || !activity.canShowDialog()) return
        dialogShowing = true
        UpdateUi.showUpdateDialog(
            activity = activity,
            info = info,
            forced = forced,
            onInstall = {
                dialogShowing = false
                startDownload(activity, info)
            },
            onLater = {
                dialogShowing = false
                AppConfig(activity).updateSkippedVersion = info.versionCode
            }
        )
    }

    private fun showInstallPrompt(activity: Activity, file: File, info: UpdateInfo) {
        if (installDialogShowing || installPromptDismissed || !activity.canShowDialog()) return
        installDialogShowing = true
        val installed = UpdateVersion.installedVersionCode(activity)
        val forced = info.forceUpdate ||
            (info.minSupportedVersionCode > 0 && installed < info.minSupportedVersionCode)

        UpdateUi.showInstallDialog(
            activity = activity,
            file = file,
            forced = forced,
            onInstall = {
                installDialogShowing = false
                if (UpdateInstaller.install(activity, file)) {
                    val config = AppConfig(activity)
                    config.updateInstallReady = false
                    config.updateDownloadId = -1L
                }
                // false = Berechtigungsseite wurde geöffnet; installReady bleibt true,
                // damit der Dialog nach der Rückkehr erneut erscheint.
            },
            onLater = {
                installDialogShowing = false
                installPromptDismissed = true
            }
        )
    }

    /** Nur aus einer sichtbaren (resumten) Activity heraus Dialoge zeigen. */
    private fun Activity.canShowDialog(): Boolean =
        !isFinishing && !isDestroyed &&
            (this as? LifecycleOwner)?.lifecycle?.currentState
                ?.isAtLeast(Lifecycle.State.RESUMED) == true

    private fun cachedInfo(config: AppConfig): UpdateInfo? {
        val json = config.updateCachedInfoJson
        if (json.isBlank()) return null
        return runCatching { UpdateInfo.fromJson(json) }.getOrNull()
    }

    private fun ensureDownloadReceiver(context: Context) {
        if (receiverRegistered) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (id > 0 && id == AppConfig(app).updateDownloadId) {
                    handleDownloadComplete(app, id)
                }
            }
        }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            // System-Broadcast von DownloadManager — muss vom System zugestellt werden.
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }
}
