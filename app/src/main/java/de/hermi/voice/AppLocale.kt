package de.hermi.voice

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import de.hermi.voice.config.AppConfig
import java.util.Locale

/**
 * Zentrale Locale-Logik (AppCompat-Weg):
 *
 * - Sprache wird NUR App-intern umgestellt (AppCompatDelegate#setApplicationLocales),
 *   die System-Locale bleibt unangetastet.
 * - Modus "auto": folgt der Systemsprache, aber mit Englisch als Fallback —
 *   jede Nicht-Deutsch-Systemsprache führt also zur App-Sprache Englisch
 *   (die App bietet nur DE/EN an).
 * - Modus "de"/"en": erzwingt die jeweilige Sprache unabhängig vom System.
 *
 * [applyToApp] wird in der Application-Klasse beim Start aufgerufen; die
 * Settings-Activity ruft es nach einer Sprachänderung erneut, wodurch
 * AppCompatActivity die laufenden Activities automatisch neu erstellt.
 */
object AppLocale {

    /** Modus-Ausdrücke, wie sie in AppConfig gespeichert werden. */
    const val MODE_AUTO = "auto"
    const val MODE_DE = "de"
    const val MODE_EN = "en"

    /**
     * Liefert das effektive BCP-47-Locale-Tag ("de"/"en") für den aktuellen
     * Sprachmodus. Bei "auto" wird die Systemsprache herangezogen, jede
     * Nicht-Deutsch-Sprache fällt auf Englisch zurück.
     */
    fun effectiveLanguageTag(appContext: Context): String {
        val mode = AppConfig(appContext).uiLanguageMode
        return when (mode) {
            MODE_DE -> "de"
            MODE_EN -> "en"
            else -> if (Locale.getDefault().language.startsWith("de")) "de" else "en"
        }
    }

    /** Das effektive [Locale] (für Datums-/Zahlenformatierung in der App). */
    fun effectiveLocale(appContext: Context): Locale =
        Locale.forLanguageTag(effectiveLanguageTag(appContext))

    /**
     * Wendet die App-interne Sprache über AppCompatActivity an und sorgt so
     * dafür, dass alle Aktivitäten in der passenden Sprache neu aufgebaut
     * werden.
     */
    fun applyToApp(appContext: Context) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(effectiveLanguageTag(appContext))
        )
    }
}