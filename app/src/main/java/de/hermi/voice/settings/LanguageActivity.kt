package de.hermi.voice.settings

import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Toast
import de.hermi.voice.AppLocale
import de.hermi.voice.R
import de.hermi.voice.config.AppConfig

/**
 * Sprachauswahl der App-Oberfläche: Automatisch (System folgen, EN-Fallback),
 * Deutsch oder English. Auswahl wird sofort gespeichert und über den
 * AppCompat-Weg (setApplicationLocales) aktiv — die laufenden Activities werden
 * dabei automatisch in der neuen Sprache neu aufgebaut.
 */
class LanguageActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_language
    override val titleRes: Int = R.string.language_section_title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val group = findViewById<RadioGroup>(R.id.languageRadioGroup)

        // Aktuellen Modus ZUERST vorbelegen — group.check() feuert den
        // Listener synchron, daher darf der Listener hier noch nicht
        // registriert sein (sonst Toast + Locale-Anwendung bei jedem Öffnen).
        when (config.uiLanguageMode) {
            AppLocale.MODE_DE -> group.check(R.id.languageDe)
            AppLocale.MODE_EN -> group.check(R.id.languageEn)
            else -> group.check(R.id.languageAuto)
        }

        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.languageDe -> AppLocale.MODE_DE
                R.id.languageEn -> AppLocale.MODE_EN
                else -> AppLocale.MODE_AUTO
            }
            config.uiLanguageMode = mode
            // Gekoppelt: Die UI-Sprache bestimmt die STT-Sprache (activeSttLanguage).
            config.activeSttLanguage = AppLocale.effectiveLanguageTag(this)
            AppLocale.applyToApp(this)
            Toast.makeText(this, R.string.language_saved, Toast.LENGTH_SHORT).show()
        }
    }
}