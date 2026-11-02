package de.hermi.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.hermi.voice.R

/**
 * Baut und postet die „Antwort fertig“-Benachrichtigung.
 *
 * Keine Hintergrund-Dienste nötig: Die Benachrichtigung wird im Moment der
 * abgeschlossenen Antwort gepostet, während die App (im Prozess) läuft. Der
 * Channel wird einmalig beim App-Start angelegt — bei minSdk 26 ist die
 * Channel-Anlage immer Pflicht (NotificationChannel gibt es seit API 26).
 */
object NotificationHelper {

    const val CHANNEL_ID = "hermi_task_done"
    private const val NOTIFICATION_ID = 1001

    /** Maximale Länge der Textvorschau (eine Zeile) in der Benachrichtigung. */
    private const val PREVIEW_MAX_CHARS = 120

    /** Legt den Benachrichtigungskanal an. Idempotent — bei jedem App-Start
     *  gegen die vorhandene Konfiguration prüfend aufrufen. */
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
        )
    }

    /** Postet die „Antwort fertig“-Benachrichtigung mit Titel + kurzer Vorschau.
     *  Antippen öffnet die App in diesem Chat. Fehlt die Berechtigung
     *  (Android 13+), wird der Aufruf still ignoriert (NotificationManagerCompat). */
    fun notifyAnswer(context: Context, answer: String) {
        val flat = answer.trim().replace(Regex("\\s+"), " ").trim()
        val preview = flat.take(PREVIEW_MAX_CHARS).let {
            if (it.length < flat.length) "$it …" else it
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_answer_title))
            .setContentText(preview)
            // Volle Antwort über Langes-Drücken/Nach-unten-Ziehen einsehbar.
            // Bewusst KEIN BigTextStyle mit der vollen Antwort: Die Benachrichtigung
            // (auch auf dem Sperrbildschirm) zeigt nur „Antwort fertig“ + kurzen
            // Auszug (PREVIEW_MAX_CHARS); den vollständigen Text sieht man in der App.
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Öffnet den Chat bei Antippen; bringt eine bereits laufende Activity nach
     *  vorn statt eine zweite Instanz zu erzeugen (singleTop + CLEAR_TOP). */
    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}