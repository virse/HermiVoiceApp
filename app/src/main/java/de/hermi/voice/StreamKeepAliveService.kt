package de.hermi.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground-Service, der den Prozess am Leben hält, solange eine Streaming-
 * Antwort läuft. Ohne ihn friert Android die App beim Wechsel in den Hintergrund
 * ein bzw. kappt die Verbindung (Cached-Apps-Freezer/Doze) — der Stream bricht ab.
 *
 * Der Stream-Thread selbst bleibt in der MainActivity; der Service hält nur den
 * Prozess (Foreground-Priorität + Partial-WakeLock) und zeigt die Dauer-
 * Benachrichtigung mit „Abbrechen“. Start beim Senden, Stopp nach Stream-Ende.
 */
class StreamKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val safetyStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // Wie der Stopp-Button im Chat: sauberer Nutzer-Abbruch.
            cancelHandler?.invoke()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        try {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (_: Exception) {
            // Start nicht erlaubt (z. B. aus dem Hintergrund): Stream läuft ohne Service weiter.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermi:stream")?.apply {
                setReferenceCounted(false)
                acquire(MAX_RUNTIME_MS)
            }
        }
        // Sicherheitsnetz: Bleibt der Stop aus (Thread-Fehler), beendet sich der Service selbst.
        handler.removeCallbacks(safetyStop)
        handler.postDelayed(safetyStop, MAX_RUNTIME_MS)
        return START_NOT_STICKY
    }

    /** Android 15+: dataSync-Services haben ein Laufzeitlimit; dann sauber beenden. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(safetyStop)
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        createChannel(this)
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, StreamKeepAliveService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.stream_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stream_notification_cancel), cancel)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "hermi_stream_active"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_CANCEL = "de.hermi.voice.action.CANCEL_STREAM"
        /** Obergrenze: größte Stream-Grenze (15 min) + First-Token + Puffer. */
        private const val MAX_RUNTIME_MS = 20 * 60 * 1000L

        /** Wird von der MainActivity beim Streamstart gesetzt (Abbruch wie Stopp-Button). */
        @Volatile var cancelHandler: (() -> Unit)? = null

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.stream_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, StreamKeepAliveService::class.java))
            } catch (_: Exception) {
                // Nicht erlaubt (Hintergrundstart): Stream läuft ohne Service wie bisher.
            }
        }

        fun stop(context: Context) {
            cancelHandler = null
            try {
                context.stopService(Intent(context, StreamKeepAliveService::class.java))
            } catch (_: Exception) {}
        }
    }
}
