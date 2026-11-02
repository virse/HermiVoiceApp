package de.hermi.voice

import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        window.decorView.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, SPLASH_DURATION_MS)
    }
}