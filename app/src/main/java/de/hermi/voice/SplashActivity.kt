package de.hermi.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Eigener Splash-Screen. Wird als Launcher-Activity über jede App-Version hinweg
 * konsistent angezeigt (der System-Splash von Android 12+ erscheint nur beim Kaltstart
 * auf neuen Geräten) und leitet nach kurzer Zeit in die MainActivity über.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DURATION_MS = 900L
    }

    // Bugfix 1.0.1: eigener Handler statt decorView.postDelayed — nur so lässt sich
    // der Start der MainActivity beim Verlassen (Back-Taste) wieder abbestellen.
    private val handler = Handler(Looper.getMainLooper())
    private val startMain = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        handler.postDelayed(startMain, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        // Ohne removeCallbacks würde ein abgebrochener Splash (Back-Taste) die
        // MainActivity trotzdem noch starten.
        handler.removeCallbacks(startMain)
        super.onDestroy()
    }
}
