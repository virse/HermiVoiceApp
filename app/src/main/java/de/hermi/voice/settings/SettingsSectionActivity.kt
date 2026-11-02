package de.hermi.voice.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.hermi.voice.config.AppConfig
import de.hermi.voice.R

/**
 * Gemeinsame Basis für alle Einstellungs-Sub-Screens.
 *
 * Übernimmt das einheitliche Grundgerüst: Akzent-Overlay-Theme vor dem
 * Rendern, Edge-to-Edge-Insets-Absicherung, Titel, Back-Navigation und den
 * zentralen Config-Zugriff. Jede Sektion liefert nur noch ihr Layout.
 */
abstract class SettingsSectionActivity : AppCompatActivity() {

    protected lateinit var config: AppConfig

    /** Inhalt des Sub-Screens. */
    protected abstract val layoutRes: Int

    /** Titel über der ActionBar. */
    protected abstract val titleRes: Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        // Akzentfarben-Overlay-Theme anwenden, bevor der Inhalt gerendert wird,
        // damit ?attr/colorAccent & Co. im Theme aufgelöst werden.
        setTheme(config.accentTheme())
        setContentView(layoutRes)
        // Sektionstitel als Kopfzeile anzeigen (Akzentfarbe via ?attr/colorAccent im Layout).
        findViewById<TextView>(R.id.sectionTitle)?.setText(titleRes)
        // Edge-to-Edge (targetSdk 36): Inhalte unter die Systemleisten abfangen.
        // IME-Insets mit einbeziehen: Bei geöffneter Tastatur wird der Inhalt
        // angehoben/verkleinert, damit ein ScrollView die unteren Felder
        // (z. B. Name-Eingabe in „Avatare & Name“) nicht hinter der Tastatur
        // versteckt, sondern zum Fokusfeld scrollen kann.
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById<View>(android.R.id.content)
        ) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        title = getString(titleRes)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}