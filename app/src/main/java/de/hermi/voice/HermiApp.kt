package de.hermi.voice

import android.app.Application
import de.hermi.voice.config.AppConfig

/**
 * Application-Klasse: wendet beim Start die App-interne Sprache (AppCompat-
 * Locale-Mechanik, setApplicationLocales) an, bevor irgendeine Activity
 * Resources lädt. Siehe [AppLocale].
 */
class HermiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.applyToApp(this)
        // Kopplung (Entscheidung 3): Die STT-Sprache (activeSttLanguage) wird
        // mit der tatsächlich effektiven UI-Sprache synchronisiert, BEVOR irgendein
        // ensureRecognizer()-Pfad läuft. Nur LanguageActivity setzt diesen Wert —
        // ohne diese Zeile bliebe er im "auto"-Modus (z.B. Erststart mit
        // englischer Systemsprache) auf dem persistierten Default "de", obwohl
        // die UI bereits Englisch ist und so das falsche Modell lädt. Das Setzen
        // beim Start hält LanguageActivity als Single Source of Truth und
        // persistiert hier zugleich den korrekten Wert.
        AppConfig(this).activeSttLanguage = AppLocale.effectiveLanguageTag(this)
    }
}