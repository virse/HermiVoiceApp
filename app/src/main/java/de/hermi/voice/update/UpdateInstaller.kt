package de.hermi.voice.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Installation der geprüften APK über den bestehenden HermiFileProvider. */
object UpdateInstaller {

    /**
     * Startet den System-Installer. Gibt false zurück, wenn zuerst die
     * Berechtigung „Unbekannte Quellen“ erteilt werden muss — dann wurde die
     * passende Einstellungsseite bereits geöffnet.
     */
    fun install(activity: Activity, file: File): Boolean {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return false
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
        return true
    }

    fun canInstall(activity: Activity): Boolean =
        activity.packageManager.canRequestPackageInstalls()
}
