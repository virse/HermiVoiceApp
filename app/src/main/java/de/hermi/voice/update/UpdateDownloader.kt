package de.hermi.voice.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Kapselt DownloadManager für die Update-APK. Fester Dateiname, damit ein
 * abgebrochener/fortgesetzter Download und die spätere Installation über den
 * FileProvider immer denselben Pfad finden.
 */
object UpdateDownloader {

    const val FILE_NAME = "hermivoice-update.apk"

    /** Startet den Download. Gibt die Download-ID zurück. */
    fun enqueue(context: Context, info: UpdateInfo): Long {
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("HermiVoice ${info.versionName}")
            .setDescription("App-Update wird heruntergeladen")
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                FILE_NAME
            )
            // Nur Fortschritt anzeigen; die Abschluss-Benachrichtigung würde
            // sonst direkt zum ungeprüften APK führen. Die Prüfung + Installation
            // übernimmt die App selbst.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    fun query(context: Context, downloadId: Long): DownloadStatus {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return DownloadStatus.Unknown
        cursor.use {
            if (!it.moveToFirst()) return DownloadStatus.Unknown
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val localUri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    DownloadStatus.Success(localUri?.let { u -> Uri.parse(u).path }?.let { p -> File(p) })
                }
                DownloadManager.STATUS_FAILED -> DownloadStatus.Failed
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED -> DownloadStatus.Running
                else -> DownloadStatus.Unknown
            }
        }
    }

    /** Fester Zielpfad im App-spezifischen externen Verzeichnis. */
    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    sealed interface DownloadStatus {
        data object Running : DownloadStatus
        data object Failed : DownloadStatus
        data object Unknown : DownloadStatus
        data class Success(val file: File?) : DownloadStatus
    }
}
