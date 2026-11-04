package de.hermi.voice.update

import android.app.Activity
import android.app.AlertDialog
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import de.hermi.voice.R
import io.noties.markwon.Markwon
import java.io.File

/**
 * Dialoge für Update-Prüfung und Installation. Bewusst mit klassischen Views
 * (AlertDialog + TextView), passend zum restlichen App-Stack — kein Compose.
 */
object UpdateUi {

    /**
     * Zeigt den Update-Dialog. [forced] = true blendet „Später“ aus und macht
     * den Dialog nicht abbrechbar.
     */
    fun showUpdateDialog(
        activity: Activity,
        info: UpdateInfo,
        forced: Boolean,
        onInstall: () -> Unit,
        onLater: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val density = activity.resources.displayMetrics.density
        val padding = (20 * density).toInt()

        val textView = TextView(activity).apply {
            textSize = 15f
            setTextColor(activity.resources.getColor(R.color.text_primary, activity.theme))
            setPadding(padding, padding, padding, padding)
            movementMethod = LinkMovementMethod.getInstance()
        }

        val markdown = buildString {
            append("**Version ").append(info.versionName).append("**\n\n")
            if (info.changelog.isNotBlank()) {
                append(info.changelog)
            } else {
                append(
                    activity.getString(
                        R.string.update_available_message,
                        info.versionName,
                        UpdateVersion.installedVersionName(activity)
                    )
                )
            }
        }
        Markwon.create(activity).setMarkdown(textView, markdown)

        val scroll = ScrollView(activity).apply {
            addView(
                textView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(
                if (forced) R.string.update_force_title
                else R.string.update_available_title
            )
            .setView(scroll)
            .setPositiveButton(R.string.update_download) { _, _ -> onInstall() }
            .setCancelable(!forced)

        if (!forced) {
            builder.setNegativeButton(R.string.update_later) { _, _ -> onLater() }
            builder.setOnCancelListener { onLater() }
        }

        builder.show()
    }

    /** Zeigt den Install-Dialog für eine bereits geprüfte APK. */
    fun showInstallDialog(
        activity: Activity,
        file: File,
        forced: Boolean,
        onInstall: () -> Unit,
        onLater: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.update_install_ready_title)
            .setMessage(R.string.update_install_ready_message)
            .setPositiveButton(R.string.update_install) { _, _ -> onInstall() }
            .setCancelable(!forced)

        if (!forced) {
            builder.setNegativeButton(R.string.update_later) { _, _ -> onLater() }
            builder.setOnCancelListener { onLater() }
        }

        builder.show()
    }

    /** Einfacher Hinweis (z. B. „Du bist auf dem neuesten Stand“). */
    fun showInfo(activity: Activity, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
